package org.example.rentoza.booking.checkin.cqrs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.websocket.CheckInWebSocketController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Event listener for synchronizing the Check-In Status View read model.
 * 
 * <h2>CQRS Event-Driven Sync</h2>
 * <p>Listens to domain events published by the Command Service and updates
 * the denormalized read model (CheckInStatusView) and invalidates caches.
 * 
 * <h2>Processing Flow</h2>
 * <pre>
 * Command Service ─────────────────────┐
 *     │                                │
 *     │ publishes                      │
 *     ▼                                │
 * ApplicationEventPublisher            │
 *     │                                │
 *     │ delivers                       │
 *     ▼                                │
 * CheckInStatusViewSyncListener        │
 *     │                                │
 *     ├──► Update CheckInStatusView    │
 *     │                                │
 *     └──► Evict Redis Caches          │
 * </pre>
 * 
 * <h2>Transactional Behavior</h2>
 * <p>Uses REQUIRES_NEW propagation to ensure view updates are committed
 * independently of the command transaction. This provides better isolation
 * and recovery in case of view update failures.
 * 
 * @see CheckInDomainEvent for event types
 * @see CheckInCommandService for event producers
 */
@Component
@Slf4j
public class CheckInStatusViewSyncListener {

    private final CheckInStatusViewRepository viewRepository;
    private final BookingRepository bookingRepository;
    private final CacheManager cacheManager;
    private final CheckInWebSocketController webSocketController;
    private final CheckInQueryService queryService;

    // Metrics
    private final Counter syncSuccessCounter;
    private final Counter syncFailureCounter;
    private final Timer syncLatencyTimer;

    @Value("${app.checkin.no-show-minutes-after-trip-start:${app.checkin.noshow.grace-minutes:30}}")
    private int noShowMinutesAfterTripStart;

    public CheckInStatusViewSyncListener(
            CheckInStatusViewRepository viewRepository,
            BookingRepository bookingRepository,
            CacheManager cacheManager,
            CheckInWebSocketController webSocketController,
            CheckInQueryService queryService,
            MeterRegistry meterRegistry) {
        this.viewRepository = viewRepository;
        this.bookingRepository = bookingRepository;
        this.cacheManager = cacheManager;
        this.webSocketController = webSocketController;
        this.queryService = queryService;

        this.syncSuccessCounter = Counter.builder("checkin.view.sync.success")
                .description("Successful view synchronizations")
                .register(meterRegistry);

        this.syncFailureCounter = Counter.builder("checkin.view.sync.failure")
                .description("Failed view synchronizations")
                .register(meterRegistry);

        this.syncLatencyTimer = Timer.builder("checkin.view.sync.latency")
                .description("View synchronization latency")
                .register(meterRegistry);
    }

    // ========== EVENT HANDLERS ==========

    /**
     * Handle host check-in completion event.
     * 
     * <p>Updates view with host completion status, odometer, and fuel readings.
     */
    @EventListener
    @Async("viewSyncExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onHostCheckInCompleted(CheckInDomainEvent.HostCheckInCompleted event) {
        log.debug("[View-Sync] Processing HostCheckInCompleted for booking {}", event.bookingId());

        syncLatencyTimer.record(() -> {
            try {
                // Ensure view exists
                CheckInStatusView view = getOrCreateView(event.bookingId());

                // Update view fields
                view.setHostCheckInComplete(true);
                view.setHostCompletedAt(event.occurredAt());
                view.setOdometerReading(event.odometerReading());
                view.setFuelLevelPercent(event.fuelLevelPercent());
                view.setStatus(BookingStatus.CHECK_IN_HOST_COMPLETE);
                view.setStatusDisplay("Domaćin završio prijem");
                view.setLastSyncAt(Instant.now());

                viewRepository.save(view);

                // Invalidate caches
                evictCachesForBooking(event.bookingId(), view.getHostUserId(), view.getGuestUserId());

                // Broadcast via WebSocket - notify guest that host completed
                broadcastStatusUpdate(event.bookingId(), view.getHostUserId(), view.getGuestUserId());

                syncSuccessCounter.increment();
                log.info("[View-Sync] Updated view for booking {} - host completed", event.bookingId());

            } catch (Exception e) {
                syncFailureCounter.increment();
                log.error("[View-Sync] Failed to sync HostCheckInCompleted for booking {}: {}",
                        event.bookingId(), e.getMessage(), e);
            }
        });
    }

    /**
     * Handle guest condition acknowledgment event.
     */
    @EventListener
    @Async("viewSyncExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onGuestConditionAcknowledged(CheckInDomainEvent.GuestConditionAcknowledged event) {
        log.debug("[View-Sync] Processing GuestConditionAcknowledged for booking {}", event.bookingId());

        syncLatencyTimer.record(() -> {
            try {
                CheckInStatusView view = getOrCreateView(event.bookingId());

                view.setGuestCheckInComplete(true);
                view.setGuestCompletedAt(event.occurredAt());
                view.setStatus(BookingStatus.CHECK_IN_COMPLETE);
                view.setStatusDisplay("Prijem završen - čeka rukovanje");
                view.setLastSyncAt(Instant.now());

                viewRepository.save(view);

                evictCachesForBooking(event.bookingId(), view.getHostUserId(), view.getGuestUserId());

                // Broadcast via WebSocket - notify host that guest acknowledged
                broadcastStatusUpdate(event.bookingId(), view.getHostUserId(), view.getGuestUserId());

                syncSuccessCounter.increment();
                log.info("[View-Sync] Updated view for booking {} - guest acknowledged", event.bookingId());

            } catch (Exception e) {
                syncFailureCounter.increment();
                log.error("[View-Sync] Failed to sync GuestConditionAcknowledged for booking {}: {}",
                        event.bookingId(), e.getMessage(), e);
            }
        });
    }

    /**
     * Handle trip started event.
     */
    @EventListener
    @Async("viewSyncExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTripStarted(CheckInDomainEvent.TripStarted event) {
        log.debug("[View-Sync] Processing TripStarted for booking {}", event.bookingId());

        syncLatencyTimer.record(() -> {
            try {
                CheckInStatusView view = getOrCreateView(event.bookingId());

                view.setHandshakeComplete(true);
                view.setTripStarted(true);
                view.setHandshakeCompletedAt(event.occurredAt());
                view.setTripStartedAt(event.occurredAt());
                view.setHandshakeMethod(event.handshakeMethod());
                view.setStatus(BookingStatus.IN_TRIP);
                view.setStatusDisplay("Putovanje u toku");
                view.setLastSyncAt(Instant.now());

                viewRepository.save(view);

                evictCachesForBooking(event.bookingId(), view.getHostUserId(), view.getGuestUserId());

                // Broadcast via WebSocket - trip started, notify both parties
                broadcastStatusUpdate(event.bookingId(), view.getHostUserId(), view.getGuestUserId());

                syncSuccessCounter.increment();
                log.info("[View-Sync] Updated view for booking {} - trip started via {}",
                        event.bookingId(), event.handshakeMethod());

            } catch (Exception e) {
                syncFailureCounter.increment();
                log.error("[View-Sync] Failed to sync TripStarted for booking {}: {}",
                        event.bookingId(), e.getMessage(), e);
            }
        });
    }

    /**
     * Handle no-show processed event.
     */
    @EventListener
    @Async("viewSyncExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onNoShowProcessed(CheckInDomainEvent.NoShowProcessed event) {
        log.debug("[View-Sync] Processing NoShowProcessed for booking {}", event.bookingId());

        syncLatencyTimer.record(() -> {
            try {
                CheckInStatusView view = getOrCreateView(event.bookingId());

                view.setNoShowParty(event.noShowParty());

                BookingStatus status = "HOST".equals(event.noShowParty())
                        ? BookingStatus.NO_SHOW_HOST
                        : BookingStatus.NO_SHOW_GUEST;
                view.setStatus(status);

                String displayText = "HOST".equals(event.noShowParty())
                        ? "Domaćin se nije pojavio"
                        : "Gost se nije pojavio";
                view.setStatusDisplay(displayText);

                view.setLastSyncAt(Instant.now());

                viewRepository.save(view);

                evictCachesForBooking(event.bookingId(), view.getHostUserId(), view.getGuestUserId());

                // Broadcast via WebSocket - no-show notification
                broadcastStatusUpdate(event.bookingId(), view.getHostUserId(), view.getGuestUserId());

                syncSuccessCounter.increment();
                log.info("[View-Sync] Updated view for booking {} - no-show: {}",
                        event.bookingId(), event.noShowParty());

            } catch (Exception e) {
                syncFailureCounter.increment();
                log.error("[View-Sync] Failed to sync NoShowProcessed for booking {}: {}",
                        event.bookingId(), e.getMessage(), e);
            }
        });
    }

    /**
     * Handle check-in window opened event.
     */
    /**
     * Handle check-in window opened event.
     * 
     * <p><b>Race Condition Prevention:</b> This method creates the initial view AND
     * synchronizes the current booking status to prevent stale state if concurrent
     * events occur during view creation.
     */
    @EventListener
    @Async("viewSyncExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCheckInWindowOpened(CheckInDomainEvent.CheckInWindowOpened event) {
        log.debug("[View-Sync] Processing CheckInWindowOpened for booking {}", event.bookingId());

        syncLatencyTimer.record(() -> {
            try {
                // Create or get view - this is typically the first event
                CheckInStatusView view = getOrCreateView(event.bookingId());

                view.setCheckInOpenedAt(event.occurredAt());
                
                // RACE CONDITION FIX: Always sync status from booking to handle concurrent updates
                // (e.g., if host uploads photo immediately after window opens)
                Optional<Booking> bookingOpt = bookingRepository.findById(event.bookingId());
                if (bookingOpt.isPresent()) {
                    Booking booking = bookingOpt.get();
                    view.setStatus(booking.getStatus());
                    view.setStatusDisplay(mapStatusToDisplay(booking.getStatus()));
                } else {
                    // Fallback if booking not found
                    view.setStatus(BookingStatus.CHECK_IN_OPEN);
                    view.setStatusDisplay("Prijem otvoren");
                }
                
                view.setLastSyncAt(Instant.now());

                viewRepository.save(view);

                evictCachesForBooking(event.bookingId(), view.getHostUserId(), view.getGuestUserId());

                // Broadcast via WebSocket - window opened, notify both parties
                broadcastStatusUpdate(event.bookingId(), view.getHostUserId(), view.getGuestUserId());

                syncSuccessCounter.increment();
                log.info("[View-Sync] Created/updated view for booking {} - window opened (status: {})", 
                        event.bookingId(), view.getStatus());

            } catch (Exception e) {
                syncFailureCounter.increment();
                log.error("[View-Sync] Failed to sync CheckInWindowOpened for booking {}: {}",
                        event.bookingId(), e.getMessage(), e);
            }
        });
    }
    
    /**
     * Map booking status to user-friendly display text.
     */
    private String mapStatusToDisplay(BookingStatus status) {
        return switch (status) {
            case CHECK_IN_OPEN -> "Prijem otvoren";
            case CHECK_IN_HOST_COMPLETE -> "Domaćin završio";
            case IN_TRIP -> "U toku";
            case CHECKOUT_OPEN -> "Povratak otvoren";
            case CHECKOUT_GUEST_COMPLETE -> "Gost završio";
            case CHECKOUT_HOST_COMPLETE -> "Domaćin završio";
            case COMPLETED -> "Završeno";
            default -> status.name();
        };
    }

    /**
     * Handle photo uploaded event - atomic upsert version.
     * 
     * <p>Uses PostgreSQL INSERT...ON CONFLICT to prevent race conditions.
     * No retry logic needed - single atomic operation.
     * 
     * <p><b>Before (Broken):</b>
     * <pre>
     * SELECT view → IF NOT EXISTS → INSERT → RACE CONDITION!
     * </pre>
     * 
     * <p><b>After (Fixed):</b>
     * <pre>
     * INSERT...ON CONFLICT DO UPDATE → Atomic, impossible to race
     * </pre>
     */
    @EventListener
    @Async("viewSyncExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPhotoUploaded(CheckInDomainEvent.PhotoUploaded event) {
        log.debug("[View-Sync] Processing PhotoUploaded for booking {}", event.bookingId());

        syncLatencyTimer.record(() -> {
            try {
                // Fetch booking data for denormalized fields
                Booking booking = bookingRepository.findByIdWithRelations(event.bookingId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Cannot sync photo - booking not found: " + event.bookingId()));

                var host = booking.getCar().getOwner();
                var guest = booking.getRenter();
                var car = booking.getCar();

                // ✅ Atomic upsert - race condition impossible
                // No retry logic needed - PostgreSQL ON CONFLICT handles concurrency
                viewRepository.upsertPhotoCount(
                        event.bookingId(),
                        UUID.fromString(booking.getCheckInSessionId()),
                        host.getId(),
                        host.getFirstName() + " " + host.getLastName(),
                        host.getPhone(),
                        guest.getId(),
                        guest.getFirstName() + " " + guest.getLastName(),
                        guest.getPhone(),
                        car.getId(),
                        car.getBrand(),
                        car.getModel(),
                        car.getYear(),
                        car.getImageUrl(),
                        car.getLicensePlate(),
                        booking.getStatus().name(),
                        mapStatusToDisplay(booking.getStatus()),
                        booking.getStartTime(),
                        booking.getLockboxCodeEncrypted() != null,
                        booking.getGeofenceDistanceMeters()
                );

                evictPhotoCaches(event.bookingId(), host.getId(), guest.getId());

                syncSuccessCounter.increment();
                log.info("[View-Sync] Updated photo count for booking {}", event.bookingId());

            } catch (Exception e) {
                syncFailureCounter.increment();
                log.error("[View-Sync] Failed to sync PhotoUploaded for booking {}: {}",
                        event.bookingId(), e.getMessage(), e);
            }
        });
    }

    // ========== HELPER METHODS ==========

    /**
     * Get existing view or create from booking data.
     */
    private CheckInStatusView getOrCreateView(Long bookingId) {
        return viewRepository.findByBookingId(bookingId)
                .orElseGet(() -> createViewFromBooking(bookingId));
    }

    /**
     * Create a new view by loading booking data.
     */
    private CheckInStatusView createViewFromBooking(Long bookingId) {
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot create view - booking not found: " + bookingId));

        var host = booking.getCar().getOwner();
        var guest = booking.getRenter();
        var car = booking.getCar();

        CheckInStatusView view = CheckInStatusView.fromBookingData(
                bookingId,
                UUID.fromString(booking.getCheckInSessionId()),
                host.getId(),
                host.getFirstName() + " " + host.getLastName(),
                host.getPhone(),
                guest.getId(),
                guest.getFirstName() + " " + guest.getLastName(),
                guest.getPhone(),
                car.getId(),
                car.getBrand(),
                car.getModel(),
                car.getYear(),
                car.getImageUrl(),
                car.getLicensePlate(),
                booking.getStatus(),
                booking.getStartTime()
        );

        // Copy existing timestamps if any
        view.setCheckInOpenedAt(booking.getCheckInOpenedAt());
        view.setHostCompletedAt(booking.getHostCheckInCompletedAt());
        view.setGuestCompletedAt(booking.getGuestCheckInCompletedAt());
        view.setHandshakeCompletedAt(booking.getHandshakeCompletedAt());
        view.setTripStartedAt(booking.getTripStartedAt());

        // Copy existing data
        view.setOdometerReading(booking.getStartOdometer());
        view.setFuelLevelPercent(booking.getStartFuelLevel());
        view.setLockboxAvailable(booking.getLockboxCodeEncrypted() != null);
        view.setGeofenceDistanceMeters(booking.getGeofenceDistanceMeters());

        // Derive completion flags
        view.setHostCheckInComplete(booking.getHostCheckInCompletedAt() != null);
        view.setGuestCheckInComplete(booking.getGuestCheckInCompletedAt() != null);
        view.setHandshakeComplete(booking.getHandshakeCompletedAt() != null);
        view.setTripStarted(booking.getTripStartedAt() != null);

        // Calculate no-show deadline
        if (booking.getStartTime() != null) {
            view.setNoShowDeadline(booking.getStartTime().plusMinutes(noShowMinutesAfterTripStart));
        }

        log.info("[View-Sync] Created new view for booking {}", bookingId);

        return viewRepository.save(view);
    }

    /**
     * Evict all caches for a booking.
     */
    private void evictCachesForBooking(Long bookingId, Long hostUserId, Long guestUserId) {
        try {
            // Evict check-in status caches
            evictCache("checkin-status", bookingId + "-" + hostUserId);
            evictCache("checkin-status", bookingId + "-" + guestUserId);
            evictCache("checkin-status-minimal", bookingId.toString());

            // Evict photo caches
            evictCache("checkin-photos", bookingId + "-" + hostUserId);
            evictCache("checkin-photos", bookingId + "-" + guestUserId);

            // Evict dashboard caches
            evictCache("checkin-dashboard", hostUserId.toString());
            evictCache("checkin-dashboard", guestUserId.toString());

            log.debug("[View-Sync] Evicted caches for booking {} (host: {}, guest: {})",
                    bookingId, hostUserId, guestUserId);

        } catch (Exception e) {
            log.warn("[View-Sync] Cache eviction error for booking {}: {}",
                    bookingId, e.getMessage());
        }
    }

    /**
     * Evict photo-specific caches.
     */
    private void evictPhotoCaches(Long bookingId, Long hostUserId, Long guestUserId) {
        try {
            evictCache("checkin-photos", bookingId + "-" + hostUserId);
            evictCache("checkin-photos", bookingId + "-" + guestUserId);
        } catch (Exception e) {
            log.warn("[View-Sync] Photo cache eviction error: {}", e.getMessage());
        }
    }

    /**
     * Safely evict a cache entry.
     */
    private void evictCache(String cacheName, String key) {
        var cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(key);
        }
    }

    // ========== WEBSOCKET BROADCASTING ==========

    /**
     * Broadcast status update via WebSocket to both host and guest.
     * 
     * <p>This enables real-time push notifications, replacing the 30-second polling
     * that clients previously used. The latency from state change to client notification
     * is typically &lt;50ms.
     * 
     * @param bookingId Booking identifier
     * @param hostUserId Host's user ID
     * @param guestUserId Guest's user ID
     */
    private void broadcastStatusUpdate(Long bookingId, Long hostUserId, Long guestUserId) {
        try {
            // Get fresh status for host perspective
            var hostStatus = queryService.getCheckInStatusForWebSocket(bookingId, hostUserId);
            if (hostStatus != null) {
                webSocketController.sendStatusUpdateToUser(hostUserId, bookingId, hostStatus);
            }

            // Get fresh status for guest perspective (may have different fields visible)
            var guestStatus = queryService.getCheckInStatusForWebSocket(bookingId, guestUserId);
            if (guestStatus != null) {
                webSocketController.sendStatusUpdateToUser(guestUserId, bookingId, guestStatus);
            }

            log.debug("[View-Sync] Broadcasted status update for booking {} to host {} and guest {}",
                    bookingId, hostUserId, guestUserId);

        } catch (Exception e) {
            // WebSocket broadcast failure should not fail the sync operation
            log.warn("[View-Sync] WebSocket broadcast failed for booking {}: {}",
                    bookingId, e.getMessage());
        }
    }
}

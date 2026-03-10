package org.example.rentoza.booking.checkin.cqrs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.CheckInEventService;
import org.example.rentoza.booking.checkin.CheckInEventType;
import org.example.rentoza.booking.checkin.CheckInPhoto;
import org.example.rentoza.booking.checkin.CheckInPhotoRepository;
import org.example.rentoza.booking.checkin.GeofenceService;
import org.example.rentoza.booking.checkin.dto.CheckInPhotoDTO;
import org.example.rentoza.booking.checkin.dto.CheckInStatusDTO;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * CQRS Query Service for Check-In Read Operations.
 * 
 * <h2>CQRS Pattern Implementation</h2>
 * <p>This service handles all read operations (queries) for the check-in workflow,
 * with Redis caching for improved performance.
 * 
 * <h2>Caching Strategy</h2>
 * <ul>
 *   <li>{@code checkin-status}: Individual booking check-in status (30s TTL)</li>
 *   <li>{@code checkin-photos}: Photo lists per booking (60s TTL)</li>
 *   <li>{@code checkin-timeline}: Event timeline per session (120s TTL)</li>
 * </ul>
 * 
 * <h2>Cache Invalidation</h2>
 * <p>Caches are invalidated by {@link CheckInStatusViewSyncListener} when
 * domain events are received from the Command Service.
 * 
 * @see CheckInCommandService for write operations
 * @see CheckInStatusViewSyncListener for cache invalidation
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CheckInQueryService {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");

    private final BookingRepository bookingRepository;
    private final CheckInPhotoRepository photoRepository;
    private final CheckInEventService eventService;
    private final GeofenceService geofenceService;
    private final CheckInStatusViewRepository viewRepository;

    @Value("${app.checkin.no-show-minutes-after-trip-start:${app.checkin.noshow.grace-minutes:30}}")
    private int noShowGraceMinutes;

    // ========== PRIMARY QUERIES ==========

    /**
     * Query: Get current check-in status for a booking.
     * 
     * <p>Cached with 30-second TTL for dashboard polling.
     * Cache key includes userId to prevent cross-user cache pollution.
     * 
     * @param bookingId Booking to query
     * @param userId Current user (for access validation and role flags)
     * @return Complete check-in status DTO
     * @throws ResourceNotFoundException if booking not found
     * @throws AccessDeniedException if user not participant
     */
    /**
     * Get check-in status for a booking.
     * 
     * <p><b>CQRS Phase 3 Optimization:</b> Uses CheckInStatusView for fast reads (10-20ms)
     * with fallback to Booking table if view not yet populated (300-500ms).
     * 
     * <p><b>Performance:</b>
     * <ul>
     *   <li>View hit: 10-20ms (single table, indexed)</li>
     *   <li>Cache hit: <5ms (Redis)</li>
     *   <li>Fallback: 300-500ms (complex JOINs)</li>
     * </ul>
     * 
     * @param bookingId Booking to query
     * @param userId Current user (for access validation)
     * @return Check-in status with all workflow metadata
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "checkin-status", key = "#bookingId + '-' + #userId", unless = "#result == null")
    public CheckInStatusDTO getCheckInStatus(Long bookingId, Long userId) {
        log.debug("[CheckIn-Query] Cache MISS for booking {} user {}", bookingId, userId);
        
        // CQRS Phase 3: Try view first (fast path)
        Optional<CheckInStatusView> viewOpt = viewRepository.findByBookingId(bookingId);
        
        if (viewOpt.isPresent()) {
            CheckInStatusView view = viewOpt.get();
            log.debug("[CheckIn-Query] Using CheckInStatusView for booking {} (view hit)", bookingId);
            
            // Validate access using view (no JOINs needed)
            validateAccessFromView(view, userId);
            
            // Map view to DTO (10-20ms)
            return mapViewToStatusDTO(view, userId);
        }
        
        // Fallback: View not yet populated (e.g., booking created before CQRS wiring)
        log.debug("[CheckIn-Query] View not found for booking {}, using Booking table (fallback)", bookingId);
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));

        validateAccess(booking, userId);

        return mapToStatusDTO(booking, userId);
    }

    /**
     * Query: Get check-in photos for a booking.
     * 
     * <p>Cached with 60-second TTL. Only returns non-deleted photos.
     * Photos are only visible to guest after host completes check-in.
     * 
     * @param bookingId Booking to query
     * @param userId Current user
     * @return List of photo DTOs
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "checkin-photos", key = "#bookingId + '-' + #userId", unless = "#result == null")
    public List<CheckInPhotoDTO> getCheckInPhotos(Long bookingId, Long userId) {
        log.debug("[CheckIn-Query] Photos cache MISS for booking {}", bookingId);
        
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));

        validateAccess(booking, userId);

        boolean isHost = isHost(booking, userId);
        
        // Only show photos to guest after host completes
        if (!isHost && booking.getStatus().ordinal() < BookingStatus.CHECK_IN_HOST_COMPLETE.ordinal()) {
            return List.of();
        }

        return photoRepository.findByBookingId(bookingId).stream()
                .filter(p -> !p.isDeleted())
                .map(this::mapToPhotoDTO)
                .collect(Collectors.toList());
    }

    /**
     * Query: Get minimal status for polling.
     * 
     * <p>Optimized query returning only status and completion flags.
     * Useful for frequent polling without full DTO construction.
     * 
     * @param bookingId Booking to query
     * @param userId Current user
     * @return Minimal status record
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "checkin-status-minimal", key = "#bookingId", unless = "#result == null")
    public CheckInStatusMinimal getMinimalStatus(Long bookingId, Long userId) {
        log.debug("[CheckIn-Query] Minimal status cache MISS for booking {}", bookingId);
        
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));

        validateAccess(booking, userId);

        return new CheckInStatusMinimal(
                booking.getId(),
                booking.getStatus(),
                booking.getHostCheckInCompletedAt() != null,
                booking.getGuestCheckInCompletedAt() != null,
                booking.getHandshakeCompletedAt() != null
        );
    }

    // ========== SCHEDULER SUPPORT QUERIES ==========

    /**
     * Query: Find bookings eligible for check-in window opening.
     * 
     * <p>Used by scheduler for T-24h detection.
     * Not cached - scheduler runs infrequently and needs fresh data.
     * 
     * @param startFrom Start of time window
     * @param startTo End of time window
     * @return Bookings ready for check-in window
     */
    @Transactional(readOnly = true)
    public List<Booking> findBookingsForCheckInWindowOpening(LocalDateTime startFrom, LocalDateTime startTo) {
        return bookingRepository.findBookingsForCheckInWindowOpening(startFrom, startTo);
    }

    /**
     * Query: Find bookings needing reminder notifications.
     * 
     * @param status Target status
     * @param openedBefore Threshold for reminder eligibility
     * @return Bookings needing reminders
     */
    @Transactional(readOnly = true)
    public List<Booking> findBookingsNeedingReminder(BookingStatus status, Instant openedBefore) {
        return bookingRepository.findBookingsNeedingReminder(status, openedBefore);
    }

    /**
     * Query: Find potential host no-shows.
     * 
     * @param status Target status
     * @param threshold Time threshold
     * @return Potential host no-shows
     */
    @Transactional(readOnly = true)
    public List<Booking> findPotentialHostNoShows(BookingStatus status, Instant threshold) {
        return bookingRepository.findPotentialHostNoShows(status, threshold);
    }

    /**
     * Query: Find potential guest no-shows.
     * 
     * @param status Target status
     * @param threshold Time threshold
     * @return Potential guest no-shows
     */
    @Transactional(readOnly = true)
    public List<Booking> findPotentialGuestNoShows(BookingStatus status, Instant threshold) {
        Instant hostCompletedBefore = threshold;
        return bookingRepository.findPotentialGuestNoShows(status, threshold, hostCompletedBefore);
    }

    // ========== CACHE INVALIDATION ==========

    /**
     * Evict all caches for a specific booking.
     * 
     * <p>Called by event listener when state changes.
     * 
     * @param bookingId Booking to invalidate
     */
    @CacheEvict(value = {"checkin-status", "checkin-photos", "checkin-status-minimal"}, 
                allEntries = false, key = "#bookingId + '-*'")
    public void evictBookingCache(Long bookingId) {
        log.debug("[CheckIn-Query] Evicting caches for booking {}", bookingId);
    }

    /**
     * Evict status cache for specific user.
     * 
     * @param bookingId Booking ID
     * @param userId User ID
     */
    @CacheEvict(value = "checkin-status", key = "#bookingId + '-' + #userId")
    public void evictStatusCache(Long bookingId, Long userId) {
        log.debug("[CheckIn-Query] Evicting status cache for booking {} user {}", bookingId, userId);
    }

    /**
     * Evict all check-in caches.
     * 
     * <p>Used for cache warming or recovery scenarios.
     */
    @CacheEvict(value = {"checkin-status", "checkin-photos", "checkin-status-minimal"}, allEntries = true)
    public void evictAllCaches() {
        log.info("[CheckIn-Query] Evicting all check-in caches");
    }

    // ========== HELPER METHODS ==========

    private boolean isHost(Booking booking, Long userId) {
        return booking.getCar().getOwner().getId().equals(userId);
    }

    private boolean isGuest(Booking booking, Long userId) {
        return booking.getRenter().getId().equals(userId);
    }

    private void validateAccess(Booking booking, Long userId) {
        if (!isHost(booking, userId) && !isGuest(booking, userId)) {
            throw new AccessDeniedException("Nemate pristup ovoj rezervaciji");
        }
    }

    /**
     * Validate access using denormalized view (no JOINs needed).
     * 
     * <p>CQRS optimization: Uses view fields instead of loading booking relationships.
     * Runs in 1-2ms vs 50-100ms for full booking load.
     */
    private void validateAccessFromView(CheckInStatusView view, Long userId) {
        if (!view.getHostUserId().equals(userId) && !view.getGuestUserId().equals(userId)) {
            throw new AccessDeniedException("Nemate pristup ovoj rezervaciji");
        }
    }

    /**
     * Map denormalized CheckInStatusView to DTO (Phase 3 CQRS optimization).
     * 
     * <p>CQRS fast path: Converts view directly to DTO without JOINs.
     * All data is pre-computed in the view, enabling 10-20ms response times.
     * 
     * @param view Denormalized read model
     * @param userId Current user ID
     * @return Complete check-in status DTO
     */
    private CheckInStatusDTO mapViewToStatusDTO(CheckInStatusView view, Long userId) {
        boolean isHost = view.getHostUserId().equals(userId);

        LocalDateTime noShowDeadline = view.getNoShowDeadline();
        Long minutesUntilNoShow = null;
        if (noShowDeadline != null) {
            minutesUntilNoShow = ChronoUnit.MINUTES.between(LocalDateTime.now(SERBIA_ZONE), noShowDeadline);
            if (minutesUntilNoShow < 0) minutesUntilNoShow = 0L;
        }

        return CheckInStatusDTO.builder()
                .bookingId(view.getBookingId())
                .checkInSessionId(view.getSessionId() != null ? view.getSessionId().toString() : null)
                .status(view.getStatus())
                .hostCheckInComplete(view.isHostCheckInComplete())
                .guestCheckInComplete(view.isGuestCheckInComplete())
                .handshakeReady(view.getStatus() == BookingStatus.CHECK_IN_COMPLETE)
                .guestConditionAcknowledged(view.isGuestCheckInComplete())
                .handshakeComplete(view.isHandshakeComplete())
                .checkInOpenedAt(toLocalDateTime(view.getCheckInOpenedAt()))
                .hostCompletedAt(toLocalDateTime(view.getHostCompletedAt()))
                .guestCompletedAt(toLocalDateTime(view.getGuestCompletedAt()))
                .handshakeCompletedAt(toLocalDateTime(view.getHandshakeCompletedAt()))
                .lastUpdated(toLocalDateTime(view.getLastSyncAt()))
                .vehiclePhotos(null) // Photos loaded separately
                .odometerReading(view.getOdometerReading())
                .fuelLevelPercent(view.getFuelLevelPercent())
                .hostCheckInPhotoCount(view.getPhotoCount() != null ? view.getPhotoCount() : 0)
                .lockboxAvailable(view.isLockboxAvailable())
                .geofenceValid(view.getGeofenceDistanceMeters() != null &&
                        view.getGeofenceDistanceMeters() <= geofenceService.getDefaultRadiusMeters())
                .geofenceDistanceMeters(view.getGeofenceDistanceMeters())
                .handoffType(view.getHandshakeMethod() != null ? view.getHandshakeMethod() : "IN_PERSON")
                .tripStartScheduled(view.getScheduledStartTime())
                .noShowDeadline(noShowDeadline)
                .minutesUntilNoShow(minutesUntilNoShow)
                .isHost(isHost)
                .isGuest(!isHost)
                .canHostComplete(view.getStatus() == BookingStatus.CHECK_IN_OPEN)
                .canGuestAcknowledge(view.getStatus() == BookingStatus.CHECK_IN_HOST_COMPLETE)
                .canStartTrip(view.getStatus() == BookingStatus.CHECK_IN_COMPLETE)
                .car(CheckInStatusDTO.CarSummaryDTO.builder()
                        .id(view.getCarId())
                        .brand(view.getCarBrand())
                        .model(view.getCarModel())
                        .year(view.getCarYear())
                        .imageUrl(view.getCarImageUrl())
                        .build())
                .build();
    }

    private boolean isHostHandshakeConfirmed(Booking booking) {
        return eventService.hasEventOfType(booking.getId(), CheckInEventType.HANDSHAKE_HOST_CONFIRMED);
    }

    private boolean isGuestHandshakeConfirmed(Booking booking) {
        return eventService.hasEventOfType(booking.getId(), CheckInEventType.HANDSHAKE_GUEST_CONFIRMED);
    }

    private CheckInStatusDTO mapToStatusDTO(Booking booking, Long userId) {
        boolean isHost = isHost(booking, userId);
        boolean isGuest = isGuest(booking, userId);

        List<CheckInPhotoDTO> photos = null;
        int photoCount = 0;
        // Only show photos to guest after host completes, or always to host
        if (isHost || booking.getStatus().ordinal() >= BookingStatus.CHECK_IN_HOST_COMPLETE.ordinal()) {
            photos = photoRepository.findByBookingId(booking.getId()).stream()
                    .filter(p -> !p.isDeleted())
                    .map(this::mapToPhotoDTO)
                    .collect(Collectors.toList());
            photoCount = photos.size();
        }

        LocalDateTime noShowDeadline = null;
        Long minutesUntilNoShow = null;

        if (booking.getStatus() == BookingStatus.CHECK_IN_OPEN ||
                booking.getStatus() == BookingStatus.CHECK_IN_HOST_COMPLETE) {
            noShowDeadline = booking.getStartTime().plusMinutes(noShowGraceMinutes);
            minutesUntilNoShow = ChronoUnit.MINUTES.between(LocalDateTime.now(SERBIA_ZONE), noShowDeadline);
            if (minutesUntilNoShow < 0) minutesUntilNoShow = 0L;
        }

        // Handshake confirmation flags
        boolean hostConfirmedHandshake = isHostHandshakeConfirmed(booking);
        boolean guestConfirmedHandshake = isGuestHandshakeConfirmed(booking);
        boolean handshakeComplete = booking.getStatus() == BookingStatus.IN_TRIP;

        // Action availability flags (based on current status)
        boolean canHostComplete = booking.getStatus() == BookingStatus.CHECK_IN_OPEN;
        boolean canGuestAcknowledge = booking.getStatus() == BookingStatus.CHECK_IN_HOST_COMPLETE;
        boolean canStartTrip = booking.getStatus() == BookingStatus.CHECK_IN_COMPLETE;

        // Handoff type and geofence status
        String handoffType = booking.getLockboxCodeEncrypted() != null ? "REMOTE" : "IN_PERSON";
        String geofenceStatus = determineGeofenceStatus(booking);

        // Last updated timestamp (most recent of any timestamp)
        LocalDateTime lastUpdated = determineLastUpdated(booking);

        return CheckInStatusDTO.builder()
                .bookingId(booking.getId())
                .checkInSessionId(booking.getCheckInSessionId() != null 
                        ? booking.getCheckInSessionId().toString() : null)
                .status(booking.getStatus())
                .hostCheckInComplete(booking.getHostCheckInCompletedAt() != null)
                .guestCheckInComplete(booking.getGuestCheckInCompletedAt() != null)
                .handshakeReady(booking.getStatus() == BookingStatus.CHECK_IN_COMPLETE)
                // Extended phase flags
                .guestConditionAcknowledged(booking.getGuestCheckInCompletedAt() != null)
                .handshakeComplete(handshakeComplete)
                .hostConfirmedHandshake(hostConfirmedHandshake)
                .guestConfirmedHandshake(guestConfirmedHandshake)
                // Timestamps
                .checkInOpenedAt(toLocalDateTime(booking.getCheckInOpenedAt()))
                .hostCompletedAt(toLocalDateTime(booking.getHostCheckInCompletedAt()))
                .guestCompletedAt(toLocalDateTime(booking.getGuestCheckInCompletedAt()))
                .handshakeCompletedAt(toLocalDateTime(booking.getHandshakeCompletedAt()))
                .lastUpdated(lastUpdated)
                // Host data
                .vehiclePhotos(photos)
                .odometerReading(booking.getStartOdometer())
                .fuelLevelPercent(booking.getStartFuelLevel())
                // Extended host data aliases
                .hostCheckInPhotoCount(photoCount)
                .odometerStart(booking.getStartOdometer())
                .fuelLevelStart(booking.getStartFuelLevel())
                // Remote handoff
                .lockboxAvailable(booking.getLockboxCodeEncrypted() != null)
                .geofenceValid(booking.getGeofenceDistanceMeters() != null &&
                        booking.getGeofenceDistanceMeters() <= geofenceService.getDefaultRadiusMeters())
                .geofenceDistanceMeters(booking.getGeofenceDistanceMeters())
                .handoffType(handoffType)
                .geofenceStatus(geofenceStatus)
                // Deadlines
                .tripStartScheduled(booking.getStartTime())
                .noShowDeadline(noShowDeadline)
                .minutesUntilNoShow(minutesUntilNoShow)
                // Role flags
                .isHost(isHost)
                .isGuest(isGuest)
                // Action availability
                .canHostComplete(canHostComplete)
                .canGuestAcknowledge(canGuestAcknowledge)
                .canStartTrip(canStartTrip)
                // Car info
                .car(CheckInStatusDTO.CarSummaryDTO.builder()
                        .id(booking.getCar().getId())
                        .brand(booking.getCar().getBrand())
                        .model(booking.getCar().getModel())
                        .year(booking.getCar().getYear())
                        .imageUrl(booking.getCar().getImageUrl())
                        .build())
                .build();
    }

    /**
     * Determine geofence validation status string.
     */
    private String determineGeofenceStatus(Booking booking) {
        if (booking.getGeofenceDistanceMeters() == null) {
            return "NOT_CHECKED";
        }
        return booking.getGeofenceDistanceMeters() <= geofenceService.getDefaultRadiusMeters() 
                ? "VALID" : "INVALID";
    }

    /**
     * Determine the most recent update timestamp for ETag generation.
     */
    private LocalDateTime determineLastUpdated(Booking booking) {
        Instant latest = booking.getCheckInOpenedAt();

        if (booking.getHostCheckInCompletedAt() != null && 
                (latest == null || booking.getHostCheckInCompletedAt().isAfter(latest))) {
            latest = booking.getHostCheckInCompletedAt();
        }
        if (booking.getGuestCheckInCompletedAt() != null && 
                (latest == null || booking.getGuestCheckInCompletedAt().isAfter(latest))) {
            latest = booking.getGuestCheckInCompletedAt();
        }
        if (booking.getHandshakeCompletedAt() != null && 
                (latest == null || booking.getHandshakeCompletedAt().isAfter(latest))) {
            latest = booking.getHandshakeCompletedAt();
        }
        if (booking.getTripStartedAt() != null && 
                (latest == null || booking.getTripStartedAt().isAfter(latest))) {
            latest = booking.getTripStartedAt();
        }

        return toLocalDateTime(latest != null ? latest : Instant.now());
    }

    private CheckInPhotoDTO mapToPhotoDTO(CheckInPhoto photo) {
        return CheckInPhotoDTO.builder()
                .photoId(photo.getId())
                .photoType(photo.getPhotoType())
                .url(photo.getStorageKey())
                .uploadedAt(toLocalDateTime(photo.getUploadedAt()))
                .exifValidationStatus(photo.getExifValidationStatus())
                .exifValidationMessage(photo.getExifValidationMessage())
                .width(photo.getImageWidth())
                .height(photo.getImageHeight())
                .mimeType(photo.getMimeType())
                .exifTimestamp(toLocalDateTime(photo.getExifTimestamp()))
                .exifLatitude(photo.getExifLatitude() != null ? photo.getExifLatitude().doubleValue() : null)
                .exifLongitude(photo.getExifLongitude() != null ? photo.getExifLongitude().doubleValue() : null)
                .deviceModel(photo.getExifDeviceModel())
                .build();
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) return null;
        return LocalDateTime.ofInstant(instant, SERBIA_ZONE);
    }

    // ========== WEBSOCKET SUPPORT ==========

    /**
     * Query: Get check-in status for WebSocket broadcast.
     * 
     * <p>This method is used by the sync listener to get fresh status for broadcasting.
     * It bypasses caching to ensure the latest data is sent via WebSocket.
     * 
     * @param bookingId Booking to query
     * @param userId Target user (for role flags)
     * @return Status DTO or null if booking not found
     */
    @Transactional(readOnly = true)
    public CheckInStatusDTO getCheckInStatusForWebSocket(Long bookingId, Long userId) {
        try {
            return bookingRepository.findByIdWithRelations(bookingId)
                    .map(booking -> mapToStatusDTO(booking, userId))
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[CheckIn-Query] Error getting status for WebSocket: {}", e.getMessage());
            return null;
        }
    }

    // ========== RESULT RECORDS ==========

    /**
     * Minimal status for polling endpoints.
     */
    public record CheckInStatusMinimal(
            Long bookingId,
            BookingStatus status,
            boolean hostComplete,
            boolean guestComplete,
            boolean handshakeComplete
    ) {}
}

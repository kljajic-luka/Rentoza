package org.example.rentoza.booking.checkin;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.cqrs.CheckInCommandService;
import org.example.rentoza.booking.dispute.DamageClaim;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.booking.dispute.DamageClaimStatus;
import org.example.rentoza.booking.dispute.DisputeStage;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.payment.BookingPaymentService;
import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Scheduler for check-in workflow lifecycle events.
 * 
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Open check-in windows at T-Xh before trip start (configurable, default: 1h)</li>
 *   <li>Send reminder notifications (only if window > 12h)</li>
 *   <li>Detect and process no-show scenarios at T+30m</li>
 * </ul>
 * 
 * <h2>Critical Configuration</h2>
 * <p>The property {@code app.checkin.window-hours-before-trip} must align with
 * {@code app.checkin.max-early-hours} for consistent UX. If window opens before
 * submission is allowed, users can start uploading photos but cannot complete
 * check-in, leading to frustration.</p>
 * 
 * <h2>Regional Context: Serbia</h2>
 * <p>All cron expressions are evaluated in {@code Europe/Belgrade} timezone.
 * Summer time transitions are handled by the JVM's timezone database.
 * 
 * <h2>Scheduler Frequency</h2>
 * <pre>
 * openCheckInWindows():      Every hour (0 * * * *)  - catches all T-24h transitions
 * sendCheckInReminders():    Every hour (30 * * * *) - T-12h reminders
 * detectNoShows():          Every 10 min (0/10 * * * *) - prompt no-show detection
 * </pre>
 *
 * @see CheckInEventService for audit trail
 * @see CheckInService for workflow orchestration
 */
@Component
@ConditionalOnProperty(
    name = "app.checkin.scheduler.enabled",
    havingValue = "true",
    matchIfMissing = true
)
@Slf4j
public class CheckInScheduler {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");
    
    /**
     * Hours before trip start when check-in window opens.
     * CRITICAL: Must align with app.checkin.max-early-hours for consistent UX.
     * If window opens before submission is allowed, users can start but not finish.
     * TURO STANDARD: 2 hours before trip start.
     */
    @org.springframework.beans.factory.annotation.Value("${app.checkin.window-hours-before-trip:2}")
    private int windowHoursBeforeTrip;
    
    /**
     * Hours after dispute creation before timeout handling kicks in.
     * Default: 24 hours - gives admin time to review before auto-action.
     */
    @org.springframework.beans.factory.annotation.Value("${app.checkin.dispute-timeout-hours:24}")
    private int disputeTimeoutHours;

    @org.springframework.beans.factory.annotation.Value("${app.checkin.no-show-minutes-after-trip-start:120}")
    private int noShowMinutesAfterTripStart;

    @org.springframework.beans.factory.annotation.Value("${app.checkin.handshake-timeout-minutes:45}")
    private int handshakeTimeoutMinutes;

    @org.springframework.beans.factory.annotation.Value("${app.checkin.scheduler.noshow-diagnostics-enabled:false}")
    private boolean noShowDiagnosticsEnabled;

    private final CheckInService checkInService;
    private final CheckInCommandService checkInCommandService;
    private final CheckInEventService eventService;
    private final SchedulerIdempotencyService idempotencyService;
    private final BookingRepository bookingRepository;
    private final DamageClaimRepository damageClaimRepository;
    private final NotificationService notificationService;
    private final BookingPaymentService bookingPaymentService;
    private final Counter windowsOpenedCounter;
    private final Counter noShowHostCounter;
    private final Counter noShowGuestCounter;
    private final Counter schedulerSkippedCounter;
    private final Counter disputeEscalatedCounter;
    private final Counter disputeAutoCancelledCounter;
    private final Counter handshakeTimeoutCounter;

    public CheckInScheduler(
            CheckInService checkInService,
            CheckInCommandService checkInCommandService,
            CheckInEventService eventService,
            SchedulerIdempotencyService idempotencyService,
            BookingRepository bookingRepository,
            DamageClaimRepository damageClaimRepository,
            NotificationService notificationService,
            BookingPaymentService bookingPaymentService,
            MeterRegistry meterRegistry) {
        this.checkInService = checkInService;
        this.checkInCommandService = checkInCommandService;
        this.eventService = eventService;
        this.idempotencyService = idempotencyService;
        this.bookingRepository = bookingRepository;
        this.damageClaimRepository = damageClaimRepository;
        this.notificationService = notificationService;
        this.bookingPaymentService = bookingPaymentService;
        
        this.windowsOpenedCounter = Counter.builder("checkin.window.opened")
                .description("Number of check-in windows opened")
                .tag("trigger", "scheduler")
                .register(meterRegistry);
        
        this.noShowHostCounter = Counter.builder("checkin.noshow")
                .description("No-show events")
                .tag("party", "host")
                .register(meterRegistry);
        
        this.noShowGuestCounter = Counter.builder("checkin.noshow")
                .description("No-show events")
                .tag("party", "guest")
                .register(meterRegistry);
        
        this.schedulerSkippedCounter = Counter.builder("scheduler.skipped.duplicate")
                .description("Scheduler executions skipped due to idempotency")
                .tag("scheduler", "checkin")
                .register(meterRegistry);
        
        this.disputeEscalatedCounter = Counter.builder("checkin.dispute.escalated")
                .description("Check-in disputes escalated to senior admin due to timeout")
                .register(meterRegistry);
        
        this.disputeAutoCancelledCounter = Counter.builder("checkin.dispute.autocancelled")
                .description("Check-in disputes auto-cancelled due to timeout")
                .register(meterRegistry);

        this.handshakeTimeoutCounter = Counter.builder("checkin.handshake.timeout")
            .description("Check-in sessions auto-cancelled due to stale handshake")
            .register(meterRegistry);
    }

    /**
     * Open check-in windows for bookings starting within the next 24 hours.
     * 
     * <p><b>Cron:</b> Every hour at minute 0
     * <p><b>Timezone:</b> Europe/Belgrade
     * 
     * <p>Transitions ACTIVE → CHECK_IN_OPEN for eligible bookings and:
     * <ul>
     *   <li>Generates unique checkInSessionId (UUID)</li>
     *   <li>Records CHECK_IN_OPENED event in audit trail</li>
     *   <li>Sends notification to host</li>
     * </ul>
     */
    @Scheduled(cron = "${app.checkin.scheduler.window-cron:0 0 * * * *}", zone = "Europe/Belgrade")
    @Transactional
    public void openCheckInWindows() {
        LocalDateTime now = LocalDateTime.now(SERBIA_ZONE);
        // Use both hour AND minute bucket (0, 15, 30, 45) for 15-minute cron compatibility
        int minuteBucket = (now.getMinute() / 15) * 15;
        String taskId = String.format("checkin-window-%s-%02d-%02d", 
            now.toLocalDate(), now.getHour(), minuteBucket);
        
        // Idempotency guard - prevent duplicate execution within 14 minutes
        // (shorter than 15-min cron interval to allow next scheduled run)
        if (!idempotencyService.tryAcquireLock(taskId, Duration.ofMinutes(14))) {
            log.debug("[CheckIn] Skipping duplicate check-in window opening: {}", taskId);
            schedulerSkippedCounter.increment();
            return;
        }
        
        log.info("[CheckIn] Starting scheduled check-in window opening");
        
        try {
            // Find bookings starting within configured window (default: 1 hour before trip)
            // Uses exact timestamps for precise timing detection
            // +15 min catch-up buffer matches cron interval so bookings are never missed
            // if the scheduler fires slightly late, without opening windows hours early
            LocalDateTime windowStart = now; // Open window now
            LocalDateTime windowEnd = now.plusHours(windowHoursBeforeTrip).plusMinutes(15); // window + cron catch-up
            
            // DIAGNOSTIC LOGGING: Help trace PostgreSQL timestamp issues
            log.info("[CheckIn] Query parameters: timezone={}, windowStart={}, windowEnd={}, hoursBeforeTrip={}",
                SERBIA_ZONE, windowStart, windowEnd, windowHoursBeforeTrip);
            
            List<Booking> eligibleBookings = checkInService.findBookingsForCheckInWindowOpening(windowStart, windowEnd);
            
            // DIAGNOSTIC LOGGING: Show what was found
            log.info("[CheckIn] Found {} eligible bookings for check-in window opening", eligibleBookings.size());
            
            if (eligibleBookings.isEmpty()) {
                // Additional diagnostics when nothing found
                log.debug("[CheckIn] No eligible bookings in range [{} to {}]. " +
                    "Criteria: status=ACTIVE, checkInSessionId=null, startTime in range",
                    windowStart, windowEnd);
            }
            
            int opened = 0;
            for (Booking booking : eligibleBookings) {
                try {
                    // Skip if already has a check-in session
                    if (booking.getCheckInSessionId() != null) {
                        log.debug("[CheckIn] Booking {} already has check-in session, skipping", booking.getId());
                        continue;
                    }
                    
                    // Transition to CHECK_IN_OPEN
                    String sessionId = UUID.randomUUID().toString();
                    booking.setCheckInSessionId(sessionId);
                    booking.setCheckInOpenedAt(Instant.now());
                    booking.setStatus(BookingStatus.CHECK_IN_OPEN);
                    
                    // Record event
                    eventService.recordSystemEvent(
                        booking,
                        sessionId,
                        CheckInEventType.CHECK_IN_OPENED,
                        Map.of(
                            "triggeredBy", "SCHEDULER",
                            "bookingStartTime", booking.getStartTime().toString()
                        )
                    );
                    
                    // Send notification to host
                    checkInService.notifyCheckInWindowOpened(booking);

                    // P0-3: Authorize security deposit at check-in window opening.
                    // Uses a REQUIRES_NEW transaction so a deposit auth failure never
                    // rolls back the check-in window transition committed above.
                    try {
                        org.example.rentoza.payment.PaymentProvider.PaymentResult depositResult =
                                bookingPaymentService.authorizeDepositAtCheckIn(
                                        booking.getId(), booking.getStoredPaymentMethodId());
                        if (depositResult.isSuccess()) {
                            log.info("[CheckIn] Deposit authorized for booking {} at check-in window opening: {}",
                                    booking.getId(), depositResult.getAuthorizationId());
                        } else {
                            log.warn("[CheckIn] Deposit auth failed for booking {} — handoff will remain blocked until authorization succeeds: {} ({})",
                                    booking.getId(), depositResult.getErrorMessage(), depositResult.getErrorCode());
                        }
                    } catch (Exception depositEx) {
                        log.error("[CheckIn] Unexpected error during deposit auth for booking {} — handoff remains blocked until resolved: {}",
                                booking.getId(), depositEx.getMessage(), depositEx);
                    }

                    opened++;
                    log.info("[CheckIn] Opened check-in window for booking {} (session: {}, startTime: {})", 
                        booking.getId(), sessionId, booking.getStartTime());
                    
                } catch (Exception e) {
                    log.error("[CheckIn] Failed to open check-in window for booking {}: {}", 
                        booking.getId(), e.getMessage(), e);
                }
            }
            
            if (opened > 0) {
                windowsOpenedCounter.increment(opened);
                log.info("[CheckIn] Opened {} check-in windows", opened);
            } else {
                log.debug("[CheckIn] No eligible bookings for check-in window opening");
            }
            
        } catch (Exception e) {
            log.error("[CheckIn] Error during check-in window opening", e);
        }
    }

    /**
     * Send reminder notifications for check-in windows opened but not completed.
     * 
     * <p><b>Cron:</b> Every hour at minute 30
     * <p><b>Target:</b> CHECK_IN_OPEN status with no host completion after 12+ hours
     */
    @Scheduled(cron = "${app.checkin.scheduler.reminder-cron:0 30 * * * *}", zone = "Europe/Belgrade")
    @Transactional
    public void sendCheckInReminders() {
        LocalDate today = LocalDate.now(SERBIA_ZONE);
        int currentHour = LocalTime.now(SERBIA_ZONE).getHour();
        String taskId = "checkin-reminder-" + today + "-" + currentHour;
        
        // Idempotency guard - prevent duplicate execution within 55 minutes
        if (!idempotencyService.tryAcquireLock(taskId, Duration.ofMinutes(55))) {
            log.debug("[CheckIn] Skipping duplicate check-in reminder: {}", taskId);
            schedulerSkippedCounter.increment();
            return;
        }
        
        // Skip reminders if window is too short - no time for separate reminder
        if (windowHoursBeforeTrip <= 12) {
            log.debug("[CheckIn] Reminder skipped: window ({} hours) too short for separate reminder", 
                windowHoursBeforeTrip);
            return;
        }
        
        log.info("[CheckIn] Starting check-in reminder check");
        
        try {
            // Find bookings in CHECK_IN_OPEN status opened more than 12 hours ago
            Instant reminderThreshold = Instant.now().minusSeconds(12 * 60 * 60); // 12 hours
            
            List<Booking> needReminder = checkInService.findBookingsNeedingReminder(
                BookingStatus.CHECK_IN_OPEN, 
                reminderThreshold
            );
            
            int sent = 0;
            for (Booking booking : needReminder) {
                try {
                    // Check if reminder was already sent (via event log)
                    if (eventService.hasEventOfType(booking.getId(), CheckInEventType.CHECK_IN_REMINDER_SENT)) {
                        continue;
                    }
                    
                    // Send reminder and record event
                    checkInService.sendCheckInReminder(booking, "HOST");
                    
                    eventService.recordSystemEvent(
                        booking,
                        booking.getCheckInSessionId(),
                        CheckInEventType.CHECK_IN_REMINDER_SENT,
                        Map.of("channel", "PUSH", "recipient", "HOST")
                    );
                    
                    sent++;
                    
                } catch (Exception e) {
                    log.error("[CheckIn] Failed to send reminder for booking {}: {}", 
                        booking.getId(), e.getMessage());
                }
            }
            
            if (sent > 0) {
                log.info("[CheckIn] Sent {} check-in reminders", sent);
            }
            
        } catch (Exception e) {
            log.error("[CheckIn] Error during check-in reminder sending", e);
        }
    }

    /**
     * Detect and process no-show scenarios.
     * 
     * <p><b>Cron:</b> Every 10 minutes
     * 
     * <h3>No-Show Conditions</h3>
     * <ul>
     *   <li><b>Host No-Show:</b> CHECK_IN_OPEN status, T+30m past trip start, no host action</li>
     *   <li><b>Guest No-Show:</b> CHECK_IN_HOST_COMPLETE status, T+30m past host completion</li>
     * </ul>
     */
    @Scheduled(cron = "${app.checkin.scheduler.noshow-cron:0 0/10 * * * *}", zone = "Europe/Belgrade")
    @Transactional
    public void detectNoShows() {
        // For 10-minute intervals, use minute block as task ID component
        int minuteBlock = LocalTime.now(SERBIA_ZONE).getMinute() / 10;
        String taskId = "checkin-noshow-" + LocalDate.now(SERBIA_ZONE) + "-" + 
                        LocalTime.now(SERBIA_ZONE).getHour() + "-" + minuteBlock;
        
        // Idempotency guard - prevent duplicate execution within 8 minutes
        if (!idempotencyService.tryAcquireLock(taskId, Duration.ofMinutes(8))) {
            log.debug("[CheckIn] Skipping duplicate no-show detection: {}", taskId);
            schedulerSkippedCounter.increment();
            return;
        }
        
        log.info("[CheckIn] Starting no-show detection");
        
        try {
            LocalDateTime now = LocalDateTime.now(SERBIA_ZONE);
            LocalDateTime noShowThreshold = now.minusMinutes(noShowMinutesAfterTripStart);
            
            // Detect Host No-Shows
            // CHECK_IN_OPEN bookings where trip start + 30min has passed
            List<Booking> hostNoShows = checkInService.findPotentialHostNoShows(
                BookingStatus.CHECK_IN_OPEN, 
                noShowThreshold
            );
            
            int hostNoShowCount = 0;
            for (Booking booking : hostNoShows) {
                try {
                    checkInCommandService.processNoShow(booking, "HOST");
                    hostNoShowCount++;
                    log.warn("[CheckIn] Host no-show detected for booking {}", booking.getId());
                } catch (Exception e) {
                    log.error("[CheckIn] Failed to process host no-show for booking {}: {}", 
                        booking.getId(), e.getMessage());
                }
            }
            
            // Detect Guest No-Shows
            // CHECK_IN_HOST_COMPLETE bookings where host completed + 30min has passed
            List<Booking> guestNoShows = checkInService.findPotentialGuestNoShows(
                BookingStatus.CHECK_IN_HOST_COMPLETE, 
                noShowThreshold
            );

            if (noShowDiagnosticsEnabled) {
                logNoShowDiagnostics(now, noShowThreshold, hostNoShows, guestNoShows);
            }
            
            int guestNoShowCount = 0;
            for (Booking booking : guestNoShows) {
                try {
                    checkInCommandService.processNoShow(booking, "GUEST");
                    guestNoShowCount++;
                    log.warn("[CheckIn] Guest no-show detected for booking {}", booking.getId());
                } catch (Exception e) {
                    log.error("[CheckIn] Failed to process guest no-show for booking {}: {}", 
                        booking.getId(), e.getMessage());
                }
            }
            
            if (hostNoShowCount > 0) {
                noShowHostCounter.increment(hostNoShowCount);
            }
            if (guestNoShowCount > 0) {
                noShowGuestCounter.increment(guestNoShowCount);
            }
            
            if (hostNoShowCount > 0 || guestNoShowCount > 0) {
                log.info("[CheckIn] Processed {} host no-shows, {} guest no-shows", 
                    hostNoShowCount, guestNoShowCount);
            }
            
        } catch (Exception e) {
            log.error("[CheckIn] Error during no-show detection", e);
        }
    }

    private void logNoShowDiagnostics(
            LocalDateTime now,
            LocalDateTime noShowThreshold,
            List<Booking> hostNoShows,
            List<Booking> guestNoShows) {
        log.info(
                "[CheckIn] No-show diagnostics | now={} ({}) | threshold={} | graceMinutes={} | hostCandidates={} {} | guestCandidates={} {}",
                now,
                SERBIA_ZONE,
                noShowThreshold,
                noShowMinutesAfterTripStart,
                hostNoShows.size(),
                hostNoShows.isEmpty() ? "" : hostNoShows.stream().map(Booking::getId).toList(),
                guestNoShows.size(),
                guestNoShows.isEmpty() ? "" : guestNoShows.stream().map(Booking::getId).toList()
        );

        if (!hostNoShows.isEmpty() || !guestNoShows.isEmpty()) {
            return;
        }

        LocalDateTime nextHostEligibleAt = null;
        Long nextHostBookingId = null;
        LocalDateTime nextGuestEligibleAt = null;
        Long nextGuestBookingId = null;

        for (Booking booking : bookingRepository.findBookingsInCheckInPhase()) {
            if (booking.getStatus() == BookingStatus.CHECK_IN_OPEN &&
                    booking.getHostCheckInCompletedAt() == null &&
                    booking.getStartTime() != null) {
                LocalDateTime eligibleAt = booking.getStartTime().plusMinutes(noShowMinutesAfterTripStart);
                if (eligibleAt.isAfter(now) &&
                        (nextHostEligibleAt == null || eligibleAt.isBefore(nextHostEligibleAt))) {
                    nextHostEligibleAt = eligibleAt;
                    nextHostBookingId = booking.getId();
                }
            }

            if (booking.getStatus() == BookingStatus.CHECK_IN_HOST_COMPLETE &&
                    booking.getGuestCheckInCompletedAt() == null &&
                    booking.getHostCheckInCompletedAt() != null &&
                    booking.getStartTime() != null) {
                LocalDateTime hostCompletedLocal = LocalDateTime.ofInstant(
                        booking.getHostCheckInCompletedAt(),
                        SERBIA_ZONE
                );
                LocalDateTime tripStartEligibleAt = booking.getStartTime().plusMinutes(noShowMinutesAfterTripStart);
                LocalDateTime hostCompletedEligibleAt = hostCompletedLocal.plusMinutes(noShowMinutesAfterTripStart);
                LocalDateTime eligibleAt = tripStartEligibleAt.isAfter(hostCompletedEligibleAt)
                        ? tripStartEligibleAt
                        : hostCompletedEligibleAt;

                if (eligibleAt.isAfter(now) &&
                        (nextGuestEligibleAt == null || eligibleAt.isBefore(nextGuestEligibleAt))) {
                    nextGuestEligibleAt = eligibleAt;
                    nextGuestBookingId = booking.getId();
                }
            }
        }

        if (nextHostEligibleAt != null) {
            log.info(
                    "[CheckIn] Next HOST no-show eligibility: booking={} eligibleAt={} (in {} minutes)",
                    nextHostBookingId,
                    nextHostEligibleAt,
                    ChronoUnit.MINUTES.between(now, nextHostEligibleAt)
            );
        } else {
            log.info("[CheckIn] No upcoming HOST no-show eligibility found");
        }

        if (nextGuestEligibleAt != null) {
            log.info(
                    "[CheckIn] Next GUEST no-show eligibility: booking={} eligibleAt={} (in {} minutes)",
                    nextGuestBookingId,
                    nextGuestEligibleAt,
                    ChronoUnit.MINUTES.between(now, nextGuestEligibleAt)
            );
        } else {
            log.info("[CheckIn] No upcoming GUEST no-show eligibility found");
        }
    }

    /**
     * Detect stale handshakes where both parties completed check-in but no trip start happened.
     */
    @Scheduled(cron = "${app.checkin.scheduler.handshake-timeout-cron:0 5/10 * * * *}", zone = "Europe/Belgrade")
    @Transactional
    public void detectStaleHandshakes() {
        int minuteBlock = LocalTime.now(SERBIA_ZONE).getMinute() / 10;
        String taskId = "checkin-handshake-timeout-" + LocalDate.now(SERBIA_ZONE) + "-" +
                LocalTime.now(SERBIA_ZONE).getHour() + "-" + minuteBlock;

        if (!idempotencyService.tryAcquireLock(taskId, Duration.ofMinutes(8))) {
            log.debug("[CheckIn] Skipping duplicate stale handshake detection: {}", taskId);
            schedulerSkippedCounter.increment();
            return;
        }

        try {
            LocalDateTime threshold = LocalDateTime.now(SERBIA_ZONE).minusMinutes(handshakeTimeoutMinutes);
            List<Booking> staleHandshakes = bookingRepository.findStaleCheckInHandshakes(
                    BookingStatus.CHECK_IN_COMPLETE,
                    threshold
            );

            int processed = 0;
            for (Booking booking : staleHandshakes) {
                try {
                    cancelStaleHandshakeBooking(booking);
                    processed++;
                } catch (Exception ex) {
                    log.error("[CheckIn] Failed stale handshake cancellation for booking {}: {}",
                            booking.getId(), ex.getMessage(), ex);
                }
            }

            if (processed > 0) {
                handshakeTimeoutCounter.increment(processed);
                log.warn("[CheckIn] Processed {} stale handshakes (auto-cancelled)", processed);
            }
        } catch (Exception ex) {
            log.error("[CheckIn] Error during stale handshake detection", ex);
        }
    }

    /**
     * Handle stale check-in disputes that have not been resolved within the timeout period.
     * VAL-004 Phase 6: Timeout handling for disputes.
     * 
     * <p><b>Cron:</b> Every 30 minutes
     * <p><b>Timezone:</b> Europe/Belgrade
     * 
     * <p>For disputes older than {@code app.checkin.dispute-timeout-hours} (default: 24h):
     * <ul>
     *   <li>If trip start is more than 24h away: Escalate to senior admin</li>
     *   <li>If trip start is within 24h or has passed: Auto-cancel with full refund</li>
     * </ul>
     */
    @Scheduled(cron = "${app.checkin.scheduler.dispute-timeout-cron:0 0/30 * * * *}", zone = "Europe/Belgrade")
    @Transactional
    public void handleStaleCheckInDisputes() {
        LocalDateTime now = LocalDateTime.now(SERBIA_ZONE);
        int minuteBucket = (now.getMinute() / 30) * 30;
        String taskId = String.format("checkin-dispute-timeout-%s-%02d-%02d", 
            now.toLocalDate(), now.getHour(), minuteBucket);
        
        // Idempotency guard - prevent duplicate execution within 25 minutes
        if (!idempotencyService.tryAcquireLock(taskId, Duration.ofMinutes(25))) {
            log.debug("[CheckIn] Skipping duplicate stale dispute handling: {}", taskId);
            schedulerSkippedCounter.increment();
            return;
        }
        
        log.info("[CheckIn] Starting stale dispute detection (timeout: {}h)", disputeTimeoutHours);
        
        try {
            // Calculate threshold: disputes older than this are considered stale
            Instant threshold = Instant.now().minus(Duration.ofHours(disputeTimeoutHours));
            
            // Find bookings with CHECK_IN_DISPUTE status where dispute was created > threshold
            List<Booking> staleDisputes = bookingRepository.findStaleCheckInDisputes(
                BookingStatus.CHECK_IN_DISPUTE, 
                threshold
            );
            
            int escalatedCount = 0;
            int autoCancelledCount = 0;
            
            for (Booking booking : staleDisputes) {
                try {
                    boolean autoCancelled = handleStaleDispute(booking, now);
                    if (autoCancelled) {
                        autoCancelledCount++;
                    } else {
                        escalatedCount++;
                    }
                } catch (Exception e) {
                    log.error("[CheckIn] Failed to handle stale dispute for booking {}: {}", 
                        booking.getId(), e.getMessage(), e);
                }
            }
            
            if (escalatedCount > 0) {
                disputeEscalatedCounter.increment(escalatedCount);
            }
            if (autoCancelledCount > 0) {
                disputeAutoCancelledCounter.increment(autoCancelledCount);
            }
            
            if (escalatedCount > 0 || autoCancelledCount > 0) {
                log.info("[CheckIn] Stale dispute handling complete: {} escalated, {} auto-cancelled", 
                    escalatedCount, autoCancelledCount);
            }
            
        } catch (Exception e) {
            log.error("[CheckIn] Error during stale dispute handling", e);
        }
    }

    /**
     * Handle a single stale dispute based on trip timing.
     * 
     * @param booking The booking with a stale dispute
     * @param now Current timestamp
     * @return true if auto-cancelled, false if escalated
     */
    private boolean handleStaleDispute(Booking booking, LocalDateTime now) {
        // Find the associated dispute claim
        Optional<DamageClaim> claimOpt = damageClaimRepository.findByBookingAndDisputeStage(
            booking, DisputeStage.CHECK_IN
        );
        
        if (claimOpt.isEmpty()) {
            log.warn("[CheckIn] No CHECK_IN dispute found for booking {} with status CHECK_IN_DISPUTE", 
                booking.getId());
            return false;
        }
        
        DamageClaim claim = claimOpt.get();
        
        // Skip if already escalated
        if (Boolean.TRUE.equals(claim.getEscalated())) {
            log.debug("[CheckIn] Dispute {} already escalated, skipping", claim.getId());
            return false;
        }
        
        // Calculate hours until trip start
        LocalDateTime tripStart = booking.getStartTime();
        long hoursUntilTrip = Duration.between(now, tripStart).toHours();
        
        if (hoursUntilTrip <= 24 || tripStart.isBefore(now)) {
            // Trip is imminent or has passed - auto-cancel with refund
            return autoResolveDisputeWithCancellation(booking, claim);
        } else {
            // Trip is still far away - escalate to senior admin
            escalateDisputeToSeniorAdmin(booking, claim);
            return false;
        }
    }

    /**
     * Auto-resolve a stale dispute by cancelling the booking and issuing a full refund.
     * 
     * @param booking The booking to cancel
     * @param claim The dispute claim
     * @return true if successfully auto-cancelled
     */
    private boolean autoResolveDisputeWithCancellation(Booking booking, DamageClaim claim) {
        log.warn("[CheckIn] AUTO-CANCELLING booking {} due to unresolved dispute {} (trip imminent/passed)", 
            booking.getId(), claim.getId());
        
        try {
            // Update dispute claim status
            claim.setStatus(DamageClaimStatus.CHECK_IN_RESOLVED_CANCEL);
            claim.setResolvedAt(Instant.now());
            claim.setResolutionNotes("Auto-cancelled due to timeout. Dispute not resolved within " + 
                disputeTimeoutHours + "h and trip start imminent or passed.");
            damageClaimRepository.save(claim);
            
            // Process full refund and release deposit
            try {
                bookingPaymentService.processFullRefund(booking.getId(), 
                        "Auto-cancelled: check-in dispute not resolved within " + disputeTimeoutHours + "h");
                log.info("[CheckIn] Full refund processed for booking {}", booking.getId());
            } catch (Exception refundEx) {
                log.error("[CheckIn] Failed to process refund for booking {}: {} - manual intervention required",
                        booking.getId(), refundEx.getMessage());
            }
            
            // Release deposit hold if exists
            try {
                String depositAuthId = booking.getDepositAuthorizationId();
                if (depositAuthId != null && !depositAuthId.isBlank()) {
                    bookingPaymentService.releaseDeposit(booking.getId(), depositAuthId);
                    log.info("[CheckIn] Deposit released for booking {}", booking.getId());
                }
            } catch (Exception depositEx) {
                log.error("[CheckIn] Failed to release deposit for booking {}: {} - manual intervention required",
                        booking.getId(), depositEx.getMessage());
            }
            
            // Update booking status (cancellation reason tracked in claim.resolutionNotes)
            booking.setStatus(BookingStatus.CANCELLED);
            booking.setCancelledAt(LocalDateTime.now(SERBIA_ZONE));
            bookingRepository.save(booking);
            
            // Record event
            eventService.recordSystemEvent(booking, booking.getCheckInSessionId(),
                CheckInEventType.DISPUTE_TIMEOUT_AUTO_CANCEL,
                Map.of(
                    "claimId", claim.getId(),
                    "disputeType", claim.getDisputeType() != null ? claim.getDisputeType().name() : "UNKNOWN",
                    "timeoutHours", disputeTimeoutHours,
                    "reason", "Booking auto-cancelled due to unresolved check-in dispute timeout"
                ));
            
            // Send notifications
            notificationService.notifyCheckInDisputeAutoCancelled(booking, claim);
            notificationService.alertAdminDisputeAutoCancelled(booking, claim);
            
            log.info("[CheckIn] Successfully auto-cancelled booking {} with full refund", booking.getId());
            return true;
            
        } catch (Exception e) {
            log.error("[CheckIn] Failed to auto-cancel booking {} for dispute {}: {}", 
                booking.getId(), claim.getId(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Escalate a stale dispute to senior admin for priority resolution.
     * 
     * @param booking The booking with the stale dispute
     * @param claim The dispute claim to escalate
     */
    private void escalateDisputeToSeniorAdmin(Booking booking, DamageClaim claim) {
        log.warn("[CheckIn] ESCALATING dispute {} for booking {} to senior admin (trip >24h away)", 
            claim.getId(), booking.getId());
        
        try {
            // Mark claim as escalated
            claim.setEscalated(true);
            claim.setEscalatedAt(Instant.now());
            damageClaimRepository.save(claim);
            
            // Record event
            eventService.recordSystemEvent(booking, booking.getCheckInSessionId(),
                CheckInEventType.DISPUTE_ESCALATED,
                Map.of(
                    "claimId", claim.getId(),
                    "disputeType", claim.getDisputeType() != null ? claim.getDisputeType().name() : "UNKNOWN",
                    "timeoutHours", disputeTimeoutHours,
                    "reason", "Check-in dispute escalated to senior admin due to timeout"
                ));
            
            // Send escalation notifications
            notificationService.escalateCheckInDisputeToSeniorAdmin(booking, claim);
            
            log.info("[CheckIn] Dispute {} escalated successfully", claim.getId());
            
        } catch (Exception e) {
            log.error("[CheckIn] Failed to escalate dispute {} for booking {}: {}", 
                claim.getId(), booking.getId(), e.getMessage(), e);
            throw e;
        }
    }

    private void cancelStaleHandshakeBooking(Booking booking) {
        // F-SM-2: Guard against race with concurrent handshake confirmation.
        // Between the scheduler query and this processing, confirmHandshake() could
        // have advanced the booking to IN_TRIP under pessimistic lock.
        if (booking.getStatus() != BookingStatus.CHECK_IN_COMPLETE) {
            log.warn("[CheckIn] Skipping stale handshake cancellation for booking {} — status is {} " +
                     "(expected CHECK_IN_COMPLETE). Likely race with concurrent handshake confirmation.",
                     booking.getId(), booking.getStatus());
            return;
        }

        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(LocalDateTime.now(SERBIA_ZONE));
        bookingRepository.save(booking);

        boolean refundSuccess = checkInService.processHostNoShowRefund(booking);

        eventService.recordSystemEvent(
            booking,
            booking.getCheckInSessionId(),
            refundSuccess ? CheckInEventType.NO_SHOW_REFUND_PROCESSED : CheckInEventType.NO_SHOW_REFUND_FAILED,
            Map.of(
                "party", "HANDSHAKE_TIMEOUT",
                "refundMode", booking.getPaymentVerificationRef() == null ? "MOCK" : "PAYMENT_PROVIDER"
            )
        );

        eventService.recordSystemEvent(
                booking,
                booking.getCheckInSessionId(),
                CheckInEventType.HANDSHAKE_TIMEOUT_AUTO_CANCELLED,
                Map.of(
                        "timeoutMinutes", handshakeTimeoutMinutes,
                        "reason", "HANDSHAKE_NOT_CONFIRMED"
                )
        );

        if (booking.getRenter() != null) {
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(booking.getRenter().getId())
                    .type(NotificationType.BOOKING_CANCELLED)
                    .message("Rezervacija je automatski otkazana jer primopredaja nije potvrđena na vreme.")
                    .relatedEntityId(String.valueOf(booking.getId()))
                    .build());
        }

        if (booking.getCar() != null && booking.getCar().getOwner() != null) {
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(booking.getCar().getOwner().getId())
                    .type(NotificationType.BOOKING_CANCELLED)
                    .message("Rezervacija je automatski otkazana jer primopredaja nije potvrđena na vreme.")
                    .relatedEntityId(String.valueOf(booking.getId()))
                    .build());
        }

        notificationService.alertAdminNoShow(booking, "HANDSHAKE_TIMEOUT", refundSuccess);
    }
}

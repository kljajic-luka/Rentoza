package org.example.rentoza.booking.checkin;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingStatus;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scheduler for check-in workflow lifecycle events.
 * 
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Open check-in windows at T-24h before trip start</li>
 *   <li>Send reminder notifications at T-12h</li>
 *   <li>Detect and process no-show scenarios at T+30m</li>
 * </ul>
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

    private final CheckInService checkInService;
    private final CheckInEventService eventService;
    private final SchedulerIdempotencyService idempotencyService;
    private final Counter windowsOpenedCounter;
    private final Counter noShowHostCounter;
    private final Counter noShowGuestCounter;
    private final Counter schedulerSkippedCounter;

    public CheckInScheduler(
            CheckInService checkInService,
            CheckInEventService eventService,
            SchedulerIdempotencyService idempotencyService,
            MeterRegistry meterRegistry) {
        this.checkInService = checkInService;
        this.eventService = eventService;
        this.idempotencyService = idempotencyService;
        
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
        LocalDate today = LocalDate.now(SERBIA_ZONE);
        int currentHour = LocalTime.now(SERBIA_ZONE).getHour();
        String taskId = "checkin-window-" + today + "-" + currentHour;
        
        // Idempotency guard - prevent duplicate execution within 55 minutes
        if (!idempotencyService.tryAcquireLock(taskId, Duration.ofMinutes(55))) {
            log.debug("[CheckIn] Skipping duplicate check-in window opening: {}", taskId);
            schedulerSkippedCounter.increment();
            return;
        }
        
        log.info("[CheckIn] Starting scheduled check-in window opening");
        
        try {
            // Find bookings starting within next 24-26 hours (buffer for hourly run)
            // Uses exact timestamps for precise T-24h detection
            LocalDateTime now = LocalDateTime.now(SERBIA_ZONE);
            LocalDateTime windowStart = now; // Open window now
            LocalDateTime windowEnd = now.plusHours(26); // Buffer for hourly cron
            
            List<Booking> eligibleBookings = checkInService.findBookingsForCheckInWindowOpening(windowStart, windowEnd);
            
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
            LocalDateTime noShowThreshold = now.minusMinutes(30);
            
            // Detect Host No-Shows
            // CHECK_IN_OPEN bookings where trip start + 30min has passed
            List<Booking> hostNoShows = checkInService.findPotentialHostNoShows(
                BookingStatus.CHECK_IN_OPEN, 
                noShowThreshold
            );
            
            int hostNoShowCount = 0;
            for (Booking booking : hostNoShows) {
                try {
                    checkInService.processNoShow(booking, "HOST");
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
            
            int guestNoShowCount = 0;
            for (Booking booking : guestNoShows) {
                try {
                    checkInService.processNoShow(booking, "GUEST");
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
}

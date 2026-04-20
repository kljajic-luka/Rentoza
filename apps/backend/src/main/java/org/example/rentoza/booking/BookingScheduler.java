package org.example.rentoza.booking;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Scheduled task to automatically expire pending booking approval requests
 * that have exceeded their decision deadline.
 * 
 * Runs every 6 hours by default. Can be configured via:
 * - app.booking.scheduler.expiry-cron (default: "0 0 *6 * * *")
 * - app.booking.scheduler.enabled (default: true)
 * 
 * When a booking expires:
 * - Status changes from PENDING_APPROVAL to EXPIRED
 * - Guest receives notification
 * - Payment hold is released (simulated)
 * - Booking is no longer visible in owner's pending queue
        */
@Component
@ConditionalOnProperty(
    name = "app.booking.scheduler.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class BookingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BookingScheduler.class);

    private static final String LOCK_EXPIRY = "booking.scheduler.auto-expiry";
    private static final String LOCK_REMINDER = "booking.scheduler.approval-reminder";

    private final BookingApprovalService approvalService;
    private final SchedulerIdempotencyService lockService;
    private final Counter expiredBookingsCounter;
    private final Counter approvalRemindersCounter;

    public BookingScheduler(BookingApprovalService approvalService,
                            SchedulerIdempotencyService lockService,
                            MeterRegistry meterRegistry) {
        this.approvalService = approvalService;
        this.lockService = lockService;
        this.expiredBookingsCounter = Counter.builder("booking.expired.count")
                .description("Number of booking approval requests that expired due to timeout")
                .tag("type", "auto_expiry")
                .register(meterRegistry);
        this.approvalRemindersCounter = Counter.builder("booking.approval.reminder.count")
            .description("Number of pre-expiry reminders sent for pending booking approvals")
            .register(meterRegistry);
    }

    /**
     * Auto-expire pending bookings that have exceeded their decision deadline.
     * 
     * Cron Expression: "0 0/15 * * * *"
     * - Second: 0
     * - Minute: Every 15 minutes starting at 0 (0, 15, 30, 45)
     * - Hour: every hour
     * - Day of month: every day
     * - Month: every month
     * - Day of week: any
     * 
     * Execution times: Every 15 minutes (00:00, 00:15, 00:30, 00:45, etc.)
     * 
     * Rationale: For "Short Notice" bookings where trip starts within hours,
     * the previous 6-hour schedule was too slow to catch expiries promptly.
     * 15-minute frequency provides acceptable latency (~7.5 min average).
     * 
     * Can be overridden via application.properties:
     * app.booking.scheduler.expiry-cron=0 0 0/6 * * *  (revert to 6h for low-volume deployments)
     */
    @Scheduled(cron = "${app.booking.scheduler.expiry-cron:0 0/15 * * * *}", zone = "Europe/Belgrade")
    public void autoExpirePendingBookings() {
        if (!lockService.tryAcquireLock(LOCK_EXPIRY, Duration.ofMinutes(14))) {
            log.debug("[BookingScheduler] Skipping auto-expiry — lock held by another instance");
            return;
        }
        log.info("Starting scheduled auto-expiry of pending booking approval requests");

        try {
            int expiredCount = approvalService.autoExpirePendingBookings();

            if (expiredCount > 0) {
                log.warn("Auto-expired {} pending booking requests due to decision deadline timeout", expiredCount);
                expiredBookingsCounter.increment(expiredCount);
            } else {
                log.debug("No pending bookings to expire");
            }

        } catch (Exception e) {
            log.error("Error during auto-expiry of pending bookings", e);
        } finally {
            lockService.releaseLock(LOCK_EXPIRY);
        }
    }

    /**
     * Send pre-expiry reminders for pending approval requests.
     * Default cadence is every 30 minutes; reminders are idempotent per threshold.
     */
    @Scheduled(cron = "${app.booking.scheduler.reminder-cron:0 0/30 * * * *}", zone = "Europe/Belgrade")
    public void sendPendingApprovalReminders() {
        if (!lockService.tryAcquireLock(LOCK_REMINDER, Duration.ofMinutes(29))) {
            log.debug("[BookingScheduler] Skipping approval reminder — lock held by another instance");
            return;
        }

        try {
            int reminderCount = approvalService.sendPendingApprovalReminders();
            if (reminderCount > 0) {
                approvalRemindersCounter.increment(reminderCount);
                log.info("Sent {} pending-approval reminder notifications", reminderCount);
            } else {
                log.debug("No pending-approval reminders to send");
            }
        } catch (Exception e) {
            log.error("Error during pending-approval reminder job", e);
        } finally {
            lockService.releaseLock(LOCK_REMINDER);
        }
    }

}

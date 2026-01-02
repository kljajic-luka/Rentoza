package org.example.rentoza.booking;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

    private final BookingApprovalService approvalService;
    private final Counter expiredBookingsCounter;

    public BookingScheduler(BookingApprovalService approvalService, MeterRegistry meterRegistry) {
        this.approvalService = approvalService;
        this.expiredBookingsCounter = Counter.builder("booking.expired.count")
                .description("Number of booking approval requests that expired due to timeout")
                .tag("type", "auto_expiry")
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
        }
    }

}

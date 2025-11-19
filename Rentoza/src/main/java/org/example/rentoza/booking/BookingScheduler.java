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
     * Cron Expression: "0 0 *\/6 * * *"
     * - Second: 0
     * - Minute: 0
     * - Hour: Every 6 hours (0, 6, 12, 18)
     * - Day of month: *
     * - Month: *
     * - Day of week: *
     * 
     * Execution times: 00:00, 06:00, 12:00, 18:00 daily
     * 
     * Can be overridden via application.properties:
     * app.booking.scheduler.expiry-cron=0 0 4 * * *  (run at 4 AM daily)
     */
    @Scheduled(cron = "${app.booking.scheduler.expiry-cron:0 0 */6 * * *}")
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

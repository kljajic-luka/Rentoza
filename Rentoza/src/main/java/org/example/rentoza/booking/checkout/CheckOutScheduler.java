package org.example.rentoza.booking.checkout;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Scheduled tasks for checkout workflow automation.
 * 
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Auto-open checkout windows when trip end date is reached</li>
 *   <li>Send checkout reminders to guests who haven't returned</li>
 *   <li>Escalate overdue returns</li>
 * </ul>
 *
 * <h2>Cron Schedule (Europe/Belgrade)</h2>
 * <ul>
 *   <li>Checkout window opening: Hourly at minute 0</li>
 *   <li>Checkout reminders: Every 4 hours</li>
 *   <li>Overdue escalation: Every 6 hours</li>
 * </ul>
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "app.checkout.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class CheckOutScheduler {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");

    private final CheckOutService checkOutService;
    private final BookingRepository bookingRepository;
    private final SchedulerIdempotencyService idempotencyService;
    
    // Metrics
    private final Counter checkoutWindowOpenedCounter;
    private final Counter checkoutReminderSentCounter;
    private final Counter schedulerSkippedCounter;

    public CheckOutScheduler(
            CheckOutService checkOutService,
            BookingRepository bookingRepository,
            SchedulerIdempotencyService idempotencyService,
            MeterRegistry meterRegistry) {
        this.checkOutService = checkOutService;
        this.bookingRepository = bookingRepository;
        this.idempotencyService = idempotencyService;
        
        this.checkoutWindowOpenedCounter = Counter.builder("checkout.window.opened")
                .description("Checkout windows opened by scheduler")
                .register(meterRegistry);
        
        this.checkoutReminderSentCounter = Counter.builder("checkout.reminder.sent")
                .description("Checkout reminders sent")
                .register(meterRegistry);
        
        this.schedulerSkippedCounter = Counter.builder("scheduler.skipped.duplicate")
                .description("Scheduler executions skipped due to idempotency")
                .tag("scheduler", "checkout")
                .register(meterRegistry);
    }

    /**
     * Open checkout windows for trips ending today.
     * 
     * <p>Runs hourly at minute 0. Finds IN_TRIP bookings with end date
     * <= today and initiates checkout process.
     * 
     * <p>Cron: {@code 0 0 * * * *} (every hour at :00)
     */
    @Scheduled(cron = "${app.checkout.scheduler.window-cron:0 0 * * * *}", zone = "Europe/Belgrade")
    @Transactional
    public void openCheckoutWindows() {
        LocalDate today = LocalDate.now(SERBIA_ZONE);
        String taskId = "checkout-window-" + today;
        
        // Idempotency guard - prevent duplicate execution within 55 minutes
        if (!idempotencyService.tryAcquireLock(taskId, Duration.ofMinutes(55))) {
            log.debug("[CheckOutScheduler] Skipping duplicate checkout window opening for: {}", today);
            schedulerSkippedCounter.increment();
            return;
        }
        
        log.debug("[CheckOutScheduler] Running checkout window opening for date: {}", today);
        
        try {
            List<Booking> eligibleBookings = checkOutService.findBookingsForCheckoutOpening(today);
            
            int opened = 0;
            for (Booking booking : eligibleBookings) {
                try {
                    checkOutService.initiateCheckoutByScheduler(booking);
                    opened++;
                    checkoutWindowOpenedCounter.increment();
                } catch (Exception e) {
                    log.error("[CheckOutScheduler] Failed to open checkout for booking {}: {}",
                        booking.getId(), e.getMessage());
                }
            }
            
            if (opened > 0) {
                log.info("[CheckOutScheduler] Opened {} checkout windows for date {}", opened, today);
            }
        } catch (Exception e) {
            log.error("[CheckOutScheduler] Failed to run checkout window opening", e);
        }
    }

    /**
     * Send checkout reminders to guests who haven't completed checkout.
     * 
     * <p>Runs every 4 hours. Finds bookings in CHECKOUT_OPEN status
     * where checkout was opened more than 2 hours ago.
     * 
     * PERFORMANCE OPTIMIZATION (Phase 1 Critical Fix):
     * Uses optimized database query instead of findAll().stream() pattern.
     * 
     * <p>Cron: {@code 0 0 0/4 * * *} (every 4 hours)
     */
    @Scheduled(cron = "${app.checkout.scheduler.reminder-cron:0 0 0/4 * * *}", zone = "Europe/Belgrade")
    @Transactional(readOnly = true)
    public void sendCheckoutReminders() {
        String taskId = "checkout-reminder-" + LocalDate.now(SERBIA_ZONE) + "-" + 
                        (java.time.LocalTime.now(SERBIA_ZONE).getHour() / 4);
        
        // Idempotency guard - prevent duplicate execution within 3 hours 50 minutes
        if (!idempotencyService.tryAcquireLock(taskId, Duration.ofMinutes(230))) {
            log.debug("[CheckOutScheduler] Skipping duplicate checkout reminder: {}", taskId);
            schedulerSkippedCounter.increment();
            return;
        }
        
        log.debug("[CheckOutScheduler] Running checkout reminder check");
        
        try {
            // Calculate threshold: checkout opened at least 2 hours ago
            java.time.LocalDateTime thresholdTime = java.time.LocalDateTime.now(SERBIA_ZONE).minusHours(2);
            
            // Use optimized query that handles overdue checkouts
            List<Booking> bookingsNeedingReminder = bookingRepository.findOverdueCheckouts(thresholdTime);
            
            for (Booking booking : bookingsNeedingReminder) {
                if (booking.getStatus() == BookingStatus.CHECKOUT_OPEN) {
                    // TODO: Send reminder notification
                    log.info("[CheckOutScheduler] Would send checkout reminder for booking {}", booking.getId());
                    checkoutReminderSentCounter.increment();
                }
            }
        } catch (Exception e) {
            log.error("[CheckOutScheduler] Failed to send checkout reminders", e);
        }
    }

    /**
     * Escalate significantly overdue returns.
     * 
     * <p>Runs every 6 hours. Finds bookings where:
     * <ul>
     *   <li>Checkout was opened more than 24 hours ago</li>
     *   <li>Guest still hasn't completed checkout</li>
     * </ul>
     * 
     * PERFORMANCE OPTIMIZATION (Phase 1 Critical Fix):
     * Uses optimized database query instead of findAll().stream() pattern.
     * 
     * <p>Cron: {@code 0 0 0/6 * * *} (every 6 hours)
     */
    @Scheduled(cron = "${app.checkout.scheduler.escalation-cron:0 0 0/6 * * *}", zone = "Europe/Belgrade")
    @Transactional(readOnly = true)
    public void escalateOverdueReturns() {
        String taskId = "checkout-escalation-" + LocalDate.now(SERBIA_ZONE) + "-" + 
                        (java.time.LocalTime.now(SERBIA_ZONE).getHour() / 6);
        
        // Idempotency guard - prevent duplicate execution within 5 hours 50 minutes
        if (!idempotencyService.tryAcquireLock(taskId, Duration.ofMinutes(350))) {
            log.debug("[CheckOutScheduler] Skipping duplicate escalation check: {}", taskId);
            schedulerSkippedCounter.increment();
            return;
        }
        
        log.debug("[CheckOutScheduler] Running overdue return escalation check");
        
        try {
            // Calculate threshold: checkout opened at least 24 hours ago
            java.time.LocalDateTime thresholdTime = java.time.LocalDateTime.now(SERBIA_ZONE).minusHours(24);
            
            // Use optimized query for overdue checkouts
            List<Booking> overdueBookings = bookingRepository.findOverdueCheckouts(thresholdTime);
            
            for (Booking booking : overdueBookings) {
                if (booking.getStatus() == BookingStatus.CHECKOUT_OPEN && 
                    booking.getCheckoutOpenedAt() != null) {
                    long hoursOverdue = java.time.Duration.between(
                        booking.getCheckoutOpenedAt(), 
                        java.time.Instant.now()
                    ).toHours();
                    
                    if (hoursOverdue >= 24) {
                        // TODO: Escalate to admin, calculate significant late fees
                        log.warn("[CheckOutScheduler] OVERDUE RETURN: Booking {} is {} hours overdue!",
                            booking.getId(), hoursOverdue);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[CheckOutScheduler] Failed to escalate overdue returns", e);
        }
    }
}
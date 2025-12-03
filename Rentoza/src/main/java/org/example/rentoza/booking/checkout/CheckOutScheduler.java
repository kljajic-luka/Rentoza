package org.example.rentoza.booking.checkout;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
    
    // Metrics
    private final Counter checkoutWindowOpenedCounter;
    private final Counter checkoutReminderSentCounter;

    public CheckOutScheduler(
            CheckOutService checkOutService,
            BookingRepository bookingRepository,
            MeterRegistry meterRegistry) {
        this.checkOutService = checkOutService;
        this.bookingRepository = bookingRepository;
        
        this.checkoutWindowOpenedCounter = Counter.builder("checkout.window.opened")
                .description("Checkout windows opened by scheduler")
                .register(meterRegistry);
        
        this.checkoutReminderSentCounter = Counter.builder("checkout.reminder.sent")
                .description("Checkout reminders sent")
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
    @Scheduled(cron = "${app.checkout.scheduler.window-cron:0 0 * * * *}")
    @Transactional
    public void openCheckoutWindows() {
        LocalDate today = LocalDate.now(SERBIA_ZONE);
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
     * <p>Cron: {@code 0 0 0/4 * * *} (every 4 hours)
     */
    @Scheduled(cron = "${app.checkout.scheduler.reminder-cron:0 0 0/4 * * *}")
    @Transactional(readOnly = true)
    public void sendCheckoutReminders() {
        log.debug("[CheckOutScheduler] Running checkout reminder check");
        
        try {
            List<Booking> bookingsNeedingReminder = bookingRepository.findAll().stream()
                .filter(b -> b.getStatus() == BookingStatus.CHECKOUT_OPEN)
                .filter(b -> b.getCheckoutOpenedAt() != null)
                .filter(b -> {
                    // Opened more than 2 hours ago
                    long hoursOpen = java.time.Duration.between(
                        b.getCheckoutOpenedAt(), 
                        java.time.Instant.now()
                    ).toHours();
                    return hoursOpen >= 2;
                })
                .toList();
            
            for (Booking booking : bookingsNeedingReminder) {
                // TODO: Send reminder notification
                log.info("[CheckOutScheduler] Would send checkout reminder for booking {}", booking.getId());
                checkoutReminderSentCounter.increment();
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
     * <p>Cron: {@code 0 0 0/6 * * *} (every 6 hours)
     */
    @Scheduled(cron = "${app.checkout.scheduler.escalation-cron:0 0 0/6 * * *}")
    @Transactional(readOnly = true)
    public void escalateOverdueReturns() {
        log.debug("[CheckOutScheduler] Running overdue return escalation check");
        
        try {
            List<Booking> overdueBookings = bookingRepository.findAll().stream()
                .filter(b -> b.getStatus() == BookingStatus.CHECKOUT_OPEN)
                .filter(b -> b.getCheckoutOpenedAt() != null)
                .filter(b -> {
                    // Opened more than 24 hours ago
                    long hoursOpen = java.time.Duration.between(
                        b.getCheckoutOpenedAt(), 
                        java.time.Instant.now()
                    ).toHours();
                    return hoursOpen >= 24;
                })
                .toList();
            
            for (Booking booking : overdueBookings) {
                // TODO: Escalate to admin, calculate significant late fees
                log.warn("[CheckOutScheduler] OVERDUE RETURN: Booking {} is {} hours overdue!",
                    booking.getId(),
                    java.time.Duration.between(
                        booking.getCheckoutOpenedAt(), 
                        java.time.Instant.now()
                    ).toHours());
            }
        } catch (Exception e) {
            log.error("[CheckOutScheduler] Failed to escalate overdue returns", e);
        }
    }
}



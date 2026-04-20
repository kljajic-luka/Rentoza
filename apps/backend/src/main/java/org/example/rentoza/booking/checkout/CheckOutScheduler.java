package org.example.rentoza.booking.checkout;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.dispute.DamageClaim;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.booking.dispute.DamageClaimStatus;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
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

    @Value("${app.checkout.window-hours-before-trip:1}")
    private int windowHoursBeforeTrip;

    private final CheckOutService checkOutService;
    private final BookingRepository bookingRepository;
    private final DamageClaimRepository damageClaimRepository;
    private final NotificationService notificationService;
    private final SchedulerIdempotencyService idempotencyService;
    
    // Metrics
    private final Counter checkoutWindowOpenedCounter;
    private final Counter checkoutReminderSentCounter;
    private final Counter schedulerSkippedCounter;
    private final Counter damageDisputeTimeoutCounter;

    public CheckOutScheduler(
            CheckOutService checkOutService,
            BookingRepository bookingRepository,
            DamageClaimRepository damageClaimRepository,
            NotificationService notificationService,
            SchedulerIdempotencyService idempotencyService,
            MeterRegistry meterRegistry) {
        this.checkOutService = checkOutService;
        this.bookingRepository = bookingRepository;
        this.damageClaimRepository = damageClaimRepository;
        this.notificationService = notificationService;
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
        
        this.damageDisputeTimeoutCounter = Counter.builder("checkout.damage_dispute.timeout")
                .description("Damage disputes timed out and escalated (VAL-010)")
                .register(meterRegistry);
    }

    /**
     * Open checkout windows for trips approaching return time.
     *
     * <p>Finds IN_TRIP bookings with end time before:
     * {@code now + app.checkout.window-hours-before-trip} and opens checkout.
     *
     * <p>Cron default: {@code 0 0 * * * *} (every hour at :00).
     * In production this is typically overridden to every 15 minutes.
     */
    @Scheduled(cron = "${app.checkout.scheduler.window-cron:0 0 * * * *}", zone = "Europe/Belgrade")
    @Transactional
    public void openCheckoutWindows() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(SERBIA_ZONE);
        int minuteBucket = (now.getMinute() / 15) * 15;
        String taskId = String.format("checkout-window-%s-%02d-%02d",
            now.toLocalDate(), now.getHour(), minuteBucket);

        // Idempotency guard tuned for 15-minute cron compatibility.
        if (!idempotencyService.tryAcquireLock(taskId, Duration.ofMinutes(14))) {
            log.debug("[CheckOutScheduler] Skipping duplicate checkout window opening: {}", taskId);
            schedulerSkippedCounter.increment();
            return;
        }

        java.time.LocalDateTime thresholdTime = now.plusHours(windowHoursBeforeTrip);
        log.debug("[CheckOutScheduler] Opening windows up to thresholdTime={} (now={}, hoursBeforeTrip={})",
            thresholdTime, now, windowHoursBeforeTrip);

        try {
            List<Booking> eligibleBookings = checkOutService.findBookingsForCheckoutOpening(thresholdTime);

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
                log.info("[CheckOutScheduler] Opened {} checkout windows (thresholdTime={})", opened, thresholdTime);
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
                    try {
                        // Send reminder notification to guest
                        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                                .recipientId(booking.getRenter().getId())
                                .type(NotificationType.CHECKOUT_REMINDER)
                                .message("Podsetnik: Molimo vratite vozilo i završite checkout.")
                                .relatedEntityId(String.valueOf(booking.getId()))
                                .build());
                        
                        log.info("[CheckOutScheduler] Sent checkout reminder for booking {}", booking.getId());
                        checkoutReminderSentCounter.increment();
                    } catch (Exception e) {
                        log.error("[CheckOutScheduler] Failed to send reminder for booking {}: {}",
                            booking.getId(), e.getMessage());
                    }
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
                        try {
                            // Send late return notification to guest
                            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                                    .recipientId(booking.getRenter().getId())
                                    .type(NotificationType.LATE_RETURN_DETECTED)
                                    .message(String.format("Vozilo kasni %d sati! Molimo vratite vozilo hitno ili kontaktirajte podršku.", hoursOverdue))
                                    .relatedEntityId(String.valueOf(booking.getId()))
                                    .build());
                            
                            // Send late return notification to host
                            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                                    .recipientId(booking.getCar().getOwner().getId())
                                    .type(NotificationType.LATE_RETURN_DETECTED)
                                    .message(String.format("Vozilo kasni %d sati sa vraćanjem. Eskaliramo podršci.", hoursOverdue))
                                    .relatedEntityId(String.valueOf(booking.getId()))
                                    .build());
                            
                            log.warn("[CheckOutScheduler] OVERDUE RETURN: Booking {} is {} hours overdue - notifications sent",
                                booking.getId(), hoursOverdue);
                        } catch (Exception e) {
                            log.error("[CheckOutScheduler] Failed to send late return notification for booking {}: {}",
                                booking.getId(), e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("[CheckOutScheduler] Failed to escalate overdue returns", e);
        }
    }
    
    // ==================== GHOST TRIP: AUTO NO-SHOW AFTER 48H ====================
    
    /**
     * Handle ghost trips where guest never completes checkout.
     * 
     * <p>Runs every 6 hours. Finds bookings in CHECKOUT_OPEN status where:
     * <ul>
     *   <li>Checkout window has been open for 48+ hours</li>
     *   <li>Guest has NOT completed checkout</li>
     * </ul>
     * 
     * <p>When triggered:
     * <ul>
     *   <li>Booking flagged as NO_SHOW_GUEST (ghost trip)</li>
     *   <li>Full security deposit charged</li>
     *   <li>Notifications sent to host and admin</li>
     * </ul>
     * 
     * <p>Cron: {@code 0 15 1,7,13,19 * * *} (every 6 hours at :15)
    */
    @Scheduled(cron = "${app.checkout.scheduler.ghost-trip-cron:0 15 1,7,13,19 * * *}", zone = "Europe/Belgrade")
    public void handleGhostTrips() {
        String taskId = "ghost-trip-check-" + LocalDate.now(SERBIA_ZONE) + "-" + 
                        (java.time.LocalTime.now(SERBIA_ZONE).getHour() / 6);
        
        if (!idempotencyService.tryAcquireLock(taskId, Duration.ofMinutes(350))) {
            log.debug("[CheckOutScheduler] Skipping duplicate ghost trip check: {}", taskId);
            schedulerSkippedCounter.increment();
            return;
        }
        
        log.info("[CheckOutScheduler] Running ghost trip (no-checkout) check");
        
        try {
            // Find bookings with checkout open for 48+ hours
            java.time.LocalDateTime threshold48h = java.time.LocalDateTime.now(SERBIA_ZONE).minusHours(48);
            List<Booking> ghostTrips = bookingRepository.findOverdueCheckouts(threshold48h);
            
            int processed = 0;
            for (Booking booking : ghostTrips) {
                if (booking.getStatus() == BookingStatus.CHECKOUT_OPEN && 
                    booking.getCheckoutOpenedAt() != null) {
                    
                    long hoursOverdue = Duration.between(
                        booking.getCheckoutOpenedAt(), Instant.now()
                    ).toHours();
                    
                    if (hoursOverdue >= 48) {
                        try {
                            if (handleSingleGhostTrip(booking, hoursOverdue)) {
                                processed++;
                            }
                        } catch (Exception e) {
                            log.error("[CheckOutScheduler] Failed to process ghost trip for booking {}: {}",
                                    booking.getId(), e.getMessage());
                        }
                    }
                }
            }
            
            if (processed > 0) {
                log.warn("[CheckOutScheduler] Processed {} ghost trips (48h+ no checkout)", processed);
            }
        } catch (Exception e) {
            log.error("[CheckOutScheduler] Failed to run ghost trip check", e);
        }
    }
    
    /**
     * Process a single ghost trip: flag as no-show, capture deposit, notify parties.
     */
    private boolean handleSingleGhostTrip(Booking booking, long hoursOverdue) {
        return checkOutService.processGhostTripNoShow(booking.getId(), hoursOverdue);
    }
    
    // ==================== VAL-010: DAMAGE DISPUTE TIMEOUT HANDLING ====================
    
    /**
     * Handle checkout damage dispute timeouts (VAL-010).
     * 
     * <p>Runs every 4 hours. Finds bookings in CHECKOUT_DAMAGE_DISPUTE status
     * where the deposit hold deadline has passed (7 days from damage report).
     * 
     * <p>When timeout occurs:
     * <ul>
     *   <li>Damage claim is escalated to admin for resolution</li>
     *   <li>Notifications sent to all parties</li>
     *   <li>Claim status set to CHECKOUT_TIMEOUT_ESCALATED</li>
     * </ul>
     * 
     * <p>Cron: {@code 0 30 3,7,11,15,19,23 * * *} (every 4 hours at :30)
     */
    @Scheduled(cron = "${app.checkout.scheduler.damage-timeout-cron:0 30 3,7,11,15,19,23 * * *}", zone = "Europe/Belgrade")
    @Transactional
    public void handleDamageDisputeTimeouts() {
        String taskId = "damage-dispute-timeout-" + LocalDate.now(SERBIA_ZONE) + "-" + 
                        (java.time.LocalTime.now(SERBIA_ZONE).getHour() / 4);
        
        // Idempotency guard - prevent duplicate execution within 3 hours 50 minutes
        if (!idempotencyService.tryAcquireLock(taskId, Duration.ofMinutes(230))) {
            log.debug("[CheckOutScheduler] Skipping duplicate damage dispute timeout check: {}", taskId);
            schedulerSkippedCounter.increment();
            return;
        }
        
        log.info("[VAL-010] Running damage dispute timeout check");
        
        try {
            // Find bookings in CHECKOUT_DAMAGE_DISPUTE status where hold deadline has passed
            List<Booking> timedOutBookings = bookingRepository
                    .findByStatusAndSecurityDepositHoldUntilBefore(
                            BookingStatus.CHECKOUT_DAMAGE_DISPUTE,
                            Instant.now()
                    );
            
            int escalated = 0;
            for (Booking booking : timedOutBookings) {
                try {
                    escalateDamageDisputeTimeout(booking);
                    escalated++;
                    damageDisputeTimeoutCounter.increment();
                } catch (Exception e) {
                    log.error("[VAL-010] Failed to escalate timed-out damage dispute for booking {}: {}",
                            booking.getId(), e.getMessage());
                }
            }
            
            if (escalated > 0) {
                log.info("[VAL-010] Escalated {} damage disputes due to timeout", escalated);
            }
        } catch (Exception e) {
            log.error("[VAL-010] Failed to run damage dispute timeout check", e);
        }
    }
    
    /**
     * Escalate a single damage dispute that has timed out.
     */
    private void escalateDamageDisputeTimeout(Booking booking) {
        log.info("[VAL-010] Escalating timed-out damage dispute for booking {}", booking.getId());
        
        DamageClaim claim = booking.getCheckoutDamageClaim();
        if (claim == null) {
            log.warn("[VAL-010] No damage claim found for booking {} in CHECKOUT_DAMAGE_DISPUTE status",
                    booking.getId());
            return;
        }
        
        // Update claim status
        claim.setStatus(DamageClaimStatus.CHECKOUT_TIMEOUT_ESCALATED);
        claim.setEscalated(true);
        claim.setEscalatedAt(Instant.now());
        claim.setResolutionNotes("Auto-escalated due to 7-day timeout without guest response");
        damageClaimRepository.save(claim);
        
        // CRITICAL: Extend deposit hold for admin review period (additional 7 days)
        // Without this, the hold expires and deposit could be auto-released while admin reviews.
        booking.setSecurityDepositHoldUntil(Instant.now().plus(java.time.Duration.ofDays(7)));
        booking.setDamageClaimStatus("CHECKOUT_TIMEOUT_ESCALATED");
        bookingRepository.save(booking);
        
        // Notify admin team
        log.warn("[VAL-010] ATTENTION REQUIRED: Damage dispute for booking {} escalated due to timeout. " +
                 "Claimed amount: {} RSD. Guest did not respond within 7 days.",
                 booking.getId(), claim.getClaimedAmount());
        
        // Notify host
        try {
            CreateNotificationRequestDTO hostNotification = CreateNotificationRequestDTO.builder()
                    .recipientId(booking.getCar().getOwner().getId())
                    .type(NotificationType.DISPUTE_ESCALATED)
                    .message(String.format(
                            "Vaša prijava oštećenja za rezervaciju #%d je prosleđena admin timu. " +
                            "Gost nije odgovorio u roku od 7 dana. Očekujte rešenje uskoro.",
                            booking.getId()))
                    .relatedEntityId(String.valueOf(booking.getId()))
                    .build();
            notificationService.createNotification(hostNotification);
        } catch (Exception e) {
            log.error("[VAL-010] Failed to notify host about escalation", e);
        }
        
        // Notify guest
        try {
            CreateNotificationRequestDTO guestNotification = CreateNotificationRequestDTO.builder()
                    .recipientId(booking.getRenter().getId())
                    .type(NotificationType.DISPUTE_ESCALATED)
                    .message(String.format(
                            "⚠️ Prijava oštećenja za rezervaciju #%d je prosleđena admin timu. " +
                            "Niste odgovorili u roku od 7 dana. Depozit ostaje zadržan do rešenja.",
                            booking.getId()))
                    .relatedEntityId(String.valueOf(booking.getId()))
                    .build();
            notificationService.createNotification(guestNotification);
        } catch (Exception e) {
            log.error("[VAL-010] Failed to notify guest about escalation", e);
        }
    }
}

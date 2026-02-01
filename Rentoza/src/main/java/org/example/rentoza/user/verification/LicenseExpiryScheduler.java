package org.example.rentoza.user.verification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.config.timezone.SerbiaTimeZone;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.example.rentoza.user.verification.event.LicenseExpiringEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scheduler for license expiry notifications.
 * 
 * <p>Runs daily to:
 * <ul>
 *   <li>Send reminders to users whose license expires within 30 days</li>
 *   <li>Send expired notifications to users whose license has already expired</li>
 * </ul>
 * 
 * <h2>Cron Schedule (Europe/Belgrade)</h2>
 * <ul>
 *   <li>License expiry check: Daily at 8:00 AM</li>
 * </ul>
 * 
 * @since P1-3 Notification Implementation
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.license-expiry.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class LicenseExpiryScheduler {

    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;
    private final SchedulerIdempotencyService idempotencyService;
    
    /**
     * Days before expiry to start sending warnings.
     */
    @Value("${app.license-expiry.warning-days:30}")
    private int warningDays;
    
    /**
     * Track users who have already been notified today to prevent duplicates.
     * Reset daily via idempotency service.
     */
    private final Set<Long> notifiedUsersToday = ConcurrentHashMap.newKeySet();
    
    /**
     * Check for expiring and expired licenses.
     * 
     * <p>Runs daily at 8:00 AM Serbia time.
     * <ul>
     *   <li>Sends LICENSE_EXPIRING_SOON for licenses expiring within 30 days</li>
     *   <li>Sends LICENSE_EXPIRED for licenses already expired</li>
     * </ul>
     */
    @Scheduled(cron = "${app.license-expiry.scheduler.cron:0 0 8 * * *}", zone = "Europe/Belgrade")
    @Transactional(readOnly = true)
    public void checkLicenseExpiry() {
        LocalDate today = SerbiaTimeZone.today();
        String taskId = "license-expiry-check-" + today;
        
        // Idempotency guard - prevent duplicate execution within 23 hours
        if (!idempotencyService.tryAcquireLock(taskId, Duration.ofHours(23))) {
            log.debug("[LicenseExpiry] Skipping duplicate license expiry check: {}", taskId);
            return;
        }
        
        // Clear daily tracking set
        notifiedUsersToday.clear();
        
        log.info("[LicenseExpiry] Starting license expiry check for date: {}", today);
        
        try {
            int expiringNotified = processExpiringLicenses(today);
            int expiredNotified = processExpiredLicenses(today);
            
            log.info("[LicenseExpiry] License expiry check completed: expiring={}, expired={}",
                expiringNotified, expiredNotified);
                
        } catch (Exception e) {
            log.error("[LicenseExpiry] Error during license expiry check", e);
        }
    }
    
    /**
     * Process users with licenses expiring within warning period.
     */
    private int processExpiringLicenses(LocalDate today) {
        LocalDate expiryThreshold = today.plusDays(warningDays);
        
        List<User> expiringUsers = userRepository.findUsersWithLicenseExpiringBetween(
            today.plusDays(1), // tomorrow (not expired yet)
            expiryThreshold
        );
        
        int notified = 0;
        for (User user : expiringUsers) {
            if (notifiedUsersToday.contains(user.getId())) {
                continue;
            }
            
            try {
                LocalDate expiryDate = user.getDriverLicenseExpiryDate();
                int daysUntilExpiry = (int) ChronoUnit.DAYS.between(today, expiryDate);
                
                // Publish event for listeners (email, etc.)
                eventPublisher.publishEvent(new LicenseExpiringEvent(
                    this, user, expiryDate, daysUntilExpiry
                ));
                
                notifiedUsersToday.add(user.getId());
                notified++;
                
                log.debug("[LicenseExpiry] Sent expiring notification: userId={}, expiryDate={}, daysLeft={}",
                    user.getId(), expiryDate, daysUntilExpiry);
                    
            } catch (Exception e) {
                log.error("[LicenseExpiry] Failed to notify user {} about expiring license: {}",
                    user.getId(), e.getMessage());
            }
        }
        
        return notified;
    }
    
    /**
     * Process users with already expired licenses.
     */
    private int processExpiredLicenses(LocalDate today) {
        List<User> expiredUsers = userRepository.findUsersWithLicenseExpiredBefore(today);
        
        int notified = 0;
        for (User user : expiredUsers) {
            if (notifiedUsersToday.contains(user.getId())) {
                continue;
            }
            
            try {
                LocalDate expiryDate = user.getDriverLicenseExpiryDate();
                int daysSinceExpiry = (int) ChronoUnit.DAYS.between(expiryDate, today);
                
                // Only notify once when expired (not every day)
                // Check if we've already notified this user in the past
                // For now, send notification - deduplication handled by notification service
                
                notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(user.getId())
                    .type(NotificationType.LICENSE_EXPIRED)
                    .message(String.format(
                        "Vaša vozačka dozvola je istekla pre %d dana. " +
                        "Molimo ažurirajte dozvolu da biste nastavili sa rezervacijama.",
                        daysSinceExpiry
                    ))
                    .relatedEntityId(String.valueOf(user.getId()))
                    .build());
                
                notifiedUsersToday.add(user.getId());
                notified++;
                
                log.info("[LicenseExpiry] Sent expired notification: userId={}, expiryDate={}, daysSinceExpiry={}",
                    user.getId(), expiryDate, daysSinceExpiry);
                    
            } catch (Exception e) {
                log.error("[LicenseExpiry] Failed to notify user {} about expired license: {}",
                    user.getId(), e.getMessage());
            }
        }
        
        return notified;
    }
}

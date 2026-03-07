package org.example.rentoza.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.notification.channel.NotificationChannel;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.notification.dto.NotificationEventDTO;
import org.example.rentoza.notification.dto.NotificationResponseDTO;
import org.example.rentoza.notification.dto.RegisterDeviceTokenRequestDTO;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.example.rentoza.booking.dispute.DamageClaim;

/**
 * Core service for notification management and multi-channel delivery.
 * Orchestrates notification creation, persistence, and routing through all enabled channels.
 *
 * Supports:
 * - In-app notifications (persisted + WebSocket)
 * - Email notifications (async)
 * - Push notifications (Firebase FCM)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final UserDeviceTokenRepository deviceTokenRepository;
    private final List<NotificationChannel> notificationChannels;
    private final SchedulerIdempotencyService lockService;
    private final NotificationDeliveryOutboxRepository deliveryOutboxRepository;

    /**
     * Create and send a notification to a single recipient.
     * Persists to database and delivers via all enabled channels.
     *
     * @param request Notification creation request
     * @return Created notification DTO
     */
    @Transactional
    public NotificationResponseDTO createNotification(CreateNotificationRequestDTO request) {
        User recipient = userRepository.findById(request.getRecipientId())
                .orElseThrow(() -> new ResourceNotFoundException("Recipient not found"));

        if (request.getRelatedEntityId() != null) {
            Optional<Notification> existing = notificationRepository.findByTypeAndRelatedEntityId(
                    request.getType(), request.getRelatedEntityId())
                .stream()
                .filter(notification -> notification.getRecipient() != null
                    && notification.getRecipient().getId().equals(request.getRecipientId()))
                .findFirst();

            if (existing.isPresent()) {
            log.info("Duplicate notification suppressed: type={}, relatedEntityId={}, recipientId={}",
                request.getType(), request.getRelatedEntityId(), request.getRecipientId());
            return NotificationResponseDTO.fromEntity(existing.get());
            }
        }

        Notification notification = Notification.builder()
                .recipient(recipient)
                .type(request.getType())
                .message(request.getMessage())
                .relatedEntityId(request.getRelatedEntityId())
                .read(false)
                .build();

        notification = notificationRepository.save(notification);
        log.info("Notification created: id={}, type={}, recipientId={}",
                notification.getId(),
                notification.getType(),
                recipient.getId());

        // Send through all enabled channels asynchronously
        sendThroughChannels(notification);

        return NotificationResponseDTO.fromEntity(notification);
    }

    /**
     * Create and send notifications to multiple recipients (batch).
     * Used for events that notify multiple users (e.g., booking confirmed → renter + owner).
     *
     * @param event Notification event with multiple recipients
     */
    @Transactional
    public void createBatchNotifications(NotificationEventDTO event) {
        for (Long recipientId : event.getRecipientIds()) {
            try {
                CreateNotificationRequestDTO request = CreateNotificationRequestDTO.builder()
                        .recipientId(recipientId)
                        .type(event.getType())
                        .message(event.getMessage())
                        .relatedEntityId(event.getRelatedEntityId())
                        .build();

                createNotification(request);
            } catch (Exception e) {
                log.error("Failed to create notification for recipient {}: {}",
                        recipientId, e.getMessage(), e);
            }
        }
    }

    /**
     * Send notification through all enabled channels.
     * C1 FIX: For channels that support durable delivery (Email, FCM),
     * writes outbox entries in the same transaction for retry on failure.
     * WebSocket is still fire-and-forget (real-time, no persistence needed).
     */
    private void sendThroughChannels(Notification notification) {
        for (NotificationChannel channel : notificationChannels) {
            if (channel.isEnabled()) {
                try {
                    channel.send(notification);
                    log.debug("Notification {} sent via {}", notification.getId(), channel.getChannelName());
                } catch (Exception e) {
                    log.error("Failed to send notification {} via {}: {}",
                            notification.getId(),
                            channel.getChannelName(),
                            e.getMessage(), e);
                    // C1 FIX: Write outbox entry for retry (Email and FCM channels)
                    if (isDurableChannel(channel.getChannelName())) {
                        writeOutboxEntry(notification, channel.getChannelName(), e.getMessage());
                    }
                }
            } else {
                log.debug("Channel {} is disabled, skipping", channel.getChannelName());
            }
        }
    }

    /**
     * C1 FIX: Check if a channel supports durable delivery via outbox retry.
     * WebSocket is real-time only; Email and FCM should be retried on failure.
     */
    private boolean isDurableChannel(String channelName) {
        return "Email".equals(channelName) || "Firebase Push".equals(channelName);
    }

    /**
     * C1 FIX: Write an outbox entry for a failed channel delivery.
     * Will be picked up by NotificationDeliveryRetryWorker for retry.
     */
    private void writeOutboxEntry(Notification notification, String channelName, String errorMessage) {
        try {
            NotificationDeliveryOutbox outbox = NotificationDeliveryOutbox.builder()
                    .notificationId(notification.getId())
                    .channelName(channelName)
                    .status(NotificationDeliveryOutbox.DeliveryStatus.PENDING)
                    .attemptCount(1)
                    .maxAttempts(3)
                    .lastError(errorMessage != null && errorMessage.length() > 500
                            ? errorMessage.substring(0, 500) : errorMessage)
                    .nextAttemptAt(java.time.Instant.now().plusSeconds(30))
                    .build();
            deliveryOutboxRepository.save(outbox);
            log.info("[Outbox] Retry entry created for notification {} via {} (next attempt in 30s)",
                    notification.getId(), channelName);
        } catch (Exception ex) {
            log.error("[Outbox] CRITICAL: Failed to write outbox entry for notification {} via {}: {}",
                    notification.getId(), channelName, ex.getMessage());
        }
    }

    /**
     * Get paginated notifications for a user.
     *
     * @param userId User ID
     * @param page Page number (0-indexed)
     * @param size Page size
     * @return Page of notifications
     */
    @Transactional(readOnly = true)
    public Page<NotificationResponseDTO> getUserNotifications(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Notification> notifications = notificationRepository
                .findByRecipientIdOrderByCreatedAtDesc(userId, pageable);

        return notifications.map(NotificationResponseDTO::fromEntity);
    }

    /**
     * Get unread notifications for a user.
     *
     * @param userId User ID
     * @return List of unread notifications
     */
    @Transactional(readOnly = true)
    public List<NotificationResponseDTO> getUnreadNotifications(Long userId) {
        List<Notification> notifications = notificationRepository
                .findByRecipientIdAndReadFalseOrderByCreatedAtDesc(userId);

        return notifications.stream()
                .map(NotificationResponseDTO::fromEntity)
                .toList();
    }

    /**
     * Get unread notification count for a user.
     *
     * @param userId User ID
     * @return Unread count
     */
    @Transactional(readOnly = true)
    public long getUnreadCount(Long userId) {
        return notificationRepository.countByRecipientIdAndReadFalse(userId);
    }

    /**
     * Mark a notification as read.
     *
     * @param notificationId Notification ID
     * @param userId User ID (for ownership verification)
     */
    @Transactional
    public void markAsRead(Long notificationId, Long userId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));

        // Verify ownership
        if (!notification.getRecipient().getId().equals(userId)) {
            throw new IllegalStateException("Cannot mark another user's notification as read");
        }

        if (!notification.isRead()) {
            notification.markAsRead();
            notificationRepository.save(notification);
            log.debug("Notification {} marked as read", notificationId);
        }
    }

    /**
     * Mark all notifications as read for a user.
     *
     * @param userId User ID
     * @return Number of notifications marked as read
     */
    @Transactional
    public int markAllAsRead(Long userId) {
        int count = notificationRepository.markAllAsReadForUser(userId);
        log.info("Marked {} notifications as read for user {}", count, userId);
        return count;
    }

    /**
     * Register a device token for push notifications.
     *
     * @param userId User ID
     * @param request Device token registration request
     */
    @Transactional
    public void registerDeviceToken(Long userId, RegisterDeviceTokenRequestDTO request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Check if token already exists
        Optional<UserDeviceToken> existing = deviceTokenRepository.findByDeviceToken(request.getDeviceToken());
        if (existing.isPresent()) {
            log.debug("Device token already registered for user {}", userId);
            return;
        }

        UserDeviceToken deviceToken = UserDeviceToken.builder()
                .user(user)
                .deviceToken(request.getDeviceToken())
                .platform(request.getPlatform())
                .build();

        deviceTokenRepository.save(deviceToken);
        log.info("Device token registered for user {} on platform {}", userId, request.getPlatform());
    }

    /**
     * Unregister a device token with ownership verification.
     * C5 FIX: Only the token owner can unregister their device token.
     *
     * @param deviceToken Device token to remove
     * @param userId The authenticated user requesting unregistration
     * @throws IllegalStateException if the token belongs to another user
     */
    @Transactional
    public void unregisterDeviceToken(String deviceToken, Long userId) {
        Optional<UserDeviceToken> existing = deviceTokenRepository.findByDeviceToken(deviceToken);
        if (existing.isPresent()) {
            UserDeviceToken token = existing.get();
            if (!token.getUser().getId().equals(userId)) {
                log.warn("[Security] User {} attempted to unregister device token belonging to user {}",
                        userId, token.getUser().getId());
                throw new IllegalStateException("Cannot unregister another user's device token");
            }
            deviceTokenRepository.delete(token);
            log.info("Device token unregistered for user {}", userId);
        } else {
            log.debug("Device token not found for unregistration (may have already been removed)");
        }
    }

    /**
     * Notify users about a dispute resolution.
     * Sends notifications to both guest (renter) and host (owner).
     *
     * @param claim Resolved damage claim
     * @param admin Admin who resolved the dispute
     */
    @Transactional
    public void notifyDisputeResolved(DamageClaim claim, User admin) {
       try {
           // Notify guest (renter)
           if (claim.getBooking() != null && claim.getBooking().getRenter() != null) {
               createNotification(CreateNotificationRequestDTO.builder()
                       .recipientId(claim.getBooking().getRenter().getId())
                       .type(NotificationType.DISPUTE_RESOLVED)
                       .message("Your dispute for booking " + claim.getBooking().getId() + " has been resolved.")
                       .relatedEntityId(String.valueOf(claim.getId()))
                       .build());
           }

           // Notify host (owner)
           if (claim.getBooking() != null && claim.getBooking().getCar() != null && 
               claim.getBooking().getCar().getOwner() != null) {
               createNotification(CreateNotificationRequestDTO.builder()
                       .recipientId(claim.getBooking().getCar().getOwner().getId())
                       .type(NotificationType.DISPUTE_RESOLVED)
                       .message("The dispute for your car booking " + claim.getBooking().getId() + " has been resolved.")
                       .relatedEntityId(String.valueOf(claim.getId()))
                       .build());
           }

           log.info("Dispute resolved notification sent for claim: {}", claim.getId());
       } catch (Exception e) {
           log.error("Failed to send dispute resolution notification for claim {}: {}", 
                   claim.getId(), e.getMessage(), e);
           // Don't rethrow - notification failure shouldn't fail the main operation
       }
    }

    /**
     * Notify parties when a check-in dispute is auto-cancelled due to timeout.
     * VAL-004 Phase 6: Timeout handling - sends notifications to both guest and host.
     *
     * @param booking The booking that was auto-cancelled
     * @param claim   The dispute claim that triggered auto-cancellation
     */
    @Transactional
    public void notifyCheckInDisputeAutoCancelled(Booking booking, DamageClaim claim) {
        try {
            String reason = "Your check-in dispute could not be resolved in time. " +
                    "The booking has been automatically cancelled and a full refund will be processed.";

            // Notify guest (renter)
            if (booking.getRenter() != null) {
                createNotification(CreateNotificationRequestDTO.builder()
                        .recipientId(booking.getRenter().getId())
                        .type(NotificationType.DISPUTE_RESOLVED)
                        .message("Booking #" + booking.getId() + " auto-cancelled: " + reason)
                        .relatedEntityId(String.valueOf(booking.getId()))
                        .build());
            }

            // Notify host (owner)
            if (booking.getCar() != null && booking.getCar().getOwner() != null) {
                createNotification(CreateNotificationRequestDTO.builder()
                        .recipientId(booking.getCar().getOwner().getId())
                        .type(NotificationType.DISPUTE_RESOLVED)
                        .message("Booking #" + booking.getId() + " auto-cancelled due to unresolved check-in dispute. " +
                                "Guest has been refunded.")
                        .relatedEntityId(String.valueOf(booking.getId()))
                        .build());
            }

            log.info("Check-in dispute auto-cancellation notifications sent for booking: {}", booking.getId());
        } catch (Exception e) {
            log.error("Failed to send auto-cancellation notification for booking {}: {}",
                    booking.getId(), e.getMessage(), e);
        }
    }

    /**
     * Alert admin when a check-in dispute is auto-cancelled due to timeout.
     * VAL-004 Phase 6: Logs critical alert for admin review.
     *
     * @param booking The booking that was auto-cancelled
     * @param claim   The dispute claim
     */
    public void alertAdminDisputeAutoCancelled(Booking booking, DamageClaim claim) {
        log.warn("ADMIN ALERT: Check-in dispute AUTO-CANCELLED due to timeout. " +
                "Booking ID: {}, Claim ID: {}, Dispute Type: {}, Created: {}. " +
                "Full refund issued to guest. Please review for process improvement.",
                booking.getId(), claim.getId(), claim.getDisputeType(), claim.getCreatedAt());
    }

    /**
     * Alert admins about no-show outcome (refund/operations visibility).
     *
     * @param booking Booking impacted by no-show
     * @param noShowParty Party that no-showed (HOST or GUEST)
     * @param refundSuccess Whether refund processing succeeded
     */
    @Transactional
    public void alertAdminNoShow(Booking booking, String noShowParty, boolean refundSuccess) {
        List<User> admins = userRepository.findByRole(Role.ADMIN);
        if (admins.isEmpty()) {
            log.warn("No admin users found for no-show alert. Booking: {}", booking.getId());
            return;
        }

        String hostName = booking.getCar() != null && booking.getCar().getOwner() != null
            ? (booking.getCar().getOwner().getFirstName() + " " + booking.getCar().getOwner().getLastName()).trim()
            : "N/A";
        String guestName = booking.getRenter() != null
            ? (booking.getRenter().getFirstName() + " " + booking.getRenter().getLastName()).trim()
            : "N/A";

        String refundStatus = "GUEST".equals(noShowParty)
            ? "N/A"
            : (refundSuccess ? "SUCCESS" : "FAILED");

        String message = String.format(
            "No-show alert | Booking #%d | Party: %s | Host: %s | Guest: %s | Refund: %s",
            booking.getId(),
            noShowParty,
            hostName,
            guestName,
            refundStatus
        );

        for (User admin : admins) {
            createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(admin.getId())
                .type(NotificationType.NO_SHOW_ADMIN_ALERT)
                .message(message)
                .relatedEntityId(String.valueOf(booking.getId()))
                .build());
        }

        log.info("Admin no-show alert sent for booking {} to {} admin(s)", booking.getId(), admins.size());
    }

    /**
     * Escalate check-in dispute to senior admin due to timeout.
     * VAL-004 Phase 6: Creates high-priority notification for senior admin attention.
     *
     * @param booking The booking with the stale dispute
     * @param claim   The dispute claim requiring escalation
     */
    @Transactional
    public void escalateCheckInDisputeToSeniorAdmin(Booking booking, DamageClaim claim) {
        try {
            log.warn("ESCALATION: Check-in dispute requires senior admin attention. " +
                "Booking ID: {}, Claim ID: {}, Dispute Type: {}, " +
                "Guest ID: {}, Host ID: {}, Trip Start: {}",
                    booking.getId(), claim.getId(), claim.getDisputeType(),
                booking.getRenter() != null ? booking.getRenter().getId() : "N/A",
                    booking.getCar() != null && booking.getCar().getOwner() != null ?
                    booking.getCar().getOwner().getId() : "N/A",
                    booking.getStartTime());

            // Notify guest that dispute is being escalated
            if (booking.getRenter() != null) {
                createNotification(CreateNotificationRequestDTO.builder()
                        .recipientId(booking.getRenter().getId())
                        .type(NotificationType.DISPUTE_ESCALATED)
                        .message("Your check-in dispute for booking #" + booking.getId() +
                                " has been escalated to senior management for priority resolution.")
                        .relatedEntityId(String.valueOf(booking.getId()))
                        .build());
            }

            // Notify host that dispute is being escalated
            if (booking.getCar() != null && booking.getCar().getOwner() != null) {
                createNotification(CreateNotificationRequestDTO.builder()
                        .recipientId(booking.getCar().getOwner().getId())
                        .type(NotificationType.DISPUTE_ESCALATED)
                        .message("The check-in dispute for booking #" + booking.getId() +
                                " has been escalated for priority resolution.")
                        .relatedEntityId(String.valueOf(booking.getId()))
                        .build());
            }

            log.info("Check-in dispute escalation notifications sent for booking: {}", booking.getId());
        } catch (Exception e) {
            log.error("Failed to send escalation notification for booking {}: {}",
                    booking.getId(), e.getMessage(), e);
        }
    }

    /**
     * Scheduled cleanup of expired notifications (older than 30 days).
     * Runs daily at 2 AM Europe/Belgrade timezone.
     *
     * <p>Uses distributed locking to prevent duplicate cleanup in multi-instance deployments.</p>
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "Europe/Belgrade")
    @Transactional
    public void cleanupExpiredNotifications() {
        if (!lockService.tryAcquireLock("notification.cleanup.expired", Duration.ofHours(23))) {
            log.debug("[NotificationService] Skipping cleanup — lock held by another instance");
            return;
        }

        try {
            Instant expirationDate = Instant.now().minus(30, ChronoUnit.DAYS);
            int deleted = notificationRepository.softDeleteExpiredNotifications(expirationDate);

            if (deleted > 0) {
                log.info("Soft-deleted {} expired notifications", deleted);
            }
        } finally {
            lockService.releaseLock("notification.cleanup.expired");
        }
    }
}

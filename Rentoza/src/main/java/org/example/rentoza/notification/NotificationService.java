package org.example.rentoza.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.notification.channel.NotificationChannel;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.notification.dto.NotificationEventDTO;
import org.example.rentoza.notification.dto.NotificationResponseDTO;
import org.example.rentoza.notification.dto.RegisterDeviceTokenRequestDTO;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    @Async
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
     * Each channel handles delivery asynchronously and logs failures internally.
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
                }
            } else {
                log.debug("Channel {} is disabled, skipping", channel.getChannelName());
            }
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
     * Unregister a device token.
     *
     * @param deviceToken Device token to remove
     */
    @Transactional
    public void unregisterDeviceToken(String deviceToken) {
        deviceTokenRepository.deleteByDeviceToken(deviceToken);
        log.info("Device token unregistered: {}", deviceToken);
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
     * Scheduled cleanup of expired notifications (older than 30 days).
     * Runs daily at 2 AM Europe/Belgrade timezone.
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "Europe/Belgrade")
    @Transactional
    public void cleanupExpiredNotifications() {
        Instant expirationDate = Instant.now().minus(30, ChronoUnit.DAYS);
        int deleted = notificationRepository.deleteExpiredNotifications(expirationDate);

        if (deleted > 0) {
            log.info("Cleaned up {} expired notifications", deleted);
        }
    }
    }

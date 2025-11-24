package org.example.rentoza.notification;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.notification.dto.*;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for notification management.
 *
 * Endpoints:
 * - GET /api/notifications - Get user's notifications (paginated)
 * - GET /api/notifications/unread - Get unread notifications
 * - GET /api/notifications/unread/count - Get unread count
 * - PATCH /api/notifications/{id}/read - Mark notification as read
 * - POST /api/notifications/mark-all-read - Mark all as read
 * - POST /api/notifications/register-token - Register FCM device token
 * - DELETE /api/notifications/unregister-token - Unregister device token
 * - POST /api/notifications/internal/push - Internal endpoint for chat service
 *
 * All endpoints require JWT authentication.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Slf4j
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * Get paginated notifications for authenticated user.
     *
     * @param authHeader Authorization header with JWT
     * @param page Page number (default: 0)
     * @param size Page size (default: 20, max: 100)
     * @return Page of notifications
     */
    @GetMapping
    public ResponseEntity<?> getNotifications(
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        try {
            // Limit page size
            if (size > 100) {
                size = 100;
            }

            Page<NotificationResponseDTO> notifications = notificationService.getUserNotifications(principal.id(), page, size);
            return ResponseEntity.ok(notifications);

        } catch (Exception e) {
            log.error("Failed to retrieve notifications: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to retrieve notifications"));
        }
    }

    /**
     * Get unread notifications for authenticated user.
     *
     * @param authHeader Authorization header with JWT
     * @return List of unread notifications
     */
    @GetMapping("/unread")
    public ResponseEntity<?> getUnreadNotifications(@org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal) {
        try {
            List<NotificationResponseDTO> notifications = notificationService.getUnreadNotifications(principal.id());
            return ResponseEntity.ok(notifications);

        } catch (Exception e) {
            log.error("Failed to retrieve unread notifications: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to retrieve unread notifications"));
        }
    }

    /**
     * Get unread notification count for authenticated user.
     *
     * @param authHeader Authorization header with JWT
     * @return Unread count
     */
    @GetMapping("/unread/count")
    public ResponseEntity<?> getUnreadCount(@org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal) {
        try {
            long count = notificationService.getUnreadCount(principal.id());
            return ResponseEntity.ok(Map.of("count", count));

        } catch (Exception e) {
            log.error("Failed to retrieve unread count: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to retrieve unread count"));
        }
    }

    /**
     * Mark a notification as read.
     *
     * @param id Notification ID
     * @param authHeader Authorization header with JWT
     * @return Success response
     */
    @PatchMapping("/{id}/read")
    public ResponseEntity<?> markAsRead(
            @PathVariable Long id,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal) {

        try {
            notificationService.markAsRead(id, principal.id());
            return ResponseEntity.ok(Map.of("message", "Notification marked as read"));

        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to mark notification as read: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to mark notification as read"));
        }
    }

    /**
     * Mark all notifications as read for authenticated user.
     *
     * @param authHeader Authorization header with JWT
     * @return Success response with count
     */
    @PostMapping("/mark-all-read")
    public ResponseEntity<?> markAllAsRead(@org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal) {
        try {
            int count = notificationService.markAllAsRead(principal.id());
            return ResponseEntity.ok(Map.of("message", "All notifications marked as read", "count", count));

        } catch (Exception e) {
            log.error("Failed to mark all notifications as read: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to mark all as read"));
        }
    }

    /**
     * Register FCM device token for push notifications.
     *
     * @param request Device token registration request
     * @param authHeader Authorization header with JWT
     * @return Success response
     */
    @PostMapping("/register-token")
    public ResponseEntity<?> registerDeviceToken(
            @Valid @RequestBody RegisterDeviceTokenRequestDTO request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal) {

        try {
            notificationService.registerDeviceToken(principal.id(), request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("message", "Device token registered successfully"));

        } catch (Exception e) {
            log.error("Failed to register device token: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to register device token"));
        }
    }

    /**
     * Unregister FCM device token.
     *
     * @param request Device token to unregister
     * @param authHeader Authorization header with JWT
     * @return Success response
     */
    @DeleteMapping("/unregister-token")
    public ResponseEntity<?> unregisterDeviceToken(
            @Valid @RequestBody UnregisterDeviceTokenRequestDTO request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal) {

        try {
            // Verify authenticated (no need to check user ID for unregistration, but principal ensures it)
            if (principal == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
            }

            notificationService.unregisterDeviceToken(request.getDeviceToken());
            return ResponseEntity.ok(Map.of("message", "Device token unregistered successfully"));

        } catch (Exception e) {
            log.error("Failed to unregister device token: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to unregister device token"));
        }
    }

    /**
     * Internal endpoint for creating notifications from other services (e.g., chat microservice).
     * Requires INTERNAL_SERVICE authority.
     *
     * @param request Notification creation request
     * @return Created notification
     */
    @PostMapping("/internal/create")
    @PreAuthorize("hasAuthority('INTERNAL_SERVICE')")
    public ResponseEntity<?> createNotificationInternal(@Valid @RequestBody CreateNotificationRequestDTO request) {
        try {
            NotificationResponseDTO notification = notificationService.createNotification(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(notification);

        } catch (Exception e) {
            log.error("Failed to create notification internally: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create notification"));
        }
    }

    /**
     * Internal endpoint for batch notification creation.
     * Requires INTERNAL_SERVICE authority.
     *
     * @param event Notification event with multiple recipients
     * @return Accepted response
     */
    @PostMapping("/internal/batch")
    @PreAuthorize("hasAuthority('INTERNAL_SERVICE')")
    public ResponseEntity<?> createBatchNotificationsInternal(@Valid @RequestBody NotificationEventDTO event) {
        try {
            notificationService.createBatchNotifications(event);
            return ResponseEntity.accepted()
                    .body(Map.of("message", "Batch notification request accepted"));

        } catch (Exception e) {
            log.error("Failed to create batch notifications: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create batch notifications"));
        }
    }

    /**
     * Extract user ID from JWT token.
     */

}

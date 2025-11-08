package org.example.rentoza.notification;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.user.User;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Entity representing a user's device token for Firebase Cloud Messaging (FCM).
 * Supports multiple devices per user for push notifications.
 * Tokens are automatically cleaned up when they expire or are invalidated by Firebase.
 */
@Entity
@Table(
        name = "user_device_tokens",
        indexes = {
                @Index(name = "idx_device_token_user", columnList = "user_id"),
                @Index(name = "idx_device_token_token", columnList = "device_token", unique = true)
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDeviceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "device_token", nullable = false, unique = true, length = 255)
    private String deviceToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DevicePlatform platform;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Platform types for device tokens.
     */
    public enum DevicePlatform {
        WEB,      // Progressive Web App
        ANDROID,  // Android mobile app
        IOS       // iOS mobile app
    }
}

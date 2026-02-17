package org.example.rentoza.security.password;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Stores one-time password reset tokens with 1-hour expiry.
 *
 * <p>Security features:
 * <ul>
 *   <li>Token is stored as SHA-256 hash (not plain text)</li>
 *   <li>One-time use: marked as used after consumption</li>
 *   <li>Expires after 1 hour</li>
 *   <li>Only one active token per user at a time</li>
 * </ul>
 *
 * @since Phase 3 - Security Hardening
 */
@Entity
@Table(
        name = "password_reset_tokens",
        indexes = {
                @Index(name = "idx_reset_token_hash", columnList = "token_hash"),
                @Index(name = "idx_reset_token_user_id", columnList = "user_id"),
                @Index(name = "idx_reset_token_expires_at", columnList = "expires_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class PasswordResetToken {

    /** Token validity duration: 1 hour */
    public static final long EXPIRY_DURATION_SECONDS = 3600;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * SHA-256 hash of the reset token.
     * The actual token is sent to the user via email and never stored in plain text.
     */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used", nullable = false)
    private boolean used = false;

    @Column(name = "used_at")
    private Instant usedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * IP address that requested the reset (audit trail).
     */
    @Column(name = "requested_ip", length = 45)
    private String requestedIp;

    public PasswordResetToken(Long userId, String tokenHash, Instant expiresAt, String requestedIp) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.requestedIp = requestedIp;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !used && !isExpired();
    }

    public void markUsed() {
        this.used = true;
        this.usedAt = Instant.now();
    }
}

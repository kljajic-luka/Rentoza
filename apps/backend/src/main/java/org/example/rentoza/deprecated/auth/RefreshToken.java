package org.example.rentoza.deprecated.auth;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Refresh token aligned with Supabase database schema.
 *
 * <p>Uses denormalized user_email for query efficiency while
 * supporting the FK relationship to users table.
 */
@Deprecated(since = "2.1.0", forRemoval = true)
@Entity
@Table(name = "refresh_tokens")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
@Builder
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Denormalized email for queries (avoids FK lookup for common operations)
     */
    @Column(name = "user_email", nullable = false)
    private String userEmail;

    /**
     * Hashed token value - stored in 'token' column
     */
    @Column(name = "token", nullable = false, unique = true)
    private String tokenHash;

    /**
     * Token expiration time - stored in 'expiry_date' column
     */
    @Column(name = "expiry_date", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean revoked = false;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    // For detecting token reuse attacks
    @Column(nullable = false)
    @Builder.Default
    private boolean used = false;

    @Column(name = "used_at")
    private Instant usedAt;

    // Optional: IP fingerprint for additional security
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    // Optional: User-Agent fingerprint
    @Column(name = "user_agent", length = 500)
    private String userAgent;

    // For rotation (optional chaining)
    @Column(name = "previous_token_hash")
    private String previousTokenHash;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * Mark token as used (for rotation tracking)
     */
    public void markAsUsed() {
        this.used = true;
        this.usedAt = Instant.now();
    }

    /**
     * Mark token as revoked
     */
    public void revoke() {
        this.revoked = true;
        this.revokedAt = Instant.now();
    }

    /**
     * Check if token is expired
     */
    public boolean isExpired() {
        return this.expiresAt.isBefore(Instant.now());
    }

    /**
     * Check if token has been reused (security concern)
     */
    public boolean wasReused() {
        return this.used && this.usedAt != null;
    }
}
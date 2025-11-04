package org.example.rentoza.auth;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_rt_user", columnList = "userEmail"),
        @Index(name = "idx_rt_token", columnList = "tokenHash")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
@Builder
public class RefreshToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable=false)
    private String userEmail;

    @Column(nullable=false, unique=true)
    private String tokenHash;

    @Column(nullable=false)
    private Instant expiresAt;

    @Column(nullable=false)
    private Instant createdAt;

    @Column(nullable=false)
    @Builder.Default
    private boolean revoked = false;

    // For detecting token reuse attacks
    @Column(nullable=false)
    @Builder.Default
    private boolean used = false;

    @Column
    private Instant usedAt;

    // Optional: IP fingerprint for additional security (production mode)
    @Column(length = 45) // IPv6 max length
    private String ipAddress;

    // Optional: User-Agent fingerprint for additional security
    @Column(length = 500)
    private String userAgent;

    // For rotation (optional chaining)
    private String previousTokenHash;

    /**
     * Mark token as used (for rotation tracking)
     */
    public void markAsUsed() {
        this.used = true;
        this.usedAt = Instant.now();
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
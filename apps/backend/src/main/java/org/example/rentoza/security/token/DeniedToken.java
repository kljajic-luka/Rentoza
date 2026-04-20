package org.example.rentoza.security.token;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Stores denied (blacklisted) JWT access tokens.
 *
 * <p>When a user logs out, their current access token hash is added here.
 * The SupabaseJwtAuthFilter checks this table before granting authentication.
 *
 * <p>Entries are automatically cleaned up after their original expiry time.
 *
 * @since Phase 3 - Security Hardening (Turo standard: logout invalidates JWT)
 */
@Entity
@Table(
        name = "token_denylist",
        indexes = {
                @Index(name = "idx_denylist_token_hash", columnList = "token_hash"),
                @Index(name = "idx_denylist_expires_at", columnList = "expires_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class DeniedToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * SHA-256 hash of the JWT access token.
     */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /**
     * When the original JWT was set to expire.
     * Used for cleanup: entries can be deleted after this time.
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Email of the user who was logged out (audit trail).
     */
    @Column(name = "user_email", length = 255)
    private String userEmail;

    @CreationTimestamp
    @Column(name = "denied_at", nullable = false, updatable = false)
    private Instant deniedAt;

    public DeniedToken(String tokenHash, Instant expiresAt, String userEmail) {
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.userEmail = userEmail;
    }
}

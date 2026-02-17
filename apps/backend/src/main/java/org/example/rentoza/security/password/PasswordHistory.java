package org.example.rentoza.security.password;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Stores hashed passwords for reuse prevention (Turo standard: last 3 passwords).
 *
 * <p>Used during password reset and password change flows to enforce
 * that users cannot reuse their most recent passwords.
 *
 * @since Phase 3 - Security Hardening
 */
@Entity
@Table(
        name = "password_history",
        indexes = {
                @Index(name = "idx_password_history_user_id", columnList = "user_id"),
                @Index(name = "idx_password_history_created_at", columnList = "created_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class PasswordHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * BCrypt-hashed password (same algorithm as User.password).
     */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public PasswordHistory(Long userId, String passwordHash) {
        this.userId = userId;
        this.passwordHash = passwordHash;
    }
}

package org.example.chatservice.security;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Read-only mapping to the shared token_denylist table (populated by backend on logout).
 * Chat-service only reads this table to reject logged-out tokens.
 */
@Entity
@Table(name = "token_denylist")
@Getter
@NoArgsConstructor
public class DeniedToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "user_email", length = 255)
    private String userEmail;

    @Column(name = "denied_at", nullable = false, updatable = false)
    private Instant deniedAt;
}

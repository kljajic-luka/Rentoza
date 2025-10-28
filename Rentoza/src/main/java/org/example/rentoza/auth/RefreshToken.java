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
public class RefreshToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable=false) private String userEmail;
    @Column(nullable=false, unique=true) private String tokenHash;
    @Column(nullable=false) private Instant expiresAt;
    @Column(nullable=false) private boolean revoked = false;
    private String previousTokenHash; // for rotation (optional chaining)
}
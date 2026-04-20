package org.example.rentoza.booking.checkin;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.user.User;

import java.time.Instant;

@Entity
@Table(name = "photo_rejection_budgets", indexes = {
        @Index(name = "idx_photo_rejection_budget_cooldown", columnList = "cooldown_until")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhotoRejectionBudget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", nullable = false, length = 20)
    private CheckInActorRole actorRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "photo_type", nullable = false, length = 64)
    private CheckInPhotoType photoType;

    @Column(name = "ip_address_hash", nullable = false, length = 64)
    private String ipAddressHash;

    @Column(name = "device_fingerprint_hash", nullable = false, length = 64)
    private String deviceFingerprintHash;

    @Column(name = "rejection_count", nullable = false)
    private int rejectionCount;

    @Column(name = "window_started_at", nullable = false)
    private Instant windowStartedAt;

    @Column(name = "cooldown_until")
    private Instant cooldownUntil;

    @Column(name = "last_rejection_code", length = 64)
    private String lastRejectionCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}

package org.example.rentoza.admin.entity;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.user.User;
import org.example.rentoza.booking.dispute.DamageClaim;
import org.example.rentoza.admin.dto.enums.DisputeDecision;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "dispute_resolutions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DisputeResolution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "damage_claim_id", nullable = false, unique = true)
    private DamageClaim damageClaim;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_id", nullable = false)
    private User admin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DisputeDecision decision;

    @Column(name = "decision_notes", columnDefinition = "TEXT")
    private String decisionNotes;

    @Column(name = "approved_amount", precision = 19, scale = 2)
    private BigDecimal approvedAmount;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt;
}

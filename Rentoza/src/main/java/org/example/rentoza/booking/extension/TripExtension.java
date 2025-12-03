package org.example.rentoza.booking.extension;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.booking.Booking;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Entity for trip extension requests.
 * 
 * <p>A guest can request to extend their trip while it's in progress.
 * The host has 24 hours to respond. If no response, the request expires.
 */
@Entity
@Table(name = "trip_extensions", indexes = {
    @Index(name = "idx_trip_extension_booking", columnList = "booking_id"),
    @Index(name = "idx_trip_extension_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripExtension {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    // ========== REQUEST DETAILS ==========

    /**
     * Original end date before extension.
     */
    @Column(name = "original_end_date", nullable = false)
    private LocalDate originalEndDate;

    /**
     * Requested new end date.
     */
    @Column(name = "requested_end_date", nullable = false)
    private LocalDate requestedEndDate;

    /**
     * Number of additional days requested.
     */
    @Column(name = "additional_days", nullable = false)
    private Integer additionalDays;

    /**
     * Reason for extension (optional).
     */
    @Column(name = "reason", length = 500)
    private String reason;

    // ========== PRICING ==========

    /**
     * Daily rate at time of request.
     */
    @Column(name = "daily_rate", precision = 19, scale = 2, nullable = false)
    private BigDecimal dailyRate;

    /**
     * Total additional cost for extension.
     */
    @Column(name = "additional_cost", precision = 19, scale = 2, nullable = false)
    private BigDecimal additionalCost;

    // ========== STATUS ==========

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private TripExtensionStatus status = TripExtensionStatus.PENDING;

    /**
     * When host must respond by.
     */
    @Column(name = "response_deadline")
    private Instant responseDeadline;

    /**
     * Host's response message (for decline reason).
     */
    @Column(name = "host_response", length = 500)
    private String hostResponse;

    @Column(name = "responded_at")
    private Instant respondedAt;

    // ========== TIMESTAMPS ==========

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ========== HELPER METHODS ==========

    public void approve(String response) {
        this.status = TripExtensionStatus.APPROVED;
        this.hostResponse = response;
        this.respondedAt = Instant.now();
    }

    public void decline(String response) {
        this.status = TripExtensionStatus.DECLINED;
        this.hostResponse = response;
        this.respondedAt = Instant.now();
    }

    public void cancel() {
        this.status = TripExtensionStatus.CANCELLED;
        this.respondedAt = Instant.now();
    }

    public void expire() {
        this.status = TripExtensionStatus.EXPIRED;
        this.respondedAt = Instant.now();
    }
}



package org.example.rentoza.booking;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.booking.cancellation.CancelledBy;
import org.example.rentoza.booking.cancellation.CancellationRecord;
import org.example.rentoza.car.Car;
import org.example.rentoza.user.User;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version; // Optimistic locking for concurrent approval/decline

    private LocalDate startDate;
    private LocalDate endDate;
    
    /**
     * Total booking price in Serbian Dinar (RSD).
     * Uses BigDecimal for financial precision - IEEE 754 floats cannot
     * represent decimal fractions exactly (e.g., 10.10 becomes 10.0999...).
     * 
     * Column: DECIMAL(19, 2) - supports up to 99 quadrillion RSD with 2 decimal places.
     */
    @Column(name = "total_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalPrice = BigDecimal.ZERO;

    @Column(name = "insurance_type", length = 20)
    private String insuranceType = "BASIC"; // BASIC, STANDARD, PREMIUM

    @Column(name = "prepaid_refuel")
    private boolean prepaidRefuel = false;

    // Phase 2.2: Pickup time support
    @Column(name = "pickup_time_window", length = 20)
    private String pickupTimeWindow = "MORNING"; // MORNING, AFTERNOON, EVENING, EXACT

    @Column(name = "pickup_time")
    private LocalTime pickupTime; // Only used when pickupTimeWindow is EXACT

    @Enumerated(EnumType.STRING)
    private BookingStatus status = BookingStatus.PENDING_APPROVAL;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "car_id")
    private Car car;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "renter_id")
    private User renter;

    // Host approval/decline tracking
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private User approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "declined_by")
    private User declinedBy;

    @Column(name = "declined_at")
    private LocalDateTime declinedAt;

    @Column(name = "decline_reason", length = 500)
    private String declineReason;

    @Column(name = "decision_deadline_at")
    private LocalDateTime decisionDeadlineAt;

    // Payment simulation placeholders
    @Column(name = "payment_verification_ref", length = 100)
    private String paymentVerificationRef;

    @Column(name = "payment_status", length = 20)
    private String paymentStatus = "PENDING"; // PENDING, AUTHORIZED, RELEASED

    // ==================== CANCELLATION SUPPORT (Phase 1: Turo-Style Migration) ====================

    /**
     * Daily rate snapshot at time of booking creation.
     * 
     * <p>This field locks the price used for penalty calculations, preventing
     * disputes if the car's daily rate changes between booking and cancellation.
     * 
     * <p><b>Set By:</b> BookingService.createBooking() at booking creation time.
     * <p><b>Immutable:</b> Should never be updated after initial set.
     */
    @Column(name = "snapshot_daily_rate", precision = 19, scale = 2)
    private BigDecimal snapshotDailyRate;

    /**
     * Party that cancelled this booking (denormalized for query performance).
     * 
     * <p>This is a quick-access copy of {@link CancellationRecord#getCancelledBy()}.
     * Null if booking is not cancelled.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_by", length = 20)
    private CancelledBy cancelledBy;

    /**
     * Timestamp when booking was cancelled (denormalized for query performance).
     * 
     * <p>This is a quick-access copy of {@link CancellationRecord#getInitiatedAt()}.
     * Null if booking is not cancelled.
     */
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /**
     * Full cancellation audit record with financial details.
     * 
     * <p>Contains:
     * <ul>
     *   <li>Reason for cancellation</li>
     *   <li>Hours before trip start</li>
     *   <li>Penalty amount, refund amount, host payout</li>
     *   <li>Policy version applied</li>
     *   <li>Waiver request status (if any)</li>
     * </ul>
     * 
     * <p><b>Lazy Loading:</b> Only loaded when explicitly accessed or via JOIN FETCH.
     */
    @OneToOne(mappedBy = "booking", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private CancellationRecord cancellationRecord;

    /**
     * Timestamp when booking was created.
     * Used for remorse window calculation (1-hour impulse booking protection).
     */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
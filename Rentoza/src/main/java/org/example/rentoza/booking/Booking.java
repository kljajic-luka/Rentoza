package org.example.rentoza.booking;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.car.Car;
import org.example.rentoza.user.User;

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
}
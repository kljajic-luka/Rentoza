package org.example.rentoza.booking;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.booking.cancellation.CancelledBy;
import org.example.rentoza.booking.cancellation.CancellationRecord;
import org.example.rentoza.booking.checkin.CheckInEvent;
import org.example.rentoza.booking.checkin.CheckInIdVerification;
import org.example.rentoza.booking.checkin.CheckInPhoto;
import org.example.rentoza.car.Car;
import org.example.rentoza.user.User;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

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

    // ==================== CHECK-IN WORKFLOW (Phase 1: Turo-Style Handshake) ====================

    /**
     * UUID generated when check-in window opens (T-24h before trip start).
     * Correlates all check-in events, photos, and verifications for this booking.
     * 
     * <p><b>Set By:</b> CheckInScheduler at T-24h.
     * <p><b>Immutable:</b> Should never be updated after initial set.
     */
    @Column(name = "check_in_session_id", length = 36)
    private String checkInSessionId;

    /**
     * When the check-in window was opened by the scheduler.
     * Triggers transition from ACTIVE to CHECK_IN_OPEN.
     */
    @Column(name = "check_in_opened_at")
    private Instant checkInOpenedAt;

    /**
     * When the host completed their check-in (photos, odometer, fuel).
     * Triggers transition from CHECK_IN_OPEN to CHECK_IN_HOST_COMPLETE.
     */
    @Column(name = "host_check_in_completed_at")
    private Instant hostCheckInCompletedAt;

    /**
     * When the guest completed their check-in (ID verification, condition ack).
     * Combined with host completion triggers transition to CHECK_IN_COMPLETE.
     */
    @Column(name = "guest_check_in_completed_at")
    private Instant guestCheckInCompletedAt;

    /**
     * When both parties confirmed the handshake (mutual agreement to start trip).
     * Triggers transition from CHECK_IN_COMPLETE to IN_TRIP.
     */
    @Column(name = "handshake_completed_at")
    private Instant handshakeCompletedAt;

    /**
     * Actual trip start timestamp (after handshake).
     * May differ from scheduled startDate due to late handshake.
     */
    @Column(name = "trip_started_at")
    private Instant tripStartedAt;

    /**
     * Actual trip end timestamp (for early returns or checkout).
     * May differ from scheduled endDate.
     */
    @Column(name = "trip_ended_at")
    private Instant tripEndedAt;

    // ========== ODOMETER & FUEL SNAPSHOTS (Fraud Prevention) ==========

    /**
     * Odometer reading at trip start (from host check-in photo).
     * Used for mileage billing and dispute resolution.
     */
    @Column(name = "start_odometer")
    private Integer startOdometer;

    /**
     * Odometer reading at trip end (from checkout).
     * Combined with start_odometer for total mileage calculation.
     */
    @Column(name = "end_odometer")
    private Integer endOdometer;

    /**
     * Fuel level at trip start (0-100 percent).
     * Used for prepaid refuel validation.
     */
    @Column(name = "start_fuel_level")
    private Integer startFuelLevel;

    /**
     * Fuel level at trip end (0-100 percent).
     */
    @Column(name = "end_fuel_level")
    private Integer endFuelLevel;

    // ========== REMOTE HANDOFF (Lockbox Support) ==========

    /**
     * AES-256-GCM encrypted lockbox code for remote key handoff.
     * Null for in-person handoff. Decrypted only when guest is within geofence.
     */
    @Column(name = "lockbox_code_encrypted", columnDefinition = "VARBINARY(256)")
    private byte[] lockboxCodeEncrypted;

    /**
     * When the lockbox code was revealed to the guest (audit trail).
     */
    @Column(name = "lockbox_code_revealed_at")
    private Instant lockboxCodeRevealedAt;

    // ========== GEOFENCE VALIDATION (Location-Based Handshake) ==========

    /**
     * Car's GPS latitude at check-in (from host photo EXIF or manual entry).
     */
    @Column(name = "car_latitude", precision = 10, scale = 8)
    private BigDecimal carLatitude;

    /**
     * Car's GPS longitude at check-in.
     */
    @Column(name = "car_longitude", precision = 11, scale = 8)
    private BigDecimal carLongitude;

    /**
     * Host's GPS latitude when submitting check-in.
     */
    @Column(name = "host_check_in_latitude", precision = 10, scale = 8)
    private BigDecimal hostCheckInLatitude;

    /**
     * Host's GPS longitude when submitting check-in.
     */
    @Column(name = "host_check_in_longitude", precision = 11, scale = 8)
    private BigDecimal hostCheckInLongitude;

    /**
     * Guest's GPS latitude when completing check-in.
     */
    @Column(name = "guest_check_in_latitude", precision = 10, scale = 8)
    private BigDecimal guestCheckInLatitude;

    /**
     * Guest's GPS longitude when completing check-in.
     */
    @Column(name = "guest_check_in_longitude", precision = 11, scale = 8)
    private BigDecimal guestCheckInLongitude;

    /**
     * Haversine distance in meters between guest and car at handshake.
     * Used for geofence validation (default threshold: 100m).
     */
    @Column(name = "geofence_distance_meters")
    private Integer geofenceDistanceMeters;

    // ========== CHECK-IN RELATIONSHIPS ==========

    /**
     * All check-in events for this booking (immutable audit trail).
     */
    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("eventTimestamp ASC")
    private List<CheckInEvent> checkInEvents = new ArrayList<>();

    /**
     * All check-in photos for this booking.
     */
    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<CheckInPhoto> checkInPhotos = new ArrayList<>();

    /**
     * Guest ID verification record for this booking (one-to-one).
     */
    @OneToOne(mappedBy = "booking", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private CheckInIdVerification idVerification;

    // ========== CHECK-IN HELPER METHODS ==========

    /**
     * Check if the check-in window is currently open.
     */
    public boolean isCheckInWindowOpen() {
        return status == BookingStatus.CHECK_IN_OPEN
            || status == BookingStatus.CHECK_IN_HOST_COMPLETE
            || status == BookingStatus.CHECK_IN_COMPLETE;
    }

    /**
     * Check if the trip is currently in progress.
     */
    public boolean isTripInProgress() {
        return status == BookingStatus.IN_TRIP;
    }

    /**
     * Check if this is a no-show booking.
     */
    public boolean isNoShow() {
        return status == BookingStatus.NO_SHOW_HOST
            || status == BookingStatus.NO_SHOW_GUEST;
    }

    /**
     * Calculate total mileage driven during the trip.
     * Returns null if odometer readings are not available.
     */
    public Integer getTotalMileage() {
        if (startOdometer == null || endOdometer == null) {
            return null;
        }
        return endOdometer - startOdometer;
    }

    /**
     * Check if this booking uses remote handoff (lockbox).
     */
    public boolean isRemoteHandoff() {
        return lockboxCodeEncrypted != null;
    }
}
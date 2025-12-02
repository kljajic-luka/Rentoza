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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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

    /**
     * Exact trip start timestamp (Europe/Belgrade timezone).
     * Replaces the old startDate + pickupTimeWindow model.
     * 
     * <p><b>Timezone:</b> Stored as local time (no UTC conversion).
     * Application layer interprets as Europe/Belgrade.
     * 
     * <p><b>Granularity:</b> 30-minute intervals (e.g., 09:00, 09:30, 10:00)
     */
    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    /**
     * Exact trip end timestamp (Europe/Belgrade timezone).
     * Replaces the old endDate field.
     * 
     * <p><b>Constraint:</b> Must be at least 24 hours after startTime.
     * <p><b>Default return time:</b> 10:00 AM on end date.
     */
    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;
    
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
     * May differ from scheduled startTime due to late handshake.
     */
    @Column(name = "trip_started_at")
    private Instant tripStartedAt;

    /**
     * Actual trip end timestamp (for early returns or checkout).
     * May differ from scheduled endTime.
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

    // ========== CHECKOUT WORKFLOW ==========

    /**
     * UUID generated when checkout is initiated.
     * Correlates all checkout events and photos.
     */
    @Column(name = "checkout_session_id", length = 36)
    private String checkoutSessionId;

    /**
     * When the checkout window was opened (trip end or early return).
     */
    @Column(name = "checkout_opened_at")
    private Instant checkoutOpenedAt;

    /**
     * When the guest completed their checkout (photos, readings).
     */
    @Column(name = "guest_checkout_completed_at")
    private Instant guestCheckoutCompletedAt;

    /**
     * When the host confirmed vehicle return and condition.
     */
    @Column(name = "host_checkout_completed_at")
    private Instant hostCheckoutCompletedAt;

    /**
     * When the checkout process was fully completed.
     */
    @Column(name = "checkout_completed_at")
    private Instant checkoutCompletedAt;

    // ========== CHECKOUT LOCATION TRACKING ==========

    /**
     * Guest's GPS latitude when submitting checkout.
     */
    @Column(name = "guest_checkout_latitude", precision = 10, scale = 8)
    private BigDecimal guestCheckoutLatitude;

    /**
     * Guest's GPS longitude when submitting checkout.
     */
    @Column(name = "guest_checkout_longitude", precision = 11, scale = 8)
    private BigDecimal guestCheckoutLongitude;

    /**
     * Host's GPS latitude when confirming checkout.
     */
    @Column(name = "host_checkout_latitude", precision = 10, scale = 8)
    private BigDecimal hostCheckoutLatitude;

    /**
     * Host's GPS longitude when confirming checkout.
     */
    @Column(name = "host_checkout_longitude", precision = 11, scale = 8)
    private BigDecimal hostCheckoutLongitude;

    // ========== DAMAGE ASSESSMENT ==========

    /**
     * Flag indicating new damage was reported at checkout.
     */
    @Column(name = "new_damage_reported")
    private Boolean newDamageReported = false;  // Change from boolean to Boolean

    /**
     * Host's notes about damage found at return.
     */
    @Column(name = "damage_assessment_notes", columnDefinition = "TEXT")
    private String damageAssessmentNotes;

    /**
     * Estimated damage cost in RSD.
     */
    @Column(name = "damage_claim_amount", precision = 19, scale = 2)
    private BigDecimal damageClaimAmount;

    /**
     * Status of damage claim: PENDING, APPROVED, REJECTED, PAID.
     */
    @Column(name = "damage_claim_status", length = 20)
    private String damageClaimStatus;

    // ========== LATE RETURN TRACKING ==========

    /**
     * Scheduled return time based on booking end date.
     */
    @Column(name = "scheduled_return_time")
    private Instant scheduledReturnTime;

    /**
     * Actual time guest returned the vehicle.
     */
    @Column(name = "actual_return_time")
    private Instant actualReturnTime;

    /**
     * Minutes past scheduled return (negative if early).
     */
    @Column(name = "late_return_minutes")
    private Integer lateReturnMinutes;

    /**
     * Late return fee in RSD.
     */
    @Column(name = "late_fee_amount", precision = 19, scale = 2)
    private BigDecimal lateFeeAmount;

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

    /**
     * Check if the checkout window is currently open.
     */
    public boolean isCheckoutWindowOpen() {
        return status == BookingStatus.CHECKOUT_OPEN
            || status == BookingStatus.CHECKOUT_GUEST_COMPLETE
            || status == BookingStatus.CHECKOUT_HOST_COMPLETE;
    }

    /**
     * Check if checkout has been completed.
     */
    public boolean isCheckoutComplete() {
        return status == BookingStatus.COMPLETED && checkoutCompletedAt != null;
    }

    /**
     * Check if there was a late return.
     */
    public boolean isLateReturn() {
        return lateReturnMinutes != null && lateReturnMinutes > 0;
    }

    /**
     * Check if there was an early return.
     */
    public boolean isEarlyReturn() {
        return lateReturnMinutes != null && lateReturnMinutes < 0;
    }

    // ========== DURATION HELPER METHODS (Exact Timestamp Architecture) ==========

    /**
     * Calculate the booking duration in hours.
     * 
     * @return hours between startTime and endTime
     */
    public long getDurationHours() {
        if (startTime == null || endTime == null) {
            return 0;
        }
        return ChronoUnit.HOURS.between(startTime, endTime);
    }

    /**
     * Calculate the booking duration in 24-hour periods (for pricing).
     * Rounds up to the nearest full day.
     * 
     * @return number of 24-hour periods (minimum 1)
     */
    public int getDurationDays() {
        long hours = getDurationHours();
        return Math.max(1, (int) Math.ceil(hours / 24.0));
    }

    /**
     * Get the start date portion (for display and calendar purposes).
     * 
     * @return LocalDate of the trip start
     */
    public java.time.LocalDate getStartDate() {
        return startTime != null ? startTime.toLocalDate() : null;
    }

    /**
     * Get the end date portion (for display and calendar purposes).
     * 
     * @return LocalDate of the trip end
     */
    public java.time.LocalDate getEndDate() {
        return endTime != null ? endTime.toLocalDate() : null;
    }
}
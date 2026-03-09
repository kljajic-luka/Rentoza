package org.example.rentoza.booking;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.booking.cancellation.CancelledBy;
import org.example.rentoza.booking.cancellation.CancellationRecord;
import org.example.rentoza.payment.ChargeLifecycleStatus;
import org.example.rentoza.payment.DepositLifecycleStatus;
import org.example.rentoza.booking.checkin.CheckInEvent;
import org.example.rentoza.booking.checkin.CheckInIdVerification;
import org.example.rentoza.booking.checkin.CheckInPhoto;
import org.example.rentoza.booking.dispute.DamageClaim;
import org.example.rentoza.car.Car;
import org.example.rentoza.common.GeoPoint;
import org.example.rentoza.config.timezone.SerbiaTimeZone;
import org.example.rentoza.user.User;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
    @Column(name = "start_time", nullable = false, columnDefinition = "TIMESTAMP WITHOUT TIME ZONE")
    private LocalDateTime startTime;

    /**
     * Exact trip end timestamp (Europe/Belgrade timezone).
     * Replaces the old endDate field.
     * 
     * <p><b>Constraint:</b> Must be at least 24 hours after startTime.
     * <p><b>Default return time:</b> 10:00 AM on end date.
     */
    @Column(name = "end_time", nullable = false, columnDefinition = "TIMESTAMP WITHOUT TIME ZONE")
    private LocalDateTime endTime;

    /**
     * Canonical UTC trip start timestamp.
     *
     * <p>Legacy {@code startTime} remains for Belgrade-local API compatibility,
     * but scheduler/timing-sensitive logic should use this field as source of truth.
     */
    @Column(name = "start_time_utc", nullable = false)
    private Instant startTimeUtc;

    /**
     * Canonical UTC trip end timestamp.
     */
    @Column(name = "end_time_utc", nullable = false)
    private Instant endTimeUtc;
    
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
    @Column(nullable = false, length = 50)
    private BookingStatus status = BookingStatus.PENDING_APPROVAL;

    // P0-4 FIX: Booking MUST have a car - added nullable = false
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "car_id", nullable = false)
    private Car car;

    // P0-4 FIX: Booking MUST have a renter - added nullable = false
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "renter_id", nullable = false)
    private User renter;

    // Host approval/decline tracking (nullable - only set when approved)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private User approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

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
    
    @Column(name = "payment_reference", length = 100)
    private String paymentReference;

    @Column(name = "payout_retry_count")
    private Integer payoutRetryCount = 0;

    @Column(name = "last_payout_retry_at")
    private Instant lastPayoutRetryAt;

    /**
     * Legacy free-form payment status string — kept for read-only backward compatibility.
     * All runtime logic MUST use {@link #chargeLifecycleStatus} and {@link #depositLifecycleStatus}.
     *
     * @deprecated Use {@code chargeLifecycleStatus} instead.
     */
    @Deprecated(forRemoval = false)
    @Column(name = "payment_status", length = 20)
    private String paymentStatus = "PENDING"; // PENDING, AUTHORIZED, RELEASED — legacy

    /**
     * Typed charge lifecycle state machine.
     * Drives all booking-payment logic (authorize → capture → refund / reauth paths).
     *
     * <p>This is the authoritative payment state. {@code paymentStatus} is kept only
     * for backward-compat reads by existing code until full migration.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "charge_lifecycle_status", length = 30)
    private ChargeLifecycleStatus chargeLifecycleStatus = ChargeLifecycleStatus.PENDING;

    /**
     * Typed security-deposit lifecycle state machine.
     * Drives all deposit logic (authorize → release / capture for damage).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "deposit_lifecycle_status", length = 30)
    private DepositLifecycleStatus depositLifecycleStatus = DepositLifecycleStatus.PENDING;

    /**
     * Authorization ID for the booking payment hold.
     * Used to capture or release the hold after host decision.
     * 
     * <p><b>Set By:</b> BookingPaymentService.processBookingPayment() at creation time.
     * <p><b>Captured By:</b> BookingPaymentService.captureBookingPayment() after trip completion.
     * <p><b>Released By:</b> BookingPaymentService.releaseBookingPayment() on decline/expiry.
     */
    @Column(name = "booking_authorization_id", length = 100)
    private String bookingAuthorizationId;

    /**
     * Authorization ID for the security deposit hold.
     * Used to release the deposit after checkout (no damage) or capture for damage claims.
     *
     * <p><b>Set By:</b> BookingPaymentService.authorizeDeposit() — called at check-in window
     *   opening (T-Xh before trip start) rather than at booking creation, so the hold
     *   remains within the card authorization lifetime.
     * <p><b>Released By:</b> BookingPaymentService.releaseDeposit() at checkout.
     * <p><b>Captured By:</b> BookingPaymentService.chargeDamage() if damage reported.
     */
    @Column(name = "deposit_authorization_id", length = 100)
    private String depositAuthorizationId;

    /**
     * Tokenized payment-method identifier stored at booking creation time.
     * Re-used at check-in window opening to authorize the security deposit hold,
     * so the deposit auth is placed ≤ 24h before trip start (within card auth lifetime).
     */
    @Column(name = "stored_payment_method_id", length = 100)
    private String storedPaymentMethodId;

    /**
     * UTC instant at which the booking payment authorization expires.
     * Monri authorizations are typically valid for 7 days; this field enables
     * the scheduler to detect expiry and trigger the reauth flow.
     */
    @Column(name = "booking_auth_expires_at")
    private Instant bookingAuthExpiresAt;

    /**
     * UTC instant at which the security deposit authorization expires.
     */
    @Column(name = "deposit_auth_expires_at")
    private Instant depositAuthExpiresAt;

    /**
     * Number of capture attempts made for the booking payment.
     * Incremented on each attempt; stops at max (see BookingPaymentService).
     */
    @Column(name = "capture_attempts", nullable = false)
    private int captureAttempts = 0;

    /**
     * Number of capture attempts made for the security deposit.
     */
    @Column(name = "deposit_capture_attempts", nullable = false)
    private int depositCaptureAttempts = 0;

    /**
     * Service fee snapshot at booking creation time.
     * Prevents price drift when fee rates change after booking.
     * 
     * <p><b>Set By:</b> BookingService.createBooking() at booking creation time.
     * <p><b>Immutable:</b> Should never be updated after initial set.
     */
    @Column(name = "service_fee_snapshot", precision = 19, scale = 2)
    private BigDecimal serviceFeeSnapshot;

    /**
     * Insurance cost snapshot at booking creation time.
     * Prevents price drift when insurance rates change after booking.
     * 
     * <p><b>Set By:</b> BookingService.createBooking() at booking creation time.
     * <p><b>Immutable:</b> Should never be updated after initial set.
     */
    @Column(name = "insurance_cost_snapshot", precision = 19, scale = 2)
    private BigDecimal insuranceCostSnapshot;

    /**
     * Idempotency key for booking creation.
     * Prevents duplicate bookings on client retry.
     * 
     * <p><b>Set By:</b> BookingService.createBooking() from client-provided key.
     * <p><b>Unique constraint:</b> Prevents duplicate creation requests.
     */
    @Column(name = "idempotency_key", length = 64, unique = true)
    private String idempotencyKey;

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
     * 
     * @deprecated Denormalized field duplicates CancellationRecord.cancelledBy.
     *             Use {@link #getCancellationRecord()}.getCancelledBy() as the source of truth.
     *             This field is kept for backwards compatibility with existing queries.
     *             Must be updated transactionally with CancellationRecord.
     */
    @Deprecated(since = "2.0", forRemoval = false)
    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_by", length = 20)
    private CancelledBy cancelledBy;

    /**
     * Timestamp when booking was cancelled (denormalized for query performance).
     * 
     * <p>This is a quick-access copy of {@link CancellationRecord#getInitiatedAt()}.
     * Null if booking is not cancelled.
     * 
     * @deprecated Denormalized field duplicates CancellationRecord.initiatedAt.
     *             Use {@link #getCancellationRecord()}.getInitiatedAt() as the source of truth.
     *             This field is kept for backwards compatibility with existing queries.
     *             Must be updated transactionally with CancellationRecord.
     */
    @Deprecated(since = "2.0", forRemoval = false)
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
     * Admin override timestamp when guest condition acknowledgement is forced.
     */
    @Column(name = "check_in_admin_override_at")
    private Instant checkInAdminOverrideAt;

    /**
     * Number of accepted photos from guest during dual-party check-in.
     * Phase 2 Enterprise Upgrade: Dual-party verification.
     */
    @Column(name = "guest_checkin_photo_count")
    private Integer guestCheckinPhotoCount;

    /**
     * When guest completed all required dual-party photos.
     * Phase 2 Enterprise Upgrade: Dual-party verification.
     */
    @Column(name = "guest_checkin_photos_completed_at")
    private Instant guestCheckinPhotosCompletedAt;

    /**
     * Number of accepted photos from host during checkout verification.
     * Phase 2 Enterprise Upgrade: Dual-party verification.
     */
    @Column(name = "host_checkout_photo_count")
    private Integer hostCheckoutPhotoCount;

    /**
     * Number of discrepancies detected between check-in and checkout photos.
     * Phase 2 Enterprise Upgrade: Automated damage detection.
     */
    @Column(name = "checkout_discrepancy_count")
    private Integer checkoutDiscrepancyCount;

    /**
     * When host completed all required checkout photos.
     * Phase 2 Enterprise Upgrade: Dual-party verification.
     */
    @Column(name = "host_checkout_photos_completed_at")
    private Instant hostCheckoutPhotosCompletedAt;

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

    // ========== PICKUP LOCATION (Phase 2.4: Geospatial Migration) ==========

    /**
     * Agreed pickup location snapshot at booking creation time.
     * 
     * <p><b>IMMUTABLE:</b> This location is locked when booking is created.
     * Represents the contractual pickup point agreed between guest and host.
     * 
     * <p><b>Purpose:</b>
     * <ul>
     *   <li>Calculate delivery distance/fee at booking</li>
     *   <li>Validate car location variance at check-in</li>
     *   <li>Display exact pickup address to booked guest</li>
     *   <li>Geofence validation reference point</li>
     * </ul>
     * 
     * <p><b>Different from car_latitude/car_longitude:</b>
     * Those fields capture where the car ACTUALLY IS at check-in (T-24h),
     * which may differ from the agreed pickup location.
     * 
     * @see org.example.rentoza.common.GeoPoint
     */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "latitude", column = @Column(name = "pickup_latitude")),
            @AttributeOverride(name = "longitude", column = @Column(name = "pickup_longitude")),
            @AttributeOverride(name = "address", column = @Column(name = "pickup_address")),
            @AttributeOverride(name = "city", column = @Column(name = "pickup_city")),
            @AttributeOverride(name = "zipCode", column = @Column(name = "pickup_zip_code")),
            @AttributeOverride(name = "accuracyMeters", column = @Column(name = "pickup_accuracy_meters"))
    })
    private GeoPoint pickupLocation;

    /**
     * Distance in meters between booked pickup location and actual car location at check-in.
     * 
     * <p>Used for:
     * <ul>
     *   <li>Audit trail: track if host moved car after booking</li>
     *   <li>Dispute resolution: "car was not where promised"</li>
     *   <li>Warning threshold: >500m triggers host notification</li>
     *   <li>Block threshold: >2km may block check-in completion</li>
     * </ul>
     */
    @Column(name = "pickup_location_variance_meters")
    private Integer pickupLocationVarianceMeters;

    // NOTE: executionLocationUpdatedBy and executionLocationUpdatedAt columns were removed
    // as dead code - pickup location refinement UI was never implemented.
    // Database migration V20251206__remove_dead_booking_columns.sql should drop these columns.

    // ========== DELIVERY TRACKING ==========

    /**
     * Calculated driving distance for delivery (from OSRM).
     * Null if guest picks up at car location (no delivery).
     */
    @Column(name = "delivery_distance_km", precision = 8, scale = 2)
    private BigDecimal deliveryDistanceKm;

    /**
     * Final calculated delivery fee (in RSD).
     * May differ from estimate due to POI overrides or promotions.
     */
    @Column(name = "delivery_fee_calculated", precision = 10, scale = 2)
    private BigDecimal deliveryFeeCalculated;

    // ========== CHECK-IN GEOFENCE VALIDATION ==========

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

    // ========== DEPOSIT HOLD (VAL-010) ==========

    /**
     * Reason why security deposit is being held.
     * Set when damage is reported at checkout.
     * 
     * <p>Values: DAMAGE_CLAIM, DISPUTE, LATE_RETURN_EXCESSIVE</p>
     * 
     * @since VAL-010 - Damage Claim Blocks Deposit Release
     */
    @Column(name = "security_deposit_hold_reason", length = 50)
    private String securityDepositHoldReason;

    /**
     * Timestamp until which the deposit will be held.
     * After this time, scheduler may auto-resolve or escalate.
     * Default: 7 days from damage report.
     * 
     * @since VAL-010 - Damage Claim Blocks Deposit Release
     */
    @Column(name = "security_deposit_hold_until")
    private Instant securityDepositHoldUntil;

    /**
     * Whether the security deposit has been released.
     * Set to true when deposit is released (either to renter or captured for owner).
     * 
     * @since VAL-010 - Damage Claim Blocks Deposit Release
     */
    @Column(name = "security_deposit_released")
    private Boolean securityDepositReleased;

    /**
     * Timestamp when the security deposit was released or captured.
     * 
     * @since VAL-010 - Damage Claim Blocks Deposit Release
     */
    @Column(name = "security_deposit_resolved_at")
    private Instant securityDepositResolvedAt;

    /**
     * Reference to the checkout damage claim (if any).
     * Links to DamageClaim entity for full dispute workflow.
     * 
     * @since VAL-010 - Damage Claim Blocks Deposit Release
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checkout_damage_claim_id")
    private DamageClaim checkoutDamageClaim;

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

    // ========== PHASE 4B: IN-PERSON LICENSE VERIFICATION (Insurance Compliance) ==========

    /**
     * Timestamp when the host confirmed they visually verified the guest's driver's license.
     * 
     * <p><b>Purpose:</b> For in-person handshakes (no lockbox), hosts must confirm they
     * have verified the guest's physical license matches their ID verification before
     * the handshake can complete. This is critical for insurance compliance.
     * 
     * <p><b>Set By:</b> CheckInService.confirmLicenseVerifiedInPerson()
     * <p><b>Required:</b> Only for in-person handshakes (lockboxCodeEncrypted == null)
     * 
     * @see #licenseVerifiedByUserId
     */
    @Column(name = "license_verified_in_person_at")
    private Instant licenseVerifiedInPersonAt;

    /**
     * User ID of the host who confirmed in-person license verification.
     * 
     * <p><b>Audit Trail:</b> Captures which user (host account) performed the verification.
     * Used for dispute resolution and compliance audits.
     */
    @Column(name = "license_verified_by_user_id")
    private Long licenseVerifiedByUserId;

    // ========== PHASE 4D: TIERED LATE FEE TRACKING ==========

    /**
     * The fee tier applied to the late return.
     * 
     * <p><b>Tiers:</b>
     * <ul>
     *   <li>1: First 2 hours late (base rate)</li>
     *   <li>2: 2-6 hours late (1.5x rate)</li>
     *   <li>3: 6+ hours late (2x rate)</li>
     * </ul>
     * 
     * <p>Null if no late fee applied.
     */
    @Column(name = "late_fee_tier")
    private Integer lateFeeTier;

    /**
     * Flag indicating the vehicle has not been returned within the threshold.
     * 
     * <p><b>Threshold:</b> Set when vehicle is 24+ hours overdue.
     * <p><b>Escalation:</b> Triggers manual review and potential insurance notification.
     */
    @Column(name = "vehicle_not_returned_flag")
    private Boolean vehicleNotReturnedFlag = false;

    /**
     * When the vehicle was flagged as not returned.
     */
    @Column(name = "vehicle_not_returned_flagged_at")
    private Instant vehicleNotReturnedFlaggedAt;

    // ========== PHASE 4F: IMPROPER RETURN STATE ==========

    /**
     * Flag indicating the vehicle was returned in an improper state.
     * 
     * <p><b>Conditions that trigger this flag:</b>
     * <ul>
     *   <li>Fuel level significantly lower than start (>25% difference)</li>
     *   <li>Excessive mileage (>2x estimated)</li>
     *   <li>Cleanliness issues requiring professional cleaning</li>
     *   <li>Smoking evidence detected</li>
     * </ul>
     */
    @Column(name = "improper_return_flag")
    private Boolean improperReturnFlag = false;

    /**
     * Code indicating the type of improper return.
     * 
     * <p><b>Codes:</b>
     * <ul>
     *   <li>LOW_FUEL: Fuel not refilled per agreement</li>
     *   <li>EXCESSIVE_MILEAGE: Miles exceeded estimate by >2x</li>
     *   <li>CLEANING_REQUIRED: Professional cleaning needed</li>
     *   <li>SMOKING_DETECTED: Evidence of smoking in vehicle</li>
     *   <li>WRONG_LOCATION: Returned to different location</li>
     * </ul>
     */
    @Column(name = "improper_return_code", length = 30)
    private String improperReturnCode;

    /**
     * Notes describing the improper return condition.
     */
    @Column(name = "improper_return_notes", columnDefinition = "TEXT")
    private String improperReturnNotes;

    // ========== SECURITY DEPOSIT ==========

    /**
     * Security deposit amount held for this booking (in RSD).
     * Set at check-in when deposit is authorized.
     * Released at checkout after damage assessment.
     * 
     * <p><b>Set By:</b> CheckInService when deposit is authorized.
     * <p><b>Released By:</b> CheckoutSagaOrchestrator at saga completion.
     */
    @Column(name = "security_deposit", precision = 19, scale = 2)
    private BigDecimal securityDeposit;

    // ========== LEGAL-ROLE METADATA (Phase 6 Compliance) ==========

    @Column(name = "platform_role", length = 30)
    private String platformRole = "INTERMEDIARY";

    @Column(name = "contract_type", length = 30)
    private String contractType = "OWNER_RENTER_DIRECT";

    @Column(name = "terms_version", length = 20)
    private String termsVersion;

    @Column(name = "terms_content_hash", length = 128)
    private String termsContentHash;

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
      * Get the total amount for this booking.
      * Alias for totalPrice to maintain compatibility.
      */
    public BigDecimal getTotalAmount() {
        return totalPrice;
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
      * Get the updated timestamp for this booking.
      */
    public Instant getUpdatedAt() {
        return updatedAt;
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
        return (status == BookingStatus.CHECKOUT_SETTLEMENT_PENDING || status == BookingStatus.COMPLETED)
                && checkoutCompletedAt != null;
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

    /**
     * Canonical UTC source-of-truth for start time during transition.
     */
    public Instant getCanonicalStartTimeUtc() {
        if (startTimeUtc != null) {
            return startTimeUtc;
        }
        return SerbiaTimeZone.toInstant(startTime);
    }

    /**
     * Canonical UTC source-of-truth for end time during transition.
     */
    public Instant getCanonicalEndTimeUtc() {
        if (endTimeUtc != null) {
            return endTimeUtc;
        }
        return SerbiaTimeZone.toInstant(endTime);
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
        this.startTimeUtc = startTime != null ? SerbiaTimeZone.toInstant(startTime) : null;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
        this.endTimeUtc = endTime != null ? SerbiaTimeZone.toInstant(endTime) : null;
    }

    public void setStartTimeUtc(Instant startTimeUtc) {
        this.startTimeUtc = startTimeUtc;
        this.startTime = startTimeUtc != null ? SerbiaTimeZone.toLocalDateTime(startTimeUtc) : null;
    }

    public void setEndTimeUtc(Instant endTimeUtc) {
        this.endTimeUtc = endTimeUtc;
        this.endTime = endTimeUtc != null ? SerbiaTimeZone.toLocalDateTime(endTimeUtc) : null;
    }

    @PrePersist
    @PreUpdate
    void synchronizeTripTimeColumns() {
        // Dual-write transition: keep UTC canonical and local compatibility fields in sync.
        if (startTimeUtc == null && startTime != null) {
            startTimeUtc = SerbiaTimeZone.toInstant(startTime);
        }
        if (endTimeUtc == null && endTime != null) {
            endTimeUtc = SerbiaTimeZone.toInstant(endTime);
        }
        if (startTime == null && startTimeUtc != null) {
            startTime = SerbiaTimeZone.toLocalDateTime(startTimeUtc);
        }
        if (endTime == null && endTimeUtc != null) {
            endTime = SerbiaTimeZone.toLocalDateTime(endTimeUtc);
        }
    }

    // ========== GEOSPATIAL HELPER METHODS ==========

    /**
     * Check if booking has an agreed pickup location.
     */
    public boolean hasPickupLocation() {
        return pickupLocation != null && pickupLocation.hasCoordinates();
    }

    /**
     * Check if this booking has a delivery component.
     */
    public boolean hasDelivery() {
        return deliveryDistanceKm != null && deliveryDistanceKm.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Calculate variance between agreed pickup location and actual car location at check-in.
     * 
     * <p><b>DEPRECATED (Phase 2):</b> This method is no longer used in business logic.
     * Kept for backward compatibility only. In Phase 2, we've simplified to trust photos
     * by default (Turo-style). Location variance is no longer calculated or validated.
     * 
     * <p>Use audit trail ({@code check_in_events} table with {@code CAR_LOCATION_DERIVED}
     * event type) for dispute resolution instead of pre-validation.
     * 
     * <p><b>Migration Path:</b> This method will be removed in Phase 3 (12 weeks after V27).
     * See {@code schema_deprecations} table for scheduled removal date.
     * 
     * @return Distance in meters, or null if locations unavailable
     * @deprecated Since Phase 2 - Location variance validation removed (Turo simplification)
     */
    @Deprecated(since = "Phase2", forRemoval = true)
    public Integer calculatePickupLocationVariance() {
        if (pickupLocation == null || !pickupLocation.hasCoordinates()) {
            return null;
        }
        if (carLatitude == null || carLongitude == null) {
            return null;
        }

        GeoPoint actualCarLocation = new GeoPoint(carLatitude, carLongitude);
        double distance = pickupLocation.distanceTo(actualCarLocation);
        return (int) Math.round(distance);
    }
}

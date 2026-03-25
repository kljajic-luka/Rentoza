package org.example.rentoza.booking.checkin.cqrs;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.booking.BookingStatus;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Denormalized read model for check-in status queries.
 * 
 * <h2>CQRS Read Model</h2>
 * <p>This entity represents a pre-computed, denormalized view of check-in status
 * optimized for read performance. It eliminates the need for complex JOIN queries
 * when displaying check-in dashboards.
 * 
 * <h2>Data Synchronization</h2>
 * <p>Updated asynchronously via domain events from the Command Service:
 * <ul>
 *   <li>{@link CheckInDomainEvent.HostCheckInCompleted} → Updates host fields</li>
 *   <li>{@link CheckInDomainEvent.GuestConditionAcknowledged} → Updates guest fields</li>
 *   <li>{@link CheckInDomainEvent.TripStarted} → Updates handshake and trip fields</li>
 *   <li>{@link CheckInDomainEvent.NoShowProcessed} → Updates status</li>
 * </ul>
 * 
 * <h2>Eventual Consistency</h2>
 * <p>This view may be slightly behind the source of truth (Booking entity).
 * For critical operations, the Command Service reads from the Booking entity.
 * For dashboard display, this view provides sub-millisecond response times.
 * 
 * @see CheckInStatusViewSyncListener for synchronization
 * @see CheckInQueryService for queries using this view
 */
@Entity
@Table(name = "checkin_status_view", indexes = {
        @Index(name = "idx_csv_booking_id", columnList = "booking_id", unique = true),
        @Index(name = "idx_csv_session_id", columnList = "session_id"),
        @Index(name = "idx_csv_status", columnList = "status"),
        @Index(name = "idx_csv_host_user", columnList = "host_user_id"),
        @Index(name = "idx_csv_guest_user", columnList = "guest_user_id"),
        @Index(name = "idx_csv_scheduled_start", columnList = "scheduled_start_time"),
        @Index(name = "idx_csv_status_noshow", columnList = "status, no_show_deadline")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckInStatusView implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ========== IDENTIFIERS ==========

    /**
     * Reference to source Booking entity.
     */
    @Column(name = "booking_id", nullable = false, unique = true)
    private Long bookingId;

    /**
     * Check-in session UUID for correlation.
     */
    @Column(name = "session_id", length = 36)
    private UUID sessionId;

    // ========== DENORMALIZED USER DATA ==========

    @Column(name = "host_user_id", nullable = false)
    private Long hostUserId;

    @Column(name = "host_name", length = 100)
    private String hostName;

    @Column(name = "host_phone", length = 20)
    private String hostPhone;

    @Column(name = "guest_user_id", nullable = false)
    private Long guestUserId;

    @Column(name = "guest_name", length = 100)
    private String guestName;

    @Column(name = "guest_phone", length = 20)
    private String guestPhone;

    // ========== DENORMALIZED CAR DATA ==========

    @Column(name = "car_id", nullable = false)
    private Long carId;

    @Column(name = "car_brand", length = 50)
    private String carBrand;

    @Column(name = "car_model", length = 50)
    private String carModel;

    @Column(name = "car_year")
    private Integer carYear;

    @Column(name = "car_image_url", length = 500)
    private String carImageUrl;

    @Column(name = "car_license_plate", length = 20)
    private String carLicensePlate;

    // ========== STATUS ==========

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private BookingStatus status;

    @Column(name = "status_display", length = 100)
    private String statusDisplay;

    // ========== COMPLETION FLAGS ==========

    @Column(name = "host_check_in_complete", nullable = false)
    private boolean hostCheckInComplete;

    @Column(name = "guest_check_in_complete", nullable = false)
    private boolean guestCheckInComplete;

    @Column(name = "handshake_complete", nullable = false)
    private boolean handshakeComplete;

    @Column(name = "trip_started", nullable = false)
    private boolean tripStarted;

    // ========== TIMESTAMPS ==========

    @Column(name = "scheduled_start_time")
    private LocalDateTime scheduledStartTime;

    @Column(name = "check_in_opened_at")
    private Instant checkInOpenedAt;

    @Column(name = "host_completed_at")
    private Instant hostCompletedAt;

    @Column(name = "guest_completed_at")
    private Instant guestCompletedAt;

    @Column(name = "handshake_completed_at")
    private Instant handshakeCompletedAt;

    @Column(name = "trip_started_at")
    private Instant tripStartedAt;

    // ========== HOST CHECK-IN DATA ==========

    @Column(name = "odometer_reading")
    private Integer odometerReading;

    @Column(name = "fuel_level_percent")
    private Integer fuelLevelPercent;

    @Column(name = "photo_count")
    private Integer photoCount;

    @Column(name = "lockbox_available", nullable = false)
    private boolean lockboxAvailable;

    // ========== GEOFENCE DATA ==========

    @Column(name = "geofence_valid")
    private Boolean geofenceValid;

    @Column(name = "geofence_distance_meters")
    private Integer geofenceDistanceMeters;

    @Column(name = "handshake_method", length = 20)
    private String handshakeMethod;  // "REMOTE" or "IN_PERSON"

    // ========== NO-SHOW TRACKING ==========

    @Column(name = "no_show_deadline")
    private LocalDateTime noShowDeadline;

    @Column(name = "no_show_party", length = 10)
    private String noShowParty;  // "HOST" or "GUEST" if no-show occurred

    // ========== SYNC METADATA ==========

    @Column(name = "last_event_id")
    private Long lastEventId;

    @Column(name = "last_sync_at", nullable = false)
    private Instant lastSyncAt;

    @Version
    @Column(name = "version")
    private Long version;

    // ========== COMPUTED FIELDS ==========

    /**
     * Get minutes until no-show deadline.
     * 
     * @return Minutes remaining, or null if not applicable
     */
    @Transient
    public Long getMinutesUntilNoShow() {
        if (noShowDeadline == null) return null;
        
        long minutes = java.time.temporal.ChronoUnit.MINUTES.between(
                LocalDateTime.now(), noShowDeadline);
        return minutes > 0 ? minutes : 0L;
    }

    /**
     * Check if check-in is actionable for a user role.
     * 
     * @param isHost Whether user is the host
     * @return True if user can take action
     */
    @Transient
    public boolean isActionableFor(boolean isHost) {
        if (tripStarted || noShowParty != null) return false;
        
        if (isHost) {
            return status == BookingStatus.CHECK_IN_OPEN && !hostCheckInComplete;
        } else {
            return (status == BookingStatus.CHECK_IN_HOST_COMPLETE && !guestCheckInComplete)
                    || (status == BookingStatus.CHECK_IN_COMPLETE && !handshakeComplete);
        }
    }

    /**
     * Get progress percentage (0-100).
     * 
     * @return Progress percentage
     */
    @Transient
    public int getProgressPercent() {
        if (tripStarted) return 100;
        if (handshakeComplete) return 90;
        if (guestCheckInComplete) return 70;
        if (hostCheckInComplete) return 40;
        if (checkInOpenedAt != null) return 10;
        return 0;
    }

    // ========== FACTORY METHOD ==========

    /**
     * Create a new view from booking data.
     * 
     * <p>Used during initial population or full refresh.
     */
    public static CheckInStatusView fromBookingData(
            Long bookingId,
            UUID sessionId,
            Long hostUserId,
            String hostName,
            String hostPhone,
            Long guestUserId,
            String guestName,
            String guestPhone,
            Long carId,
            String carBrand,
            String carModel,
            Integer carYear,
            String carImageUrl,
            String carLicensePlate,
            BookingStatus status,
            LocalDateTime scheduledStartTime) {
        
        CheckInStatusView view = new CheckInStatusView();
        view.setBookingId(bookingId);
        view.setSessionId(sessionId);
        view.setHostUserId(hostUserId);
        view.setHostName(hostName);
        view.setHostPhone(hostPhone);
        view.setGuestUserId(guestUserId);
        view.setGuestName(guestName);
        view.setGuestPhone(guestPhone);
        view.setCarId(carId);
        view.setCarBrand(carBrand);
        view.setCarModel(carModel);
        view.setCarYear(carYear);
        view.setCarImageUrl(carImageUrl);
        view.setCarLicensePlate(carLicensePlate);
        view.setStatus(status);
        view.setStatusDisplay(getStatusDisplayText(status));
        view.setScheduledStartTime(scheduledStartTime);
        view.setHostCheckInComplete(false);
        view.setGuestCheckInComplete(false);
        view.setHandshakeComplete(false);
        view.setTripStarted(false);
        view.setPhotoCount(0);
        view.setLockboxAvailable(false);
        view.setLastSyncAt(Instant.now());
        return view;
    }

    private static String getStatusDisplayText(BookingStatus status) {
        return switch (status) {
            case ACTIVE -> "Odobreno - čeka prijem";
            case CHECK_IN_OPEN -> "Prijem otvoren";
            case CHECK_IN_HOST_COMPLETE -> "Domaćin završio prijem";
            case CHECK_IN_COMPLETE -> "Prijem završen - čeka rukovanje";
            case IN_TRIP -> "Putovanje u toku";
            case NO_SHOW_HOST -> "Domaćin se nije pojavio";
            case NO_SHOW_GUEST -> "Gost se nije pojavio";
            default -> status.name();
        };
    }
}

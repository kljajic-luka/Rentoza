package org.example.rentoza.booking.checkin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.booking.BookingStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response DTO for check-in status.
 * 
 * <p>Provides complete view of check-in progress for both host and guest.
 * 
 * <h3>Phase 3: Extended Fields for API Optimization</h3>
 * <p>Includes additional fields for sparse fieldset support and ETag generation:
 * <ul>
 *   <li>Handshake confirmation flags (hostConfirmedHandshake, guestConfirmedHandshake)</li>
 *   <li>Action availability flags (canHostComplete, canGuestAcknowledge, canStartTrip)</li>
 *   <li>Computed fields (hostCheckInPhotoCount, handoffType, geofenceStatus)</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckInStatusDTO {

    private Long bookingId;
    private String checkInSessionId;
    private BookingStatus status;

    // Phase completion
    private boolean hostCheckInComplete;
    private boolean guestCheckInComplete;
    private boolean handshakeReady;

    // Extended phase flags (aliases for CheckInResponseOptimizer compatibility)
    /** Alias for guestCheckInComplete - guest has acknowledged vehicle condition */
    private boolean guestConditionAcknowledged;
    /** True when both parties have confirmed handshake and trip has started */
    private boolean handshakeComplete;
    /** Host has confirmed their side of the handshake */
    private boolean hostConfirmedHandshake;
    /** Guest has confirmed their side of the handshake */
    private boolean guestConfirmedHandshake;

    // Timestamps
    private LocalDateTime checkInOpenedAt;
    private LocalDateTime hostCompletedAt;
    private LocalDateTime guestCompletedAt;
    private LocalDateTime handshakeCompletedAt;
    /** Last update timestamp for ETag generation */
    private LocalDateTime lastUpdated;

    // Host data (visible to guest after host completes)
    private List<CheckInPhotoDTO> vehiclePhotos;
    private Integer odometerReading;
    private Integer fuelLevelPercent;

    // Extended host data aliases (for sparse fieldset compatibility)
    /** Count of uploaded check-in photos */
    private Integer hostCheckInPhotoCount;
    /** Alias for odometerReading */
    private Integer odometerStart;
    /** Alias for fuelLevelPercent */
    private Integer fuelLevelStart;

    // Remote handoff
    private boolean lockboxAvailable;
    private boolean geofenceValid;
    private Integer geofenceDistanceMeters;
    /** "REMOTE" (lockbox) or "IN_PERSON" */
    private String handoffType;
    /** "VALID", "INVALID", or "NOT_CHECKED" */
    private String geofenceStatus;

    // Deadlines
    private LocalDateTime tripStartScheduled;
    private LocalDateTime noShowDeadline;
    private Long minutesUntilNoShow;

    // Role-specific flags (explicitly named for Jackson serialization)
    @JsonProperty("host")
    private boolean isHost;
    @JsonProperty("guest")
    private boolean isGuest;

    // Action availability flags (computed from status)
    /** Host can complete check-in (status is CHECK_IN_OPEN and has required photos) */
    private boolean canHostComplete;
    /** Guest can acknowledge condition (status is CHECK_IN_HOST_COMPLETE) */
    private boolean canGuestAcknowledge;
    /** Either party can confirm handshake to start trip (status is CHECK_IN_COMPLETE) */
    private boolean canStartTrip;

    // Car info (for display)
    private CarSummaryDTO car;

    // =========================================================================
    // Phase 4A: Check-in Timing
    // =========================================================================
    /** Whether timing validation is currently blocking check-in (too early) */
    private Boolean timingBlocked;
    /** User-facing message when timing is blocked (Serbian) */
    private String timingBlockedMessage;
    /** Minutes until check-in is allowed (only set when timingBlocked=true) */
    private Long minutesUntilCheckInAllowed;
    /** Maximum hours before trip start that check-in is allowed */
    private Integer maxEarlyCheckInHours;

    // =========================================================================
    // Phase 4B: License Verification
    // =========================================================================
    /** Whether in-person license verification is required (no lockbox = in-person handoff) */
    private Boolean licenseVerificationRequired;
    /** Whether license has been verified in person by host */
    private Boolean licenseVerifiedInPerson;
    /** When license was verified in person (ISO string for frontend) */
    private String licenseVerifiedInPersonAt;

    // =========================================================================
    // Pickup Location (Phase 4: Pickup Location Display Feature)
    // =========================================================================
    /** Pickup location latitude (from booking.pickupLocation or car.locationGeoPoint fallback) */
    private Double pickupLatitude;
    /** Pickup location longitude */
    private Double pickupLongitude;
    /** Pickup address (street + number) */
    private String pickupAddress;
    /** Pickup city */
    private String pickupCity;
    /** Pickup zip code */
    private String pickupZipCode;
    /** Variance from car's home location in meters (null if pickup == car location) */
    private Integer pickupLocationVarianceMeters;
    /** Variance status for UI badging: NONE, WARNING, BLOCKING */
    private String varianceStatus;
    /** True if pickup location is an estimate (fell back to car home location) */
    @JsonProperty("estimatedLocation")
    private boolean isEstimatedLocation;
    /** Source of estimate: "CAR_HOME_LOCATION" when fallback used, null otherwise */
    private String estimatedLocationSource;

    /**
     * Get photos list (alias for vehiclePhotos for CheckInResponseOptimizer compatibility).
     */
    public List<CheckInPhotoDTO> getPhotos() {
        return vehiclePhotos;
    }

    /**
     * Set photos list (alias for vehiclePhotos for CheckInResponseOptimizer compatibility).
     */
    public void setPhotos(List<CheckInPhotoDTO> photos) {
        this.vehiclePhotos = photos;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CarSummaryDTO {
        private Long id;
        private String brand;
        private String model;
        private Integer year;
        private String imageUrl;
    }
}

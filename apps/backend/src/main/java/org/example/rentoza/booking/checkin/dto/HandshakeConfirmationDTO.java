package org.example.rentoza.booking.checkin.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for handshake confirmation.
 * 
 * <p>Both host and guest must confirm to start the trip.
 * 
 * <p><b>P0 SECURITY:</b> Anti-spoofing fields added for GPS fraud detection.
 * The {@code isMockLocation} and {@code horizontalAccuracy} fields are provided
 * by the mobile OS and must be forwarded by the frontend.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HandshakeConfirmationDTO {

    @NotNull(message = "ID rezervacije je obavezan")
    private Long bookingId;

    @NotNull(message = "Potvrda je obavezna")
    private Boolean confirmed;

    /**
     * For in-person handoff: Host verifies guest's physical ID matches profile.
     */
    private Boolean hostVerifiedPhysicalId;

    /**
     * Guest's current location (for geofence validation on remote handoff).
     */
    private Double latitude;
    private Double longitude;

    // ========== P0: ANTI-SPOOFING FIELDS ==========

    /**
     * Whether the device reports mock/fake location.
     * Android: android.location.Location.isFromMockProvider()
     * iOS: Always false (iOS blocks mock locations at OS level)
     * 
     * <p>If true, handshake is BLOCKED and fraud attempt is flagged.
     */
    private Boolean isMockLocation;

    /**
     * GPS horizontal accuracy in meters.
     * Lower values = more accurate. Values > 100m suggest VPN/proxy/indoor location.
     * Android: Location.getAccuracy()
     * iOS: CLLocation.horizontalAccuracy
     */
    private Double horizontalAccuracy;

    /**
     * Device platform identifier for platform-specific validation.
     * Expected values: "ANDROID", "IOS", "WEB"
     */
    private String platform;

    /**
     * Optional device fingerprint for fraud detection.
     */
    private String deviceFingerprint;
}

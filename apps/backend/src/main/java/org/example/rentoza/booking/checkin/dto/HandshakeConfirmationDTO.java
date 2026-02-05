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

    /**
     * Optional device fingerprint for fraud detection.
     */
    private String deviceFingerprint;
}

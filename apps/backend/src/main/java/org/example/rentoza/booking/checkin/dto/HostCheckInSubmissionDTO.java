package org.example.rentoza.booking.checkin.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTO for host check-in completion.
 * 
 * <p>Contains odometer/fuel readings and references to uploaded photos.
 * Photos must be uploaded separately via the photo upload endpoint.
 * 
 * <h2>Phase 2 Simplification (Turo-Style)</h2>
 * <p>Removed car location submission - car location is now <b>derived from
 * the first valid photo's EXIF GPS</b> during {@code CheckInService.completeHostCheckIn()}.
 * 
 * <p>This simplifies the flow:
 * <ul>
 *   <li>Host uploads 8 photos (EXIF GPS auto-captured by device camera)</li>
 *   <li>Host enters odometer/fuel (no GPS permission required)</li>
 *   <li>System derives car location from photo #1 with valid EXIF</li>
 *   <li>Location variance check removed (Turo doesn't do this)</li>
 * </ul>
 * 
 * <p>Host GPS is now <b>optional</b> (audit trail only, not required for submission).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HostCheckInSubmissionDTO {

    @NotNull(message = "ID rezervacije je obavezan")
    private Long bookingId;

    @NotNull(message = "Kilometraža je obavezna")
    @Min(value = 0, message = "Kilometraža ne može biti negativna")
    private Integer odometerReading;

    @NotNull(message = "Nivo goriva je obavezan")
    @Min(value = 0, message = "Nivo goriva mora biti između 0 i 100")
    @Max(value = 100, message = "Nivo goriva mora biti između 0 i 100")
    private Integer fuelLevelPercent;

    @Size(min = 8, max = 15, message = "Potrebno je minimum 8 fotografija, maksimum 15")
    private List<Long> photoIds;

    // Optional: Remote handoff (lockbox)
    @Size(max = 10, message = "Šifra lokota može imati maksimum 10 karaktera")
    private String lockboxCode;

    // PHASE 2 REMOVED: carLatitude, carLongitude
    // Car location is now derived from first photo's EXIF GPS
    // No longer submitted by host (simplifies UX, fixes orphaned fields)

    /**
     * Host's GPS location at submission (optional, for audit trail only).
     * <p>Used for dispute resolution - shows where host was standing when submitting.
     * NOT used for location validation (that comes from photo EXIF GPS).
     */
    private Double hostLatitude;

    /**
     * Host's GPS longitude at submission (optional, for audit trail only).
     */
    private Double hostLongitude;
}

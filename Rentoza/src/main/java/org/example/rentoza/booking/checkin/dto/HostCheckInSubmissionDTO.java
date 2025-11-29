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

    // Car location for geofence
    private Double carLatitude;
    private Double carLongitude;

    // Host location
    private Double hostLatitude;
    private Double hostLongitude;
}

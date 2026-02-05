package org.example.rentoza.booking.checkin.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for marking a damage hotspot on a vehicle photo.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HotspotMarkingDTO {

    @NotNull(message = "ID fotografije je obavezan")
    private Long photoId;

    @NotNull(message = "X pozicija je obavezna")
    @DecimalMin(value = "0.0", message = "X pozicija mora biti između 0 i 1")
    @DecimalMax(value = "1.0", message = "X pozicija mora biti između 0 i 1")
    private Double xPercent;

    @NotNull(message = "Y pozicija je obavezna")
    @DecimalMin(value = "0.0", message = "Y pozicija mora biti između 0 i 1")
    @DecimalMax(value = "1.0", message = "Y pozicija mora biti između 0 i 1")
    private Double yPercent;

    @Size(max = 500, message = "Opis može imati maksimum 500 karaktera")
    private String description;

    /**
     * Severity of the damage (for UI display).
     */
    private DamageSeverity severity;

    public enum DamageSeverity {
        MINOR,      // Scratch, small dent
        MODERATE,   // Visible damage, may need repair
        SEVERE      // Significant damage
    }
}

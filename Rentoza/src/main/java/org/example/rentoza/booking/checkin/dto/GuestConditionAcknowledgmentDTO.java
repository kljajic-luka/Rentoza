package org.example.rentoza.booking.checkin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTO for guest condition acknowledgment.
 * 
 * <p>Guest confirms they have reviewed the vehicle photos and 
 * acknowledge the current condition. Can optionally mark damage hotspots.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestConditionAcknowledgmentDTO {

    @NotNull(message = "ID rezervacije je obavezan")
    private Long bookingId;

    @NotNull(message = "Potvrda stanja vozila je obavezna")
    private Boolean conditionAccepted;

    /**
     * Optional damage notes/hotspots marked by guest.
     */
    @Valid
    @Size(max = 10, message = "Maksimalno 10 oznaka štete")
    private List<HotspotMarkingDTO> hotspots;

    @NotNull(message = "Lokacija gosta je obavezna")
    private Double guestLatitude;

    @NotNull(message = "Lokacija gosta je obavezna")
    private Double guestLongitude;

    /**
     * Optional comment from guest about vehicle condition.
     */
    @Size(max = 1000, message = "Komentar može imati maksimum 1000 karaktera")
    private String conditionComment;
}

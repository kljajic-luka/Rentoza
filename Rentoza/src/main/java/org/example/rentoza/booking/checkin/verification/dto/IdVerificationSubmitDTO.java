package org.example.rentoza.booking.checkin.verification.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.booking.checkin.CheckInIdVerification.DocumentType;

/**
 * DTO for submitting ID verification data.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdVerificationSubmitDTO {
    
    @NotNull(message = "ID rezervacije je obavezan")
    private Long bookingId;
    
    @NotNull(message = "Tip dokumenta je obavezan")
    private DocumentType documentType;
    
    /**
     * Document issuing country (ISO 3166-1 alpha-3, e.g., "SRB").
     */
    private String documentCountry;
}



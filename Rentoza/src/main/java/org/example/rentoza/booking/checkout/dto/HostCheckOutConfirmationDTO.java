package org.example.rentoza.booking.checkout.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO for host checkout confirmation.
 * 
 * <p>Submitted by host when confirming vehicle return:
 * <ul>
 *   <li>Condition acceptance or damage report</li>
 *   <li>Damage description and photos (if any)</li>
 *   <li>Estimated damage cost</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HostCheckOutConfirmationDTO {
    
    @NotNull(message = "ID rezervacije je obavezan")
    private Long bookingId;
    
    /**
     * True if host accepts vehicle condition (no new damage).
     */
    @NotNull(message = "Potvrda stanja je obavezna")
    private Boolean conditionAccepted;
    
    /**
     * True if host reports new damage.
     */
    @Builder.Default
    private Boolean newDamageReported = false;
    
    /**
     * Description of new damage found.
     */
    private String damageDescription;
    
    /**
     * Estimated damage cost in RSD.
     */
    private BigDecimal estimatedDamageCostRsd;
    
    /**
     * List of photo IDs documenting the damage.
     */
    private List<Long> damagePhotoIds;
    
    /**
     * Host's GPS latitude at checkout confirmation.
     */
    private Double hostLatitude;
    
    /**
     * Host's GPS longitude at checkout confirmation.
     */
    private Double hostLongitude;
    
    /**
     * Any additional notes from host.
     */
    private String notes;
}



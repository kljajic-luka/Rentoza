package org.example.rentoza.booking.checkin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
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
 * 
 * <p><b>VAL-004 Enhancement:</b> Supports damage dispute flow where guest
 * can report undisclosed pre-existing damage instead of accepting condition.
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
    
    // ========== VAL-004: CHECK-IN DISPUTE FIELDS ==========
    
    /**
     * If true, guest is disputing the condition due to undisclosed pre-existing damage.
     * When set, conditionAccepted should be false.
     */
    private Boolean disputePreExistingDamage;
    
    /**
     * Required description when disputing pre-existing damage.
     * Must explain what damage was found that wasn't disclosed.
     */
    @Size(max = 2000, message = "Opis prijave može imati maksimum 2000 karaktera")
    private String damageDisputeDescription;
    
    /**
     * Photo IDs from check-in that show the disputed damage.
     * These help admin verify the guest's claim.
     */
    @Size(max = 10, message = "Maksimalno 10 fotografija za prijavu")
    private List<Long> disputedPhotoIds;
    
    /**
     * Type of dispute being raised.
     * Defaults to PRE_EXISTING_DAMAGE if not specified.
     */
    private String disputeType;
    
    // ========== VALIDATION ==========
    
    /**
     * Validates dispute consistency:
     * - If disputing, description is required
     * - If disputing, conditionAccepted must be false
     */
    @AssertTrue(message = "Prijava štete zahteva opis i odbijanje stanja vozila")
    public boolean isDisputeValid() {
        if (Boolean.TRUE.equals(disputePreExistingDamage)) {
            // Disputing requires description and rejection of condition
            boolean hasDescription = damageDisputeDescription != null && 
                                     !damageDisputeDescription.trim().isEmpty();
            boolean conditionRejected = !Boolean.TRUE.equals(conditionAccepted);
            return hasDescription && conditionRejected;
        }
        return true; // Not disputing, no additional validation
    }
}

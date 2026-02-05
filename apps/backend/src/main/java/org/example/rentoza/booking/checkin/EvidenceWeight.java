package org.example.rentoza.booking.checkin;

/**
 * Evidence weight classification for check-in/checkout photos.
 * 
 * <p><b>Phase 4E Safety Improvement:</b> Photos uploaded after the deadline
 * have reduced evidentiary weight in dispute resolution.
 * 
 * <h2>Use in Disputes</h2>
 * <ul>
 *   <li><b>PRIMARY:</b> Full evidentiary weight - uploaded on time, can be used
 *       as primary evidence in damage claims and disputes.</li>
 *   <li><b>SECONDARY:</b> Reduced evidentiary weight - uploaded late, may be
 *       questioned during disputes, requires additional corroborating evidence.</li>
 * </ul>
 * 
 * <h2>Deadlines</h2>
 * <p>Configured via application properties:
 * <ul>
 *   <li>{@code app.checkin.photo-upload-deadline-hours} - Check-in photo deadline (default: 24h)</li>
 *   <li>{@code app.checkout.photo-upload-deadline-hours} - Checkout photo deadline (default: 24h)</li>
 * </ul>
 * 
 * @since Phase 4E
 */
public enum EvidenceWeight {
    
    /**
     * Primary evidence - uploaded within deadline.
     * 
     * <p>Full evidentiary weight in disputes. Can be used as standalone
     * evidence for damage claims.
     */
    PRIMARY("Primarna evidencija", "Fotografija je otpremljena na vreme"),
    
    /**
     * Secondary evidence - uploaded after deadline.
     * 
     * <p>Reduced evidentiary weight in disputes. May require additional
     * corroborating evidence (other photos, witness statements, etc.).
     * 
     * <p><b>Note to dispute reviewers:</b> Late uploads may indicate:
     * <ul>
     *   <li>Legitimate network issues or oversight</li>
     *   <li>Attempt to add evidence after damage was discovered</li>
     *   <li>Retroactive documentation (less reliable)</li>
     * </ul>
     */
    SECONDARY("Sekundarna evidencija", "Fotografija je otpremljena nakon isteka roka");
    
    private final String displayNameSr;
    private final String descriptionSr;
    
    EvidenceWeight(String displayNameSr, String descriptionSr) {
        this.displayNameSr = displayNameSr;
        this.descriptionSr = descriptionSr;
    }
    
    /**
     * Get Serbian display name for UI.
     */
    public String getDisplayNameSr() {
        return displayNameSr;
    }
    
    /**
     * Get Serbian description for tooltips/explanations.
     */
    public String getDescriptionSr() {
        return descriptionSr;
    }
    
    /**
     * Check if this is primary (full-weight) evidence.
     */
    public boolean isPrimary() {
        return this == PRIMARY;
    }
    
    /**
     * Check if this is secondary (reduced-weight) evidence.
     */
    public boolean isSecondary() {
        return this == SECONDARY;
    }
}

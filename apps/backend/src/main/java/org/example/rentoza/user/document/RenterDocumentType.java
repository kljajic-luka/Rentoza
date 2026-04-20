package org.example.rentoza.user.document;

/**
 * Document types for renter identity verification.
 * 
 * <p>Core documents (REQUIRED for booking):
 * <ul>
 *   <li>Driver's license front - photo and name</li>
 *   <li>Driver's license back - categories and expiry</li>
 *   <li>Selfie - for liveness check and face matching</li>
 * </ul>
 * 
 * <p>Optional documents (enhanced verification):
 * <ul>
 *   <li>National ID card - for additional verification</li>
 *   <li>Passport - for international renters</li>
 * </ul>
 */
public enum RenterDocumentType {
    
    /**
     * Front of driver's license showing photo and name.
     * REQUIRED for booking eligibility.
     */
    DRIVERS_LICENSE_FRONT("Vozačka dozvola - prednja strana", true),
    
    /**
     * Back of driver's license showing categories and expiry.
     * REQUIRED for booking eligibility.
     */
    DRIVERS_LICENSE_BACK("Vozačka dozvola - zadnja strana", true),
    
    /**
     * Selfie for liveness detection and face matching.
     * REQUIRED - selfie upload UI is now implemented; needed for face matching.
     */
    SELFIE("Selfie fotografija", true),
    
    /**
     * Front of national ID card.
     * OPTIONAL - for additional verification.
     */
    ID_CARD_FRONT("Lična karta - prednja strana", false),
    
    /**
     * Back of national ID card.
     * OPTIONAL - for additional verification.
     */
    ID_CARD_BACK("Lična karta - zadnja strana", false),
    
    /**
     * Passport photo page.
     * OPTIONAL - for international renters.
     */
    PASSPORT("Pasoš", false);
    
    private final String serbianName;
    private final boolean required;
    
    RenterDocumentType(String serbianName, boolean required) {
        this.serbianName = serbianName;
        this.required = required;
    }
    
    /**
     * Serbian display name for UI.
     */
    public String getSerbianName() {
        return serbianName;
    }
    
    /**
     * Whether this document type is required for verification.
     */
    public boolean isRequired() {
        return required;
    }
    
    /**
     * Whether this is a driver's license document.
     */
    public boolean isDriversLicense() {
        return this == DRIVERS_LICENSE_FRONT || this == DRIVERS_LICENSE_BACK;
    }
    
    /**
     * Whether OCR extraction should be performed on this document.
     */
    public boolean requiresOcr() {
        return this == DRIVERS_LICENSE_FRONT || this == DRIVERS_LICENSE_BACK 
            || this == ID_CARD_FRONT || this == PASSPORT;
    }
    
    /**
     * Whether liveness detection should be performed on this document.
     */
    public boolean requiresLiveness() {
        return this == SELFIE;
    }
    
    /**
     * Whether face matching should be performed against selfie.
     */
    public boolean requiresFaceMatch() {
        return this == DRIVERS_LICENSE_FRONT || this == ID_CARD_FRONT || this == PASSPORT;
    }
}

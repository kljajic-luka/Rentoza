package org.example.rentoza.car;

/**
 * Document types required for Serbian car rental compliance.
 * 
 * <p>Serbian Law on Tourism requires 4 document types for rent-a-car vehicles.
 */
public enum DocumentType {
    /**
     * Saobraćajna dozvola (Vehicle Registration).
     * Proves owner's legal right to rent the vehicle.
     */
    REGISTRATION("Saobraćajna dozvola", true),
    
    /**
     * Tehnički pregled (Technical Inspection Certificate).
     * CRITICAL: Must be renewed every 6 months for rent-a-car vehicles.
     */
    TECHNICAL_INSPECTION("Tehnički pregled", true),
    
    /**
     * Polisa Autoodgovornosti (Mandatory Liability Insurance).
     * Third-party damage coverage required by law.
     */
    LIABILITY_INSURANCE("Polisa Autoodgovornosti", true),
    
    /**
     * Ovlašćenje (Power of Attorney / Authorization).
     * Required only if car owner ≠ document uploader.
     */
    AUTHORIZATION("Ovlašćenje", false);
    
    private final String serbianName;
    private final boolean required;
    
    DocumentType(String serbianName, boolean required) {
        this.serbianName = serbianName;
        this.required = required;
    }
    
    public String getSerbianName() { return serbianName; }
    public boolean isRequired() { return required; }
}

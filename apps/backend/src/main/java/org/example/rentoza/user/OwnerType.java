package org.example.rentoza.user;

/**
 * Owner type classification for Serbian market compliance.
 * 
 * <p>Determines required verification fields:
 * <ul>
 *   <li>INDIVIDUAL - Requires JMBG (Personal ID)</li>
 *   <li>LEGAL_ENTITY - Requires PIB (Tax ID)</li>
 * </ul>
 */
public enum OwnerType {
    /**
     * Individual person (fizička lica).
     * Requires JMBG (Jedinstveni matični broj građana - Personal ID).
     */
    INDIVIDUAL,
    
    /**
     * Legal entity (pravno lice).
     * Examples: Preduzetnik (sole proprietor), DOO (LLC).
     * Requires PIB (Poreski identifikacioni broj - Tax ID).
     */
    LEGAL_ENTITY
}

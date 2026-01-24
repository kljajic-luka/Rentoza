package org.example.rentoza.user.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.example.rentoza.user.OwnerType;

import java.time.LocalDate;

/**
 * DTO for completing user profile after Google OAuth registration.
 * 
 * <p>This DTO supports dynamic field requirements based on role:
 * <ul>
 *   <li><b>USER (Renter):</b> phone, dateOfBirth, driverLicenseNumber, driverLicenseExpiryDate, driverLicenseCountry</li>
 *   <li><b>OWNER (Individual):</b> phone, dateOfBirth, jmbg, bankAccountNumber (optional), agreements</li>
 *   <li><b>OWNER (Legal Entity):</b> phone, dateOfBirth, pib, bankAccountNumber (required), agreements</li>
 * </ul>
 * 
 * <p><b>Validation:</b> Field requirements are validated at service layer based on user's role.
 * @see org.example.rentoza.user.ProfileCompletionService
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CompleteProfileRequestDTO {

    // ========== COMMON FIELDS (All Roles) ==========

    /**
     * Phone number - required for all roles.
     * Used for booking confirmations and emergency contact.
     */
    @NotBlank(message = "Broj telefona je obavezan")
    @Pattern(regexp = "^[0-9]{8,15}$", message = "Telefon mora sadržati 8-15 cifara")
    private String phone;

    /**
     * Date of birth - required for all roles.
     * Must result in age >= 21 years.
     */
    @NotNull(message = "Datum rođenja je obavezan")
    @Past(message = "Datum rođenja mora biti u prošlosti")
    private LocalDate dateOfBirth;

    // ========== USER (RENTER) SPECIFIC FIELDS ==========

    /**
     * Driver's license number - required for USER role.
     * Will be stored encrypted.
     */
    @Size(min = 1, max = 50, message = "Broj vozačke dozvole mora biti između 1 i 50 karaktera")
    private String driverLicenseNumber;

    /**
     * Driver's license expiry date - required for USER role.
     * Must be a future date (license must be valid).
     */
    private LocalDate driverLicenseExpiryDate;

    /**
     * Country that issued the driver's license (ISO 3166-1 alpha-3).
     * Default: "SRB" (Serbia).
     * Examples: "SRB", "HRV", "DEU", "AUT"
     */
    @Size(min = 3, max = 3, message = "Država mora biti u ISO 3166-1 alpha-3 formatu (3 slova)")
    private String driverLicenseCountry = "SRB";

    // ========== OWNER SPECIFIC FIELDS ==========

    /**
     * Owner type - required for OWNER role.
     * INDIVIDUAL requires JMBG, LEGAL_ENTITY requires PIB.
     */
    private OwnerType ownerType;

    /**
     * Serbian personal ID (JMBG) - 13 digits.
     * Required for OWNER with ownerType = INDIVIDUAL.
     * Will be stored encrypted with hash for uniqueness check.
     */
    @Pattern(regexp = "^[0-9]{13}$", message = "JMBG mora sadržati tačno 13 cifara")
    private String jmbg;

    /**
     * Serbian company tax ID (PIB) - 9 digits.
     * Required for OWNER with ownerType = LEGAL_ENTITY.
     * Will be stored encrypted with hash for uniqueness check.
     */
    @Pattern(regexp = "^[0-9]{9}$", message = "PIB mora sadržati tačno 9 cifara")
    private String pib;

    /**
     * Serbian IBAN bank account number.
     * Format: RS followed by 22 digits.
     * Required for LEGAL_ENTITY, recommended (optional) for INDIVIDUAL.
     */
    @Pattern(regexp = "^RS[0-9]{22}$", message = "Neispravan IBAN format. Mora početi sa RS i imati 22 cifre")
    private String bankAccountNumber;

    // ========== OWNER AGREEMENT FIELDS ==========

    /**
     * Agreement to host terms and conditions.
     * Required for OWNER role.
     */
    private Boolean agreesToHostAgreement;

    /**
     * Confirmation that vehicle(s) have valid insurance.
     * Required for OWNER role.
     */
    private Boolean confirmsVehicleInsurance;

    /**
     * Confirmation that vehicle(s) are registered to the owner.
     * Required for OWNER role.
     */
    private Boolean confirmsVehicleRegistration;
}

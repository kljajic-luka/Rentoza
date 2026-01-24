package org.example.rentoza.user.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.example.rentoza.user.OwnerType;
import java.time.LocalDate;

/**
 * DTO for completing Google OAuth registration.
 * 
 * <p>Google provides email, firstName, and sometimes lastName.
 * This DTO collects the missing required information:
 * <ul>
 *   <li>Phone (required - Google does not provide)</li>
 *   <li>Date of birth (required for age verification)</li>
 *   <li>Last name (if was placeholder)</li>
 *   <li><b>USER (Renter):</b> driverLicenseNumber, driverLicenseExpiryDate, driverLicenseCountry</li>
 *   <li><b>OWNER:</b> ownerType, JMBG/PIB, bankAccountNumber, agreements</li>
 * </ul>
 */
@Getter
@Setter
public class GoogleOAuthCompletionDTO {

    // Last name (only if was GooglePlaceholder)
    @Size(min = 3, max = 50, message = "Last name must be between 3 and 50 characters")
    private String lastName;

    // Phone (required - Google does not provide)
    @NotBlank(message = "Phone is required")
    @Pattern(regexp = "^[0-9]{8,15}$", message = "Phone must contain 8-15 digits")
    private String phone;

    // Date of birth (required - Google does not provide)
    @NotNull(message = "Date of birth is required")
    @Past(message = "Date of birth must be in the past")
    private LocalDate dateOfBirth;

    @NotNull(message = "Age confirmation is required")
    @AssertTrue(message = "You must be at least 21 years old")
    private Boolean confirmsAgeEligibility;

    // ═══════════════════════════════════════════════════════════════════════════
    // USER (Renter) specific fields - required for USER role
    // ═══════════════════════════════════════════════════════════════════════════
    
    /** Driver license number (5-20 alphanumeric) - Required for USER role */
    @Size(min = 5, max = 20, message = "Driver license number must be 5-20 characters")
    @Pattern(regexp = "^[A-Za-z0-9]{5,20}$", message = "Driver license number must be alphanumeric")
    private String driverLicenseNumber;

    /** Driver license expiry date - Required for USER role */
    @Future(message = "Driver license must not be expired")
    private LocalDate driverLicenseExpiryDate;

    /** Driver license issuing country code (2-3 chars, e.g., RS, HR) - Required for USER role */
    @Size(min = 2, max = 3, message = "Country code must be 2-3 characters")
    @Pattern(regexp = "^[A-Za-z]{2,3}$", message = "Country code must be letters only")
    private String driverLicenseCountry;

    // ═══════════════════════════════════════════════════════════════════════════
    // Owner-specific fields (only for owner registration)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private OwnerType ownerType;  // May be null for user registrations

    private String jmbg;    // Conditional: required if ownerType = INDIVIDUAL
    private String pib;     // Conditional: required if ownerType = LEGAL_ENTITY
    private String bankAccountNumber;

    // Agreement checkboxes (only for owner registration)
    private Boolean agreesToHostAgreement;
    private Boolean confirmsVehicleInsurance;
    private Boolean confirmsVehicleRegistration;
}

package org.example.rentoza.user.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.example.rentoza.user.OwnerType;
import java.time.LocalDate;

/**
 * DTO for owner registration.
 * 
 * <p>Includes all user fields plus owner-specific information:
 * <ul>
 *   <li>Owner type (INDIVIDUAL or LEGAL_ENTITY)</li>
 *   <li>JMBG (for individuals) or PIB (for legal entities)</li>
 *   <li>Bank account number</li>
 *   <li>Agreement checkboxes</li>
 * </ul>
 */
@Getter
@Setter
public class OwnerRegistrationDTO {

    // Basic info (same as USER)
    @NotBlank(message = "First name is required")
    @Size(min = 3, max = 50, message = "First name must be between 3 and 50 characters")
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(min = 3, max = 50, message = "Last name must be between 3 and 50 characters")
    private String lastName;

    @Email(message = "Invalid email format")
    @NotBlank(message = "Email is required")
    private String email;

    @NotBlank(message = "Phone is required")
    @Pattern(regexp = "^[0-9]{8,15}$", message = "Phone must contain 8-15 digits")
    private String phone;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    @Pattern(
            regexp = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d).+$",
            message = "Password must contain uppercase, lowercase, and number"
    )
    private String password;

    // Age verification (same as USER)
    @NotNull(message = "Date of birth is required")
    @Past(message = "Date of birth must be in the past")
    private LocalDate dateOfBirth;

    @NotNull(message = "Age confirmation is required")
    @AssertTrue(message = "You must be at least 21 years old")
    private Boolean confirmsAgeEligibility;

    // Owner-specific fields
    @NotNull(message = "Owner type is required")
    private OwnerType ownerType;

    @Pattern(regexp = "^[0-9]{13}$", message = "JMBG must be exactly 13 digits")
    private String jmbg;  // Required if ownerType = INDIVIDUAL

    @Pattern(regexp = "^[0-9]{9}$", message = "PIB must be exactly 9 digits")
    private String pib;   // Required if ownerType = LEGAL_ENTITY

    @Pattern(regexp = "^RS[0-9]{22}$", message = "Invalid IBAN format for Serbia")
    private String bankAccountNumber;  // Optional for INDIVIDUAL, required for LEGAL_ENTITY

    // Agreement checkboxes
    @NotNull(message = "You must agree to the host agreement")
    @AssertTrue(message = "You must agree to the host agreement")
    private Boolean agreesToHostAgreement;

    @NotNull(message = "You must confirm vehicle insurance")
    @AssertTrue(message = "You must confirm vehicle insurance")
    private Boolean confirmsVehicleInsurance;

    @NotNull(message = "You must confirm vehicle registration")
    @AssertTrue(message = "You must confirm vehicle is registered in your name")
    private Boolean confirmsVehicleRegistration;
}

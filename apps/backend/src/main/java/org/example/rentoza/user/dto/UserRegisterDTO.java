package org.example.rentoza.user.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;

/**
 * DTO for enhanced user registration.
 * 
 * <p>Adds required phone, dateOfBirth, and age confirmation to the registration flow.
 */
@Getter
@Setter
public class UserRegisterDTO {

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
    @Size(min = 8, message = "Password must be at least 8 characters long")
    @Pattern(
            regexp = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d).+$",
            message = "Password must contain uppercase, lowercase, and number"
    )
    private String password;

    // NEW FIELDS for enhanced registration
    @NotNull(message = "Date of birth is required")
    @Past(message = "Date of birth must be in the past")
    private LocalDate dateOfBirth;

    @NotNull(message = "Age confirmation is required")
    @AssertTrue(message = "You must be at least 21 years old")
    private Boolean confirmsAgeEligibility;

    // For backward compatibility with existing /api/auth/register endpoint
    private String role;
}

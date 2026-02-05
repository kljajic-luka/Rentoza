package org.example.rentoza.user.dto;

import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDate;

/**
 * DTO for secure partial profile updates.
 * Only contains fields that users are allowed to update directly.
 * Sensitive fields (firstName, lastName, email, role) are NOT included
 * to enforce identity integrity and trust-first security model.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProfileRequestDTO {

    /**
     * Phone number (8-15 digits only).
     * Must be unique across all users.
     */
    @Pattern(regexp = "^[0-9]{8,15}$", message = "Phone must contain 8-15 digits")
    private String phone;

    /**
     * Avatar URL (max 500 characters).
     * For now, accepts any URL string.
     * Future: implement secure image upload service.
     */
    @Size(max = 500, message = "Avatar URL must be maximum 500 characters")
    private String avatarUrl;

    /**
     * Short bio or description (max 300 characters).
     * Displayed on user profile page.
     */
    @Size(max = 300, message = "Bio must be maximum 300 characters")
    private String bio;

    /**
     * Last name can only be changed for Google-provisioned placeholder users.
     */
    @Size(min = 3, max = 50, message = "Last name must be between 3 and 50 characters")
    private String lastName;
    
    /**
     * Date of birth for age verification.
     * RULES:
     *   - Can only be set if not already verified via license OCR
     *   - Must be in the past
     *   - User must be at least 18 years old
     *   - Once verified via license, cannot be changed by user
     */
    @Past(message = "Date of birth must be in the past")
    private LocalDate dateOfBirth;

    /**
     * Future: notification preferences as JSON or boolean flags
     * Example: emailNotifications, smsNotifications, pushNotifications
     */
    // private Boolean emailNotifications;
    // private Boolean smsNotifications;
}

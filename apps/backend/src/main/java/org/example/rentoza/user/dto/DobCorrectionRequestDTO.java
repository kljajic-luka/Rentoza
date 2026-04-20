package org.example.rentoza.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * SECURITY (M-9): DTO for requesting a date-of-birth correction.
 * Used when a user has a verified DOB (from license OCR) that is incorrect.
 * Correction requires admin review and approval.
 */
@Getter
@Setter
public class DobCorrectionRequestDTO {

    @NotNull(message = "New date of birth is required")
    @Past(message = "Date of birth must be in the past")
    private LocalDate newDateOfBirth;

    @NotBlank(message = "Reason for correction is required")
    @Size(min = 10, max = 500, message = "Reason must be between 10 and 500 characters")
    private String reason;
}

package org.example.rentoza.user.phone;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * DTO za potvrdu OTP koda (POST /confirm).
 */
@Data
public class PhoneVerificationConfirmDTO {

    @NotBlank(message = "OTP kod je obavezan.")
    @Size(min = 6, max = 6, message = "OTP kod mora imati tacno 6 cifara.")
    private String otpCode;
}

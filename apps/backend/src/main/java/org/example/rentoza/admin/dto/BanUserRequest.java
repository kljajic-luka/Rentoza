package org.example.rentoza.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class BanUserRequest {
    @NotBlank(message = "Ban reason is required")
    @Size(min = 10, max = 500, message = "Ban reason must be between 10 and 500 characters")
    private String reason;
}

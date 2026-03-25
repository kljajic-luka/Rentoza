package org.example.rentoza.booking.checkout.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * F5 FIX: Typed, validated request body for disputing a checkout damage claim.
 * Replaces raw Map&lt;String, Object&gt; to enforce input constraints.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DisputeDamageClaimRequest {

    @NotBlank(message = "Razlog osporavanja je obavezan")
    @Size(max = 2000, message = "Razlog ne može biti duži od 2000 karaktera")
    private String reason;

    private List<Long> evidencePhotoIds;
}

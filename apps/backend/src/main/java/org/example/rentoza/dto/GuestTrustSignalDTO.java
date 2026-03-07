package org.example.rentoza.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Explicit provenance-backed trust signal shown to hosts in guest preview.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestTrustSignalDTO {
    private String code;
    private String label;
    private String state;
}
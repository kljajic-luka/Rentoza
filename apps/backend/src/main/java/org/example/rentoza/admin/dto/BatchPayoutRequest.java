package org.example.rentoza.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTO for processing batch payouts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchPayoutRequest {
    
    private List<Long> bookingIds;
    private Boolean dryRun; // If true, validate but don't execute
    private String notes;
}

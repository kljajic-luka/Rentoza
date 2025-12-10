package org.example.rentoza.admin.dto;

import lombok.Builder;
import lombok.Data;
import org.example.rentoza.booking.dispute.DamageClaimStatus;
import org.example.rentoza.admin.dto.enums.DisputeSeverity;

import java.time.LocalDateTime;

@Data
@Builder
public class DisputeFilterCriteria {
    private DamageClaimStatus status;
    private DisputeSeverity severity;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
}

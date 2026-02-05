package org.example.rentoza.admin.dto;

import lombok.Builder;
import lombok.Data;
import org.example.rentoza.booking.dispute.DamageClaimStatus;

import java.time.Instant;

@Data
@Builder
public class AdminDisputeListDto {
    private Long id;
    private DamageClaimStatus status;
    private Long estimatedCostCents;
    private String guestName;
    private String hostName;
    private Instant createdAt;
}

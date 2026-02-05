package org.example.rentoza.admin.dto;

import lombok.Builder;
import lombok.Data;
import org.example.rentoza.booking.dispute.DamageClaimStatus;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class AdminDisputeDetailDto {
    private Long id;
    private DamageClaimStatus status;
    private String description;
    private Long estimatedCostCents;
    
    private Long guestId;
    private String guestEmail;
    private String guestPhone;
    
    private Long hostId;
    private String hostEmail;
    private String hostPhone;
    
    private Long bookingId;
    private Long carId;
    private String photoUrls;      // Raw JSON string of S3 URLs as stored in DB, or parsed list in service
    
    private DisputeResolutionDto resolution;
    private List<AdminAuditLogDto> history;
    private Instant createdAt;
}

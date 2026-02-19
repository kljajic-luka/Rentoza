package org.example.rentoza.admin.dto;

import lombok.Builder;
import lombok.Data;
import org.example.rentoza.booking.dispute.DamageClaimStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * DTO for admin dispute detail view.
 * 
 * <p>Fields are aligned with frontend AdminDisputeDetailDto interface to ensure
 * the admin dispute detail page renders correctly.
 * 
 * @since V61 - DTO/UI alignment fix
 */
@Data
@Builder
public class AdminDisputeDetailDto {
    private Long id;
    private DamageClaimStatus status;
    private String description;
    private Long estimatedCostCents;
    
    // ========== PARTY INFO ==========
    private Long guestId;
    private String guestName;
    private String guestEmail;
    private String guestPhone;
    
    private Long hostId;
    private String hostName;
    private String hostEmail;
    private String hostPhone;
    
    // ========== BOOKING & VEHICLE ==========
    private Long bookingId;
    private Long carId;
    
    // ========== AMOUNTS (frontend expects BigDecimal, not cents) ==========
    /** Claimed amount in RSD — used by frontend for display. */
    private BigDecimal claimedAmount;
    /** Approved amount in RSD — null if not yet resolved. */
    private BigDecimal approvedAmount;
    
    // ========== EVIDENCE PHOTOS ==========
    private String photoUrls;
    private String checkinPhotoIds;
    private String checkoutPhotoIds;
    private String evidencePhotoIds;
    
    // ========== GUEST RESPONSE ==========
    private String guestResponse;
    private Instant guestRespondedAt;
    
    // ========== ADMIN REVIEW ==========
    private String reviewedBy;
    private Instant reviewedAt;
    private String adminNotes;
    
    // ========== DISPUTE METADATA ==========
    private String disputeStage;
    private String disputeType;
    private String initiator;
    private Boolean adminReviewRequired;
    private String repairQuoteDocumentUrl;
    
    // ========== RESOLUTION & HISTORY ==========
    private DisputeResolutionDto resolution;
    private List<AdminAuditLogDto> history;
    private Instant createdAt;
}

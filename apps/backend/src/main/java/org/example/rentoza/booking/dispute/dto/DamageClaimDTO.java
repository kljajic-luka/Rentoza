package org.example.rentoza.booking.dispute.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.booking.dispute.DamageClaimStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for damage claim response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DamageClaimDTO {
    
    private Long id;
    private Long bookingId;
    
    // ========== PARTICIPANTS ==========
    
    private Long hostId;
    private String hostName;
    private Long guestId;
    private String guestName;
    
    // ========== CLAIM DETAILS ==========
    
    private String description;
    private BigDecimal claimedAmount;
    private BigDecimal approvedAmount;
    
    // ========== PHOTOS ==========
    
    private List<Long> checkinPhotoIds;
    private List<Long> checkoutPhotoIds;
    private List<Long> evidencePhotoIds;
    
    // ========== STATUS ==========
    
    private DamageClaimStatus status;
    private String statusDisplay;
    private boolean canGuestRespond;
    private boolean needsAdminReview;
    private LocalDateTime responseDeadline;
    
    // ========== RESPONSES ==========
    
    private String guestResponse;
    private LocalDateTime guestRespondedAt;
    private String adminNotes;
    private LocalDateTime reviewedAt;
    
    // ========== PAYMENT ==========
    
    private String paymentReference;
    private LocalDateTime paidAt;
    
    // ========== TIMESTAMPS ==========
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    // ========== VEHICLE INFO ==========
    
    private String vehicleName;
    private String vehicleImageUrl;
}



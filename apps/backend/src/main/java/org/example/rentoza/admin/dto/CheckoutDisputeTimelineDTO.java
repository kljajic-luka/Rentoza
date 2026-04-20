package org.example.rentoza.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * DTO for checkout damage dispute timeline view (VAL-010).
 * 
 * <p>Provides a chronological view of all events related to a
 * checkout damage dispute, from initial report to resolution.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutDisputeTimelineDTO {
    
    private Long bookingId;
    private Long damageClaimId;
    private String currentStatus;
    
    // ========== BOOKING INFO ==========
    private Long guestId;
    private String guestName;
    private Long hostId;
    private String hostName;
    private Long carId;
    private String carDescription;
    private Instant tripStart;
    private Instant tripEnd;
    
    // ========== DAMAGE CLAIM INFO ==========
    private String damageDescription;
    private BigDecimal claimedAmountRsd;
    private Instant damageReportedAt;
    private List<String> damagePhotoUrls;
    
    // ========== DEPOSIT INFO ==========
    private BigDecimal securityDepositRsd;
    private String depositHoldReason;
    private Instant depositHoldUntil;
    
    // ========== GUEST RESPONSE ==========
    private String guestResponseType; // ACCEPTED, DISPUTED, null if no response
    private String guestDisputeReason;
    private Instant guestRespondedAt;
    
    // ========== TIMELINE EVENTS ==========
    private List<TimelineEvent> events;
    
    // ========== RESOLUTION (if resolved) ==========
    private boolean resolved;
    private String resolutionDecision;
    private BigDecimal approvedAmountRsd;
    private String resolutionNotes;
    private Long resolvedByAdminId;
    private String resolvedByAdminName;
    private Instant resolvedAt;
    
    /**
     * Individual timeline event.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimelineEvent {
        private String eventType;
        private String description;
        private String actor;        // HOST, GUEST, ADMIN, SYSTEM
        private String actorName;
        private Instant occurredAt;
        private Object metadata;     // Additional event-specific data
    }
}

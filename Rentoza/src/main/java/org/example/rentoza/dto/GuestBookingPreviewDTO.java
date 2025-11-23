package org.example.rentoza.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestBookingPreviewDTO {
    private String profilePhotoUrl;
    private String firstName;
    private String lastInitial; // e.g., "S."
    private String joinDate; // e.g., "Oct 2021"
    
    private boolean emailVerified;
    private boolean phoneVerified;
    private boolean identityVerified;
    private String drivingEligibilityStatus; // "APPROVED", "PENDING", "REJECTED"
    
    private Double starRating;
    private int tripCount;
    private List<String> badges;
    
    private List<ReviewPreviewDTO> hostReviews;
    
    private LocalDateTime requestedStartDateTime;
    private LocalDateTime requestedEndDateTime;
    private String message; // Optional message from guest
    private String protectionPlan;
}

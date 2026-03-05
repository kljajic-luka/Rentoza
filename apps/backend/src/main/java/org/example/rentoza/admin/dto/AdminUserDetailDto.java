package org.example.rentoza.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.example.rentoza.user.OwnerType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Detailed DTO for admin user detail view.
 * Includes full user information plus related data (bookings, cars, disputes).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserDetailDto {
    
    // ==================== BASIC INFO ====================
    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private Integer age;
    private String bio;
    private String avatarUrl;
    private Role role;
    
    // ==================== ACCOUNT STATUS ====================
    private boolean enabled;
    private boolean locked;
    private boolean banned;
    private String banReason;
    private LocalDateTime bannedAt;
    private Long bannedById;
    private String bannedByName;
    
    // ==================== TIMESTAMPS ====================
    private Instant createdAt;
    private Instant updatedAt;
    
    // ==================== RISK ANALYSIS ====================
    /** Risk score 0-100 (higher = more risky) */
    private Integer riskScore;
    private List<String> riskFactors;
    
    // ==================== RELATED DATA ====================
    /** Count of user's bookings */
    private Integer totalBookings;
    private Integer completedBookings;
    private Integer cancelledBookings;
    private Integer disputedBookings;
    
    /** Count of user's cars (for owners) */
    private Integer totalCars;
    private Integer activeCars;
    
    /** Reviews */
    private Integer reviewsGiven;
    private Integer reviewsReceived;
    private Double averageRating;
    
    /** Audit trail */
    private List<AdminAuditLogDto> recentAdminActions;

    // ==================== OWNER VERIFICATION (Serbian Compliance) ====================
    /** NOT_SUBMITTED | PENDING_REVIEW | VERIFIED | REJECTED (computed, to match list view) */
    private String ownerVerificationStatus;

    // ==================== OWNER VERIFICATION (Serbian Compliance) ====================
    private OwnerType ownerType;
    /** Masked only. Never return full values. */
    private String maskedJmbg;
    /** Masked only. Never return full values. */
    private String maskedPib;
    private Boolean isIdentityVerified;
    private LocalDateTime ownerVerificationSubmittedAt;
    private String maskedBankAccountNumber;

    // ==================== IDENTITY REJECTION (M-3) ====================
    /** Reason admin gave when rejecting identity verification */
    private String identityRejectionReason;
    /** When identity was rejected */
    private LocalDateTime identityRejectedAt;

    // ==================== DOB CORRECTION (M-9) ====================
    /** Requested new date of birth value */
    private LocalDate dobCorrectionRequestedValue;
    /** When the DOB correction was requested */
    private LocalDateTime dobCorrectionRequestedAt;
    /** User's reason for requesting DOB correction */
    private String dobCorrectionReason;
    /** PENDING | APPROVED | REJECTED */
    private String dobCorrectionStatus;
    
    /**
     * Convert User entity to detailed DTO.
     */
    public static AdminUserDetailDto fromEntity(User user) {
        AdminUserDetailDtoBuilder builder = AdminUserDetailDto.builder()
            .id(user.getId())
            .firstName(user.getFirstName())
            .lastName(user.getLastName())
            .email(user.getEmail())
            .phone(user.getPhone())
            .age(user.getAge())
            .bio(user.getBio())
            .avatarUrl(user.getAvatarUrl())
            .role(user.getRole())
            .enabled(user.isEnabled())
            .locked(user.isLocked())
            .banned(user.isBanned())
            .banReason(user.getBanReason())
            .bannedAt(user.getBannedAt())
            .createdAt(user.getCreatedAt())
            .updatedAt(user.getUpdatedAt())
            .ownerVerificationStatus(computeOwnerVerificationStatus(user))
            .ownerType(user.getOwnerType())
            .maskedJmbg(user.getMaskedJmbg())
            .maskedPib(user.getMaskedPib())
            .isIdentityVerified(user.getIsIdentityVerified())
            .ownerVerificationSubmittedAt(user.getOwnerVerificationSubmittedAt())
            .maskedBankAccountNumber(user.getMaskedBankAccountNumber())
            .identityRejectionReason(user.getIdentityRejectionReason())
            .identityRejectedAt(user.getIdentityRejectedAt())
            .dobCorrectionRequestedValue(user.getDobCorrectionRequestedValue())
            .dobCorrectionRequestedAt(user.getDobCorrectionRequestedAt())
            .dobCorrectionReason(user.getDobCorrectionReason())
            .dobCorrectionStatus(user.getDobCorrectionStatus());
        
        if (user.getBannedBy() != null) {
            builder.bannedById(user.getBannedBy().getId());
            builder.bannedByName(user.getBannedBy().getFirstName() + " " + 
                                 user.getBannedBy().getLastName());
        }
        
        return builder.build();
    }

    private static String computeOwnerVerificationStatus(User user) {
        if (Boolean.TRUE.equals(user.getIsIdentityVerified())) return "VERIFIED";
        if (user.getOwnerVerificationSubmittedAt() != null) return "PENDING_REVIEW";
        if (user.getIdentityRejectedAt() != null) return "REJECTED";
        return "NOT_SUBMITTED";
    }
    
    /**
     * Get full name.
     */
    public String getFullName() {
        return firstName + " " + lastName;
    }
    
    /**
     * Get status label for display.
     */
    public String getStatusLabel() {
        if (banned) return "BANNED";
        if (locked) return "LOCKED";
        if (!enabled) return "DISABLED";
        return "ACTIVE";
    }
    
    /**
     * Check if user is high risk (score > 70).
     */
    public boolean isHighRisk() {
        return riskScore != null && riskScore > 70;
    }
}

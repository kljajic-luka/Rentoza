package org.example.rentoza.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;

import java.time.Instant;
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
            .updatedAt(user.getUpdatedAt());
        
        if (user.getBannedBy() != null) {
            builder.bannedById(user.getBannedBy().getId());
            builder.bannedByName(user.getBannedBy().getFirstName() + " " + 
                                 user.getBannedBy().getLastName());
        }
        
        return builder.build();
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

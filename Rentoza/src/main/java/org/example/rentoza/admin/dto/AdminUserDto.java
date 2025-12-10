package org.example.rentoza.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * DTO for admin user list view.
 * Contains summary information for user management table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserDto {
    
    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String avatarUrl;
    private Role role;
    
    /** Account status */
    private boolean enabled;
    private boolean locked;
    private boolean banned;
    private String banReason;
    private LocalDateTime bannedAt;
    
    /** Timestamps */
    private Instant createdAt;
    private Instant updatedAt;
    
    /** Computed fields for dashboard */
    private Integer riskScore;
    private Integer bookingsCount;
    private Integer carsCount;
    
    /**
     * Convert User entity to summary DTO.
     */
    public static AdminUserDto fromEntity(User user) {
        return AdminUserDto.builder()
            .id(user.getId())
            .firstName(user.getFirstName())
            .lastName(user.getLastName())
            .email(user.getEmail())
            .phone(user.getPhone())
            .avatarUrl(user.getAvatarUrl())
            .role(user.getRole())
            .enabled(user.isEnabled())
            .locked(user.isLocked())
            .banned(user.isBanned())
            .banReason(user.getBanReason())
            .bannedAt(user.getBannedAt())
            .createdAt(user.getCreatedAt())
            .updatedAt(user.getUpdatedAt())
            .build();
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
}

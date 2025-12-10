package org.example.rentoza.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.user.Role;

/**
 * DTO for user statistics by role.
 * Used by admin dashboard for role-based user breakdown.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserStatDto {
    
    /** User role (USER, OWNER, ADMIN) */
    private Role role;
    
    /** Total users with this role */
    private Long totalCount;
    
    /** Number of banned users with this role */
    private Long bannedCount;
    
    /**
     * Get active (non-banned) user count.
     * 
     * @return Total minus banned
     */
    public Long getActiveCount() {
        return totalCount - (bannedCount != null ? bannedCount : 0);
    }
    
    /**
     * Get banned percentage.
     * 
     * @return Percentage of users banned (0-100)
     */
    public Double getBannedPercentage() {
        if (totalCount == null || totalCount == 0) {
            return 0.0;
        }
        return (bannedCount != null ? bannedCount : 0.0) / totalCount * 100;
    }
}

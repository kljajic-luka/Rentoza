package org.example.rentoza.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.AdminAuditLog;
import org.example.rentoza.admin.entity.ResourceType;

import java.time.LocalDateTime;

/**
 * DTO for admin audit log entries.
 * 
 * <p>Used for API responses when returning audit details.
 * Excludes sensitive information and provides formatted data.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminAuditLogDto {
    
    private Long id;
    
    /** Admin who performed the action */
    private String admin;
    private Long adminId;
    private String adminEmail;
    
    /** Action performed */
    private AdminAction action;
    private String actionDisplayName;
    
    /** Resource affected */
    private ResourceType resourceType;
    private Long resourceId;
    
    /** State snapshot (for detailed view) */
    private String beforeState;
    private String afterState;
    
    /** Audit metadata */
    private String reason;
    private String ipAddress;
    private String userAgent;
    private LocalDateTime createdAt;
    
    /**
     * Convert entity to DTO.
     * Masks sensitive data and formats for API response.
     */
    public static AdminAuditLogDto fromEntity(AdminAuditLog entity) {
        return AdminAuditLogDto.builder()
            .id(entity.getId())
            .adminId(entity.getAdmin() != null ? entity.getAdmin().getId() : null)
            .adminEmail(entity.getAdmin() != null ? maskEmail(entity.getAdmin().getEmail()) : null)
            .action(entity.getAction())
            .actionDisplayName(formatActionName(entity.getAction()))
            .resourceType(entity.getResourceType())
            .resourceId(entity.getResourceId())
            .beforeState(entity.getBeforeState())
            .afterState(entity.getAfterState())
            .reason(entity.getReason())
            .ipAddress(entity.getIpAddress())
            .userAgent(entity.getUserAgent())
            .createdAt(entity.getCreatedAt())
            .build();
    }
    
    /**
     * Create summary DTO without full state snapshots.
     * Used for list views where state data is not needed.
     */
    public static AdminAuditLogDto summaryFromEntity(AdminAuditLog entity) {
        return AdminAuditLogDto.builder()
            .id(entity.getId())
            .adminId(entity.getAdmin() != null ? entity.getAdmin().getId() : null)
            .adminEmail(entity.getAdmin() != null ? maskEmail(entity.getAdmin().getEmail()) : null)
            .action(entity.getAction())
            .actionDisplayName(formatActionName(entity.getAction()))
            .resourceType(entity.getResourceType())
            .resourceId(entity.getResourceId())
            .reason(entity.getReason())
            .ipAddress(entity.getIpAddress())
            .createdAt(entity.getCreatedAt())
            // Omit beforeState, afterState, userAgent for summary
            .build();
    }
    
    /**
     * Mask email for display (privacy protection).
     */
    private static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        String[] parts = email.split("@");
        if (parts[0].length() <= 1) {
            return "***@" + parts[1];
        }
        return parts[0].charAt(0) + "***@" + parts[1];
    }
    
    /**
     * Format action enum name for human-readable display.
     * Example: USER_BANNED → "User Banned"
     */
    private static String formatActionName(AdminAction action) {
        if (action == null) {
            return null;
        }
        String name = action.name();
        String[] words = name.split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) {
                result.append(" ");
            }
            result.append(word.substring(0, 1).toUpperCase())
                  .append(word.substring(1).toLowerCase());
        }
        return result.toString();
    }
}

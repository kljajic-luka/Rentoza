package org.example.rentoza.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.AdminAuditLogDto;
import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.AdminAuditLog;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.admin.repository.AdminAuditLogRepository;
import org.example.rentoza.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AUDIT SERVICE - IMMUTABLE LOGGING
 * 
 * <p>Purpose: Centralized audit logging for all admin actions.
 * 
 * <p><b>CRITICAL PROPERTIES:</b>
 * <ul>
 *   <li><b>APPEND-ONLY</b>: Only INSERT, never UPDATE or DELETE</li>
 *   <li><b>COMPLETE STATE</b>: Captures before/after JSON</li>
 *   <li><b>ACCOUNTABILITY</b>: Records admin ID, timestamp, IP</li>
 *   <li><b>NON-REPUDIATION</b>: Admin cannot deny their actions</li>
 * </ul>
 * 
 * <p><b>Usage:</b>
 * <pre>service.logAction(admin, action, resourceType, resourceId, beforeState, afterState, reason)</pre>
 * 
 * <p><b>DO NOT:</b>
 * <ul>
 *   <li>Store sensitive data (passwords, full credit card numbers) in beforeState/afterState</li>
 *   <li>Update audit logs (will throw exception)</li>
 *   <li>Delete audit logs (will throw exception)</li>
 * </ul>
 * 
 * @see AdminAuditLog
 * @see AdminAuditLogRepository
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class AdminAuditService {
    
    private final AdminAuditLogRepository auditRepo;
    private final ObjectMapper objectMapper;
    
    // Separate logger for audit trail (can be configured for external SIEM in logback-spring.xml)
    private static final org.slf4j.Logger auditLog = 
        org.slf4j.LoggerFactory.getLogger("ADMIN_AUDIT");
    
    /**
     * Log an admin action immutably.
     * 
     * <p>This is the ONLY method that should be used for audit logging.
     * All admin operations must call this before completing.
     * 
     * <p><b>ATOMIC OPERATION:</b>
     * <ol>
     *   <li>Create audit log entry</li>
     *   <li>Persist to database</li>
     *   <li>Write to audit logger</li>
     *   <li>If either fails, entire operation fails (no partial audits)</li>
     * </ol>
     * 
     * @param admin The admin user performing the action (must not be null)
     * @param action What was done (see {@link AdminAction} enum)
     * @param resourceType What was affected (see {@link ResourceType} enum)
     * @param resourceId ID of the resource (can be null for config changes)
     * @param beforeState Full JSON snapshot before change (can be null for creates)
     * @param afterState Full JSON snapshot after change (can be null for deletes)
     * @param reason Why the action was taken (audit trail explanation)
     * 
     * @throws IllegalArgumentException if admin is null
     * @throws RuntimeException if logging fails (action should not proceed)
     */
    public void logAction(
            User admin,
            AdminAction action,
            ResourceType resourceType,
            Long resourceId,
            String beforeState,
            String afterState,
            String reason) {
        
        if (admin == null) {
            throw new IllegalArgumentException("Admin user cannot be null");
        }
        
        try {
            // Create audit log entry
            AdminAuditLog logEntry = AdminAuditLog.builder()
                .admin(admin)
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .beforeState(beforeState)
                .afterState(afterState)
                .reason(reason)
                .ipAddress(getClientIpAddress())
                .userAgent(getUserAgent())
                .createdAt(LocalDateTime.now())
                .build();
            
            // Persist immutably
            AdminAuditLog saved = auditRepo.save(logEntry);
            
            // Log to SLF4J audit logger (for real-time monitoring/SIEM)
            auditLog.info(
                "ADMIN_ACTION: logId={}, adminId={}, admin={}, action={}, " +
                "resourceType={}, resourceId={}, reason={}, ip={}, timestamp={}",
                saved.getId(),
                admin.getId(),
                maskEmail(admin.getEmail()),
                action.name(),
                resourceType.name(),
                resourceId,
                reason != null ? reason.substring(0, Math.min(reason.length(), 100)) : null,
                logEntry.getIpAddress(),
                logEntry.getCreatedAt()
            );
            
            log.debug("Audit log saved: id={}, admin={}, action={}",
                saved.getId(), admin.getEmail(), action.name());
            
        } catch (Exception e) {
            // CRITICAL: Never suppress audit failures
            // If audit fails, the action must not complete
            log.error(
                "CRITICAL: Failed to log admin action. " +
                "AdminId={}, Action={}, Error={}",
                admin.getId(),
                action.name(),
                e.getMessage(),
                e
            );
            
            // Re-throw to prevent action from completing without audit
            throw new RuntimeException(
                "Audit logging failed - action cannot be completed: " + e.getMessage(),
                e
            );
        }
    }
    
    /**
     * Convenience method to serialize an object to JSON for audit logging.
     * 
     * @param obj Object to serialize
     * @return JSON string or "{}" if serialization fails
     */
    public String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("Failed to serialize object for audit: {}", e.getMessage());
            return "{}";
        }
    }
    
    /**
     * Get complete action history for a resource.
     * Shows all changes in chronological order.
     * 
     * @param resourceType Type of resource
     * @param resourceId ID of resource
     * @return List of audit logs (oldest to newest)
     */
    @Transactional(readOnly = true)
    public List<AdminAuditLogDto> getResourceHistory(
            ResourceType resourceType,
            Long resourceId) {
        
        List<AdminAuditLog> logs = auditRepo.findByResource(resourceType, resourceId);
        return logs.stream()
            .map(AdminAuditLogDto::fromEntity)
            .toList();
    }
    
    /**
     * Get all actions performed by a specific admin.
     * 
     * @param adminId ID of admin
     * @param pageable Pagination
     * @return Paginated list of admin's actions
     */
    @Transactional(readOnly = true)
    public Page<AdminAuditLogDto> getAdminActivityLog(
            Long adminId,
            Pageable pageable) {
        
        Page<AdminAuditLog> logs = auditRepo.findByAdminId(adminId, pageable);
        return logs.map(AdminAuditLogDto::fromEntity);
    }
    
    /**
     * Search audit log by action type and date range.
     * Used for compliance and forensic investigation.
     * 
     * @param action Type of action (e.g., USER_DELETED)
     * @param from Start date
     * @param to End date
     * @param pageable Pagination
     * @return Matching audit logs
     */
    @Transactional(readOnly = true)
    public Page<AdminAuditLogDto> searchByAction(
            AdminAction action,
            LocalDateTime from,
            LocalDateTime to,
            Pageable pageable) {
        
        Page<AdminAuditLog> logs = auditRepo.findByActionBetween(
            action, from, to, pageable
        );
        return logs.map(AdminAuditLogDto::fromEntity);
    }
    
    /**
     * Get recent admin activity (last N hours).
     * 
     * @param hoursBack Number of hours to look back
     * @return List of recent audit logs
     */
    @Transactional(readOnly = true)
    public List<AdminAuditLogDto> getRecentActivity(int hoursBack) {
        LocalDateTime since = LocalDateTime.now().minusHours(hoursBack);
        List<AdminAuditLog> logs = auditRepo.findRecentActivity(since);
        return logs.stream()
            .map(AdminAuditLogDto::fromEntity)
            .toList();
    }
    
    /**
     * Get all audit logs with pagination.
     * 
     * @param pageable Pagination parameters
     * @return Paginated list of all audit logs
     */
    @Transactional(readOnly = true)
    public Page<AdminAuditLogDto> getAllLogs(Pageable pageable) {
        Page<AdminAuditLog> logs = auditRepo.findAllOrderByCreatedAtDesc(pageable);
        return logs.map(AdminAuditLogDto::fromEntity);
    }
    
    // ==================== PRIVATE HELPERS ====================
    
    /**
     * Extract client IP address from current request.
     * Handles X-Forwarded-For for proxied requests.
     */
    private String getClientIpAddress() {
        try {
            ServletRequestAttributes attrs = 
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return "unknown";
            }
            HttpServletRequest request = attrs.getRequest();
            
            // Check for forwarded IP (load balancer/proxy)
            String xForwardedFor = request.getHeader("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
                // Take the first IP in chain (original client)
                return xForwardedFor.split(",")[0].trim();
            }
            
            return request.getRemoteAddr();
        } catch (Exception e) {
            log.debug("Failed to extract client IP: {}", e.getMessage());
            return "unknown";
        }
    }
    
    /**
     * Extract User-Agent header from current request.
     */
    private String getUserAgent() {
        try {
            ServletRequestAttributes attrs = 
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest request = attrs.getRequest();
            String userAgent = request.getHeader("User-Agent");
            
            // Truncate if too long
            if (userAgent != null && userAgent.length() > 500) {
                return userAgent.substring(0, 500);
            }
            return userAgent;
        } catch (Exception e) {
            log.debug("Failed to extract User-Agent: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Mask email for logging (privacy protection).
     * Example: john.doe@example.com → j***@example.com
     */
    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        String[] parts = email.split("@");
        if (parts[0].length() <= 1) {
            return "***@" + parts[1];
        }
        return parts[0].charAt(0) + "***@" + parts[1];
    }
}

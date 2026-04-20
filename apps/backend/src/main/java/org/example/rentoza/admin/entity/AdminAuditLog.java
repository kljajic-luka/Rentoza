package org.example.rentoza.admin.entity;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.user.User;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * IMMUTABLE AUDIT LOG ENTITY
 * 
 * <p>Purpose: Record every admin action for compliance, forensics, and accountability.
 * 
 * <p><b>CRITICAL PROPERTIES:</b>
 * <ul>
 *   <li><b>APPEND-ONLY</b>: Records are never updated or deleted</li>
 *   <li><b>COMPLETE STATE</b>: Captures full before/after JSON for rollback capability</li>
 *   <li><b>ACCOUNTABILITY</b>: Includes admin ID, timestamp, IP address</li>
 *   <li><b>LONG RETENTION</b>: 7+ years for compliance</li>
 * </ul>
 * 
 * <p><b>Security:</b>
 * <ul>
 *   <li>No UPDATE/DELETE triggers allowed - enforced by {@link #preventUpdate()} and {@link #preventDelete()}</li>
 *   <li>Read-only for all users except audit service</li>
 *   <li>Indexed for fast searches (admin_id, resource_type, created_at)</li>
 * </ul>
 * 
 * @see AdminAuditService for logging operations
 */
@Entity
@Table(
    name = "admin_audit_log",
    indexes = {
        @Index(name = "idx_audit_admin_created", columnList = "admin_id, created_at"),
        @Index(name = "idx_audit_resource", columnList = "resource_type, resource_id"),
        @Index(name = "idx_audit_action_created", columnList = "action, created_at"),
        @Index(name = "idx_audit_created_at", columnList = "created_at")
    }
)
@Getter
@Setter(AccessLevel.NONE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AdminAuditLog {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * Admin who performed the action.
     * Foreign key to users.id for accountability.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_id", nullable = false)
    private User admin;
    
    /**
     * What action was performed.
     * Examples: USER_BANNED, USER_DELETED, CAR_APPROVED, etc.
     */
    @Column(name = "action", nullable = false, length = 50)
    private AdminAction action;
    
    /**
     * Type of resource affected.
     * Examples: USER, CAR, BOOKING, DISPUTE
     */
    @Column(name = "resource_type", nullable = false, length = 50)
    private ResourceType resourceType;
    
    /**
     * ID of the resource affected.
     * Nullable: Some actions (e.g., CONFIG_UPDATE) don't target a specific resource.
     */
    @Column(name = "resource_id")
    private Long resourceId;
    
    /**
     * Full JSON snapshot of resource BEFORE the action.
     * Used for rollback capability and forensic analysis.
     * 
     * <p>Example:
     * <pre>{"id": 5847, "email": "user@example.com", "banned": false, "role": "USER"}</pre>
     */
    @Column(name = "before_state", columnDefinition = "TEXT")
    private String beforeState;
    
    /**
     * Full JSON snapshot of resource AFTER the action.
     * Null if resource was deleted.
     */
    @Column(name = "after_state", columnDefinition = "TEXT")
    private String afterState;
    
    /**
     * Why the action was taken.
     * Audit trail explanation for compliance and investigation.
     * 
     * <p>Example: "Fraudulent activity detected (3 chargebacks)"
     */
    @Column(name = "reason", length = 500)
    private String reason;
    
    /**
     * Client IP address (IPv4 or IPv6).
     * Used to track location and detect suspicious activity.
     */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;
    
    /**
     * User agent string.
     * Browser/OS information for forensic analysis.
     */
    @Column(name = "user_agent", length = 500)
    private String userAgent;
    
    /**
     * Timestamp when action was logged.
     * Auto-set by Hibernate @CreationTimestamp.
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    /**
     * Prevent accidental updates.
     * This entity should ONLY be inserted, never modified.
     * 
     * @throws RuntimeException if update is attempted
     */
    @PreUpdate
    public void preventUpdate() {
        throw new RuntimeException("AdminAuditLog is immutable - cannot be updated");
    }
    
    /**
     * Prevent accidental deletes.
     * This entity should NEVER be deleted (compliance requirement).
     * 
     * @throws RuntimeException if delete is attempted
     */
    @PreRemove
    public void preventDelete() {
        throw new RuntimeException("AdminAuditLog is immutable - cannot be deleted");
    }
}

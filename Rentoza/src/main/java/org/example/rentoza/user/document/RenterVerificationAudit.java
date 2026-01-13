package org.example.rentoza.user.document;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.user.DriverLicenseStatus;
import org.example.rentoza.user.User;

import java.time.LocalDateTime;

/**
 * Audit trail for renter verification decisions.
 * 
 * <p>Captures all state changes for compliance and fraud investigation:
 * <ul>
 *   <li>Document submissions</li>
 *   <li>Auto-approve/reject decisions with reasoning</li>
 *   <li>Manual admin decisions</li>
 *   <li>Expiry auto-transitions</li>
 *   <li>Suspensions</li>
 * </ul>
 * 
 * <p>GDPR: Audit logs are retained for 7 years per financial regulations.
 */
@Entity
@Table(
    name = "renter_verification_audits",
    indexes = {
        @Index(name = "idx_verification_audits_user", columnList = "user_id"),
        @Index(name = "idx_verification_audits_action", columnList = "action"),
        @Index(name = "idx_verification_audits_actor", columnList = "actor_id"),
        @Index(name = "idx_verification_audits_created", columnList = "created_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RenterVerificationAudit {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * User whose verification status changed.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    /**
     * Document involved (if applicable).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id")
    private RenterDocument document;
    
    /**
     * Actor who made the decision.
     * NULL for system actions (auto-approve, expiry).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;
    
    /**
     * Action taken.
     */
    @Column(name = "action", nullable = false, length = 50)
    private AuditAction action;
    
    /**
     * Previous status before action.
     */
    @Column(name = "previous_status", length = 50)
    private DriverLicenseStatus previousStatus;
    
    /**
     * New status after action.
     */
    @Column(name = "new_status", nullable = false, length = 50)
    private DriverLicenseStatus newStatus;
    
    /**
     * Reason/notes for the action.
     * For rejections: detailed reason.
     * For auto-approve: OCR confidence, risk score.
     */
    @Column(name = "reason", length = 1000)
    private String reason;
    
    /**
     * Metadata (JSON) - OCR results, risk scores, etc.
     */
    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;
    
    /**
     * IP address of actor (for fraud detection).
     */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;
    
    /**
     * User agent of actor.
     */
    @Column(name = "user_agent", length = 500)
    private String userAgent;
    
    /**
     * Timestamp of action.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
    
    // ================= HELPER METHODS =================
    
    /**
     * Check if this was a system (automated) action.
     */
    public boolean isSystemAction() {
        return actor == null;
    }
    
    /**
     * Check if this was an admin (manual) action.
     */
    public boolean isAdminAction() {
        return actor != null && action.isAdminAction();
    }
    
    /**
     * Audit actions for verification workflow.
     */
    public enum AuditAction {
        /** User submitted documents for first time */
        SUBMITTED(false),
        
        /** User re-submitted documents after rejection */
        RESUBMITTED(false),
        
        /** System auto-approved based on OCR/risk */
        AUTO_APPROVED(false),
        
        /** System auto-rejected (expired, unreadable, etc.) */
        AUTO_REJECTED(false),
        
        /** Admin manually approved */
        MANUAL_APPROVED(true),
        
        /** Admin manually rejected */
        MANUAL_REJECTED(true),
        
        /** System detected license expiry */
        EXPIRED(false),
        
        /** Admin suspended verification (fraud) */
        SUSPENDED(true),
        
        /** Admin lifted suspension */
        UNSUSPENDED(true),
        
        /** System escalated to manual review (HIGH risk) */
        ESCALATED_TO_REVIEW(false),
        
        /** Document processing started */
        PROCESSING_STARTED(false),
        
        /** Document processing completed */
        PROCESSING_COMPLETED(false),
        
        /** Document processing failed */
        PROCESSING_FAILED(false),
        
        /** Liveness check passed */
        LIVENESS_PASSED(false),
        
        /** Liveness check failed */
        LIVENESS_FAILED(false),
        
        /** Face match between selfie and license passed */
        FACE_MATCH_PASSED(false),
        
        /** Face match between selfie and license failed */
        FACE_MATCH_FAILED(false);
        
        private final boolean adminAction;
        
        AuditAction(boolean adminAction) {
            this.adminAction = adminAction;
        }
        
        public boolean isAdminAction() {
            return adminAction;
        }
        
        public boolean isApproval() {
            return this == AUTO_APPROVED || this == MANUAL_APPROVED;
        }
        
        public boolean isRejection() {
            return this == AUTO_REJECTED || this == MANUAL_REJECTED;
        }
    }
}

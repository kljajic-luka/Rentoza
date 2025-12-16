package org.example.rentoza.user.document;

import org.example.rentoza.user.DriverLicenseStatus;
import org.example.rentoza.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for renter verification audit log.
 * 
 * <p>Provides compliance and fraud investigation queries.
 */
@Repository
public interface RenterVerificationAuditRepository extends JpaRepository<RenterVerificationAudit, Long> {
    
    // ==================== USER HISTORY QUERIES ====================
    
    /**
     * Find all audits for a user (newest first).
     */
    List<RenterVerificationAudit> findByUserIdOrderByCreatedAtDesc(Long userId);
    
    /**
     * Find all audits for a user with pagination.
     */
    Page<RenterVerificationAudit> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    
    /**
     * Find audits by action type for a user.
     */
    List<RenterVerificationAudit> findByUserIdAndAction(Long userId, RenterVerificationAudit.AuditAction action);
    
    /**
     * Find the most recent audit for a user.
     */
    @Query("""
        SELECT a FROM RenterVerificationAudit a 
        WHERE a.user.id = :userId 
        ORDER BY a.createdAt DESC 
        LIMIT 1
        """)
    RenterVerificationAudit findLatestByUserId(@Param("userId") Long userId);
    
    // ==================== ADMIN AUDIT QUERIES ====================
    
    /**
     * Find all audits by actor (admin).
     */
    List<RenterVerificationAudit> findByActorIdOrderByCreatedAtDesc(Long actorId);
    
    /**
     * Find admin actions by action type.
     */
    List<RenterVerificationAudit> findByActorAndAction(User admin, RenterVerificationAudit.AuditAction action);
    
    /**
     * Find manual review decisions in time range.
     */
    @Query("""
        SELECT a FROM RenterVerificationAudit a 
        WHERE a.action IN ('MANUAL_APPROVED', 'MANUAL_REJECTED') 
        AND a.createdAt BETWEEN :startDate AND :endDate
        ORDER BY a.createdAt DESC
        """)
    List<RenterVerificationAudit> findManualDecisionsInRange(
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
    
    // ==================== DOCUMENT HISTORY QUERIES ====================
    
    /**
     * Find all audits for a document.
     */
    List<RenterVerificationAudit> findByDocumentIdOrderByCreatedAtDesc(Long documentId);
    
    // ==================== ANALYTICS QUERIES ====================
    
    /**
     * Count audits by action type.
     */
    long countByAction(RenterVerificationAudit.AuditAction action);
    
    /**
     * Count audits by action type in time range.
     */
    @Query("""
        SELECT COUNT(a) FROM RenterVerificationAudit a 
        WHERE a.action = :action 
        AND a.createdAt BETWEEN :startDate AND :endDate
        """)
    long countByActionInRange(
        @Param("action") RenterVerificationAudit.AuditAction action,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
    
    /**
     * Get action counts grouped by type (for dashboard).
     */
    @Query("""
        SELECT a.action, COUNT(a) 
        FROM RenterVerificationAudit a 
        WHERE a.createdAt >= :since
        GROUP BY a.action
        """)
    List<Object[]> getActionCountsSince(@Param("since") LocalDateTime since);
    
    /**
     * Calculate approval rate (manual approvals / total manual reviews).
     */
    @Query("""
        SELECT 
            (SELECT COUNT(a) FROM RenterVerificationAudit a WHERE a.action = 'MANUAL_APPROVED' AND a.createdAt >= :since),
            (SELECT COUNT(a) FROM RenterVerificationAudit a WHERE a.action IN ('MANUAL_APPROVED', 'MANUAL_REJECTED') AND a.createdAt >= :since)
        """)
    List<Object[]> getManualApprovalRate(@Param("since") LocalDateTime since);
    
    // ==================== FRAUD DETECTION QUERIES ====================
    
    /**
     * Find suspicious patterns: multiple rejections for same IP.
     */
    @Query("""
        SELECT a.ipAddress, COUNT(a) as cnt 
        FROM RenterVerificationAudit a 
        WHERE a.action IN ('AUTO_REJECTED', 'MANUAL_REJECTED') 
        AND a.ipAddress IS NOT NULL
        AND a.createdAt >= :since
        GROUP BY a.ipAddress 
        HAVING COUNT(a) >= :threshold
        """)
    List<Object[]> findSuspiciousIpPatterns(
        @Param("since") LocalDateTime since,
        @Param("threshold") long threshold
    );
    
    /**
     * Find users with multiple submission attempts.
     */
    @Query("""
        SELECT a.user.id, COUNT(a) as cnt 
        FROM RenterVerificationAudit a 
        WHERE a.action IN ('SUBMITTED', 'RESUBMITTED') 
        AND a.createdAt >= :since
        GROUP BY a.user.id 
        HAVING COUNT(a) >= :threshold
        """)
    List<Object[]> findUsersWithManyAttempts(
        @Param("since") LocalDateTime since,
        @Param("threshold") long threshold
    );
    
    // ==================== STATUS TRANSITION QUERIES ====================
    
    /**
     * Find audits where status transitioned to specific state.
     */
    List<RenterVerificationAudit> findByNewStatus(DriverLicenseStatus newStatus);
    
    /**
     * Find recent status transitions.
     */
    @Query("""
        SELECT a FROM RenterVerificationAudit a 
        WHERE a.createdAt >= :since 
        AND a.previousStatus IS NOT NULL
        ORDER BY a.createdAt DESC
        """)
    List<RenterVerificationAudit> findRecentTransitions(@Param("since") LocalDateTime since);
}

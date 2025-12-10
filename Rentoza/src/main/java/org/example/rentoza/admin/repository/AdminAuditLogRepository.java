package org.example.rentoza.admin.repository;

import org.example.rentoza.admin.entity.AdminAuditLog;
import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.ResourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for immutable admin audit logs.
 * 
 * <p><b>IMPORTANT:</b> Only INSERT operations are allowed.
 * UPDATE and DELETE are prevented by {@link AdminAuditLog#preventUpdate()} 
 * and {@link AdminAuditLog#preventDelete()}.
 * 
 * <p>Provides query methods for:
 * <ul>
 *   <li>Audit trail by admin (accountability tracking)</li>
 *   <li>Audit trail by resource (change history)</li>
 *   <li>Audit trail by action type (compliance/forensics)</li>
 *   <li>Recent activity (security monitoring)</li>
 * </ul>
 * 
 * @see AdminAuditLog
 * @see AdminAuditService
 */
@Repository
public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long>, JpaSpecificationExecutor<AdminAuditLog> {
    
    // ==================== AUDIT TRAIL BY ADMIN ====================
    
    /**
     * Get all actions performed by a specific admin.
     * Used for admin accountability tracking and activity reports.
     * 
     * @param adminId Admin user ID
     * @param pageable Pagination parameters
     * @return Paginated list of audit logs (newest first)
     */
    @Query("SELECT a FROM AdminAuditLog a WHERE a.admin.id = :adminId " +
           "ORDER BY a.createdAt DESC")
    Page<AdminAuditLog> findByAdminId(
        @Param("adminId") Long adminId,
        Pageable pageable);
    
    // ==================== AUDIT TRAIL BY RESOURCE ====================
    
    /**
     * Get complete action history for a specific resource.
     * E.g., all actions on user #5847 in chronological order.
     * 
     * @param resourceType Type of resource (USER, CAR, etc.)
     * @param resourceId ID of the resource
     * @return List of audit logs (oldest to newest)
     */
    @Query("SELECT a FROM AdminAuditLog a " +
           "WHERE a.resourceType = :resourceType " +
           "AND a.resourceId = :resourceId " +
           "ORDER BY a.createdAt ASC")
    List<AdminAuditLog> findByResource(
        @Param("resourceType") ResourceType resourceType,
        @Param("resourceId") Long resourceId);
    
    // ==================== AUDIT TRAIL BY ACTION ====================
    
    /**
     * Find all actions of a specific type within date range.
     * E.g., all USER_DELETED actions in date range for compliance.
     * 
     * @param action Action type to search for
     * @param from Start of date range
     * @param to End of date range
     * @param pageable Pagination parameters
     * @return Paginated list of matching audit logs
     */
    @Query("SELECT a FROM AdminAuditLog a " +
           "WHERE a.action = :action " +
           "AND a.createdAt BETWEEN :from AND :to " +
           "ORDER BY a.createdAt DESC")
    Page<AdminAuditLog> findByActionBetween(
        @Param("action") AdminAction action,
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to,
        Pageable pageable);
    
    // ==================== COMPLIANCE QUERIES ====================
    
    /**
     * Get recent admin activity (last X hours).
     * Used for security monitoring and real-time dashboards.
     * 
     * @param since Start time (e.g., 24 hours ago)
     * @return List of recent audit logs (newest first)
     */
    @Query("SELECT a FROM AdminAuditLog a " +
           "WHERE a.createdAt >= :since " +
           "ORDER BY a.createdAt DESC")
    List<AdminAuditLog> findRecentActivity(@Param("since") LocalDateTime since);
    
    /**
     * Get all deletions in date range (compliance/forensics).
     * 
     * @param from Start of date range
     * @param to End of date range
     * @return List of deletion audit logs
     */
    @Query("SELECT a FROM AdminAuditLog a " +
           "WHERE a.action IN (org.example.rentoza.admin.entity.AdminAction.USER_DELETED, " +
           "org.example.rentoza.admin.entity.AdminAction.CAR_REMOVED) " +
           "AND a.createdAt BETWEEN :from AND :to " +
           "ORDER BY a.createdAt DESC")
    List<AdminAuditLog> findDeletions(
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to);
    
    /**
     * Count actions by a specific admin (for activity statistics).
     * 
     * @param adminId Admin user ID
     * @return Total action count
     */
    @Query("SELECT COUNT(a) FROM AdminAuditLog a WHERE a.admin.id = :adminId")
    Long countByAdminId(@Param("adminId") Long adminId);
    
    /**
     * Get all audit logs with pagination (for admin audit viewer).
     * 
     * @param pageable Pagination parameters
     * @return Paginated list of all audit logs
     */
    @Query("SELECT a FROM AdminAuditLog a ORDER BY a.createdAt DESC")
    Page<AdminAuditLog> findAllOrderByCreatedAtDesc(Pageable pageable);
    
    /**
     * Find audit logs by resource type and ID (paginated).
     * Used by AdminAuditController to get resource history.
     */
    @Query("SELECT a FROM AdminAuditLog a " +
           "WHERE a.resourceType = :resourceType " +
           "AND a.resourceId = :resourceId " +
           "ORDER BY a.createdAt DESC")
    Page<AdminAuditLog> findByResourceTypeAndResourceId(
        @Param("resourceType") ResourceType resourceType,
        @Param("resourceId") Long resourceId,
        Pageable pageable);
    
    /**
     * Find audit logs between timestamps (for stats/export).
     */
    @Query("SELECT a FROM AdminAuditLog a " +
           "WHERE a.createdAt BETWEEN :start AND :end " +
           "ORDER BY a.createdAt DESC")
    List<AdminAuditLog> findByTimestampBetween(
        @Param("start") java.time.Instant start,
        @Param("end") java.time.Instant end);
}


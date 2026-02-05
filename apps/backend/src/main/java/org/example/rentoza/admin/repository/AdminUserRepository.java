package org.example.rentoza.admin.repository;

import org.example.rentoza.admin.dto.UserStatDto;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Extended user repository for admin operations.
 * 
 * <p>Extends the base {@link UserRepository} with admin-specific queries for:
 * <ul>
 *   <li>User listing with advanced filters</li>
 *   <li>Banned user management</li>
 *   <li>Risk analysis (fraud detection)</li>
 *   <li>Statistics aggregation</li>
 * </ul>
 * 
 * @see UserRepository for base user operations
 */
@Repository
public interface AdminUserRepository extends UserRepository {
    
    // ==================== ACTIVE USERS ====================
    
    /**
     * Find all non-admin active users.
     * Used for admin dashboard user count.
     * 
     * @return List of active non-admin users
     */
    @Query("SELECT u FROM User u WHERE u.enabled = true " +
           "AND u.role IN (org.example.rentoza.user.Role.USER, org.example.rentoza.user.Role.OWNER) " +
           "ORDER BY u.createdAt DESC")
    List<User> findActiveNonAdminUsers();
    
    /**
     * Find active users created since a specific time.
     * Used for new user growth metrics.
     * 
     * @param since Start time
     * @param pageable Pagination
     * @return Paginated list of new users
     */
    @Query("SELECT u FROM User u WHERE u.enabled = true " +
           "AND u.createdAt >= :since " +
           "ORDER BY u.createdAt DESC")
    Page<User> findActiveUsersSince(@Param("since") Instant since, Pageable pageable);
    
    /**
     * Count users created since a specific time.
     * 
     * @param since Start time
     * @return Count of new users
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.createdAt >= :since")
    Long countUsersSince(@Param("since") Instant since);
    
    // ==================== BANNED USERS ====================
    
    /**
     * List all currently banned users with pagination.
     * 
     * @param pageable Pagination parameters
     * @return Paginated list of banned users
     */
    @Query("SELECT u FROM User u WHERE u.banned = true " +
           "ORDER BY u.bannedAt DESC")
    Page<User> findBannedUsersPaged(Pageable pageable);
    
    /**
     * Count banned users.
     * Used for dashboard KPI.
     * 
     * @return Number of banned users
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.banned = true")
    Long countBannedUsers();
    
    // ==================== SEARCH & FILTER ====================
    
    /**
     * Search users by email or name.
     * 
     * @param searchTerm Search string (partial match)
     * @param pageable Pagination
     * @return Matching users
     */
    @Query("SELECT u FROM User u WHERE " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
           "OR LOWER(u.firstName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
           "OR LOWER(u.lastName) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    Page<User> searchUsers(@Param("searchTerm") String searchTerm, Pageable pageable);
    
    /**
     * Find all users with pagination and sorting.
     * Base query for admin user list.
     * 
     * @param pageable Pagination parameters
     * @return Paginated user list
     */
       @Query("SELECT u FROM User u")
    Page<User> findAllUsers(Pageable pageable);
    
    // ==================== RISK ANALYSIS ====================
    
    /**
     * Find high-risk users (fraud detection).
     * Users with bans or multiple issues.
     * 
     * @param pageable Pagination
     * @return High-risk users
     */
    @Query(value = """
        SELECT u.* FROM users u
        WHERE u.banned = true
           OR u.locked = true
        ORDER BY u.updated_at DESC
        """, nativeQuery = true)
    Page<User> findHighRiskUsers(Pageable pageable);
    
    // ==================== STATISTICS ====================
    
    /**
     * Count total active users (not banned, enabled).
     * 
     * @return Total active user count
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.enabled = true AND u.banned = false")
    Long countActiveUsers();
    
    /**
     * Count users by role.
     * 
     * @return Role-based statistics
     */
    @Query("SELECT new org.example.rentoza.admin.dto.UserStatDto(" +
           "u.role, COUNT(u), " +
           "SUM(CASE WHEN u.banned = true THEN 1L ELSE 0L END)) " +
           "FROM User u GROUP BY u.role")
    List<UserStatDto> getUserStatistics();
}

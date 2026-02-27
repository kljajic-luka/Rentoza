package org.example.rentoza.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

@org.springframework.context.annotation.Primary
@org.springframework.stereotype.Repository
public interface UserRepository extends JpaRepository<User,Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByPhone(String phone);
    List<User> findByRole(Role role);
    boolean existsByPhoneAndIdNot(String phone, Long id);
    
    // ========== ADMIN MODERATION QUERIES ==========
    
    /**
     * Find all banned users.
     * Used by AdminUserController to list banned users.
     */
    List<User> findByBannedTrue();
    
    /**
     * Find user by Google ID for OAuth2 authentication.
     * Used by CustomOAuth2UserService.
     */
    Optional<User> findByGoogleId(String googleId);
    
    /**
     * Check if a user is banned by email.
     * Used by authentication filters for fast ban check.
     */
    @Query("SELECT u.banned FROM User u WHERE u.email = :email")
    Optional<Boolean> isUserBanned(String email);
    
    /**
     * Find users created between dates (for cohort analysis).
     */
    @Query("SELECT u FROM User u WHERE u.createdAt BETWEEN :start AND :end")
    List<User> findByCreatedAtBetween(
        @org.springframework.data.repository.query.Param("start") java.time.Instant start,
        @org.springframework.data.repository.query.Param("end") java.time.Instant end
    );
    
    // ========== OWNER VERIFICATION QUERIES (Serbian Compliance) ==========
    
    /**
     * Check if JMBG is already registered (using Hash).
     * Used by OwnerVerificationService to prevent duplicate registrations.
     */
    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM User u WHERE u.jmbgHash = :jmbgHash")
    boolean existsByJmbgHash(@org.springframework.data.repository.query.Param("jmbgHash") String jmbgHash);

    /**
     * Check if JMBG is registered by a different user (excluding current user).
     * Used by ProfileCompletionService to allow re-saving same user's data.
     */
    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM User u WHERE u.jmbgHash = :jmbgHash AND u.id != :userId")
    boolean existsByJmbgHashAndIdNot(@org.springframework.data.repository.query.Param("jmbgHash") String jmbgHash, 
                                      @org.springframework.data.repository.query.Param("userId") Long userId);
    
    /**
     * Check if PIB is already registered (using Hash).
     * Used by OwnerVerificationService to prevent duplicate registrations.
     */
    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM User u WHERE u.pibHash = :pibHash")
    boolean existsByPibHash(@org.springframework.data.repository.query.Param("pibHash") String pibHash);

    /**
     * Check if PIB is registered by a different user (excluding current user).
     * Used by ProfileCompletionService to allow re-saving same user's data.
     */
    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM User u WHERE u.pibHash = :pibHash AND u.id != :userId")
    boolean existsByPibHashAndIdNot(@org.springframework.data.repository.query.Param("pibHash") String pibHash,
                                     @org.springframework.data.repository.query.Param("userId") Long userId);

    /**
     * Check if driver license number is registered by a different user (excluding current user).
     * Used by ProfileCompletionService to prevent duplicate license registrations.
     */
    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM User u WHERE u.driverLicenseNumberHash = :hash AND u.id != :userId")
    boolean existsByDriverLicenseNumberHashAndIdNot(@org.springframework.data.repository.query.Param("hash") String hash,
                                                     @org.springframework.data.repository.query.Param("userId") Long userId);
    
    /**
     * Find unverified owners awaiting admin review.
     */
    @Query("SELECT u FROM User u WHERE u.isIdentityVerified = false AND (u.jmbg IS NOT NULL OR u.pib IS NOT NULL) ORDER BY u.ownerVerificationSubmittedAt DESC")
    List<User> findPendingVerificationOwners();
    
    // ========== RENTER DRIVER LICENSE VERIFICATION QUERIES ==========
    
    /**
     * Find users awaiting driver license verification (PENDING_REVIEW status).
     * Used by admin queue to display all renters needing manual review.
     * 
     * @param pageable Pagination and sorting parameters
     * @return Page of users with PENDING_REVIEW driver license status
     */
    @Query("""
        SELECT u FROM User u 
        WHERE u.driverLicenseStatus = 'PENDING_REVIEW' 
        AND u.renterVerificationSubmittedAt IS NOT NULL
        ORDER BY u.renterVerificationSubmittedAt ASC
        """)
    org.springframework.data.domain.Page<User> findUsersWithPendingDriverLicenseVerification(
        org.springframework.data.domain.Pageable pageable
    );
    
    /**
     * Find users by driver license status with optional filters.
     * Supports status and risk level filtering.
     * 
     * @param status Driver license status to filter by (nullable for all)
     * @param riskLevel Risk level to filter by (nullable for all)
     * @param pageable Pagination and sorting
     * @return Page of filtered users
     */
    @Query("""
        SELECT u FROM User u 
        WHERE (:status IS NULL OR u.driverLicenseStatus = :status) 
        AND (:riskLevel IS NULL OR u.riskLevel = :riskLevel)
        AND (:status IS NULL 
            OR :status <> org.example.rentoza.user.DriverLicenseStatus.PENDING_REVIEW
            OR u.renterVerificationSubmittedAt IS NOT NULL)
        ORDER BY u.renterVerificationSubmittedAt ASC NULLS LAST
        """)
    org.springframework.data.domain.Page<User> findUsersByDriverLicenseStatusAndRiskLevel(
        @org.springframework.data.repository.query.Param("status") DriverLicenseStatus status,
        @org.springframework.data.repository.query.Param("riskLevel") RiskLevel riskLevel,
        org.springframework.data.domain.Pageable pageable
    );
    
    /**
     * Count users with PENDING_REVIEW driver license status.
     * Used for queue statistics.
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.driverLicenseStatus = 'PENDING_REVIEW' AND u.renterVerificationSubmittedAt IS NOT NULL")
    long countPendingDriverLicenseVerifications();
    
    // ========== SUPABASE AUTH QUERIES ==========
    
    /**
     * Find user by Supabase Auth UUID.
     * Used by SupabaseAuthService for OAuth user lookup.
     * 
     * @param authUid Supabase Auth user UUID
     * @return User if found
     */
    Optional<User> findByAuthUid(java.util.UUID authUid);
    
    // ========== GDPR DELETION SCHEDULER QUERIES ==========

    /**
     * Find users whose deletion grace period has expired and have not yet been permanently deleted.
     * Used by GdprDeletionScheduler to execute pending GDPR Article 17 erasure requests.
     *
     * @param now current timestamp — users with deletionScheduledAt before this are due
     * @return list of users pending permanent deletion
     */
    @Query("""
        SELECT u FROM User u
        WHERE u.deletionScheduledAt < :now
        AND u.deleted = false
        ORDER BY u.deletionScheduledAt ASC
        """)
    java.util.List<User> findByDeletionScheduledAtBeforeAndDeletedFalse(
        @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now
    );

    // ========== LICENSE EXPIRY QUERIES ==========
    
    /**
     * Find users with driver license expiring between two dates.
     * Used by LicenseExpiryScheduler to send expiry warnings.
     * 
     * @param startDate Start of expiry date range (exclusive - tomorrow or later)
     * @param endDate End of expiry date range (inclusive)
     * @return List of users whose licenses expire in this range
     */
    @Query("""
        SELECT u FROM User u 
        WHERE u.driverLicenseExpiryDate > :startDate 
        AND u.driverLicenseExpiryDate <= :endDate
        AND u.driverLicenseStatus = 'APPROVED'
        ORDER BY u.driverLicenseExpiryDate ASC
        """)
    List<User> findUsersWithLicenseExpiringBetween(
        @org.springframework.data.repository.query.Param("startDate") java.time.LocalDate startDate,
        @org.springframework.data.repository.query.Param("endDate") java.time.LocalDate endDate
    );
    
    /**
     * Find users with expired driver licenses.
     * Used by LicenseExpiryScheduler to send expired notifications.
     * 
     * @param date Date to check against (typically today)
     * @return List of users whose licenses have expired
     */
    @Query("""
        SELECT u FROM User u 
        WHERE u.driverLicenseExpiryDate < :date
        AND u.driverLicenseStatus = 'APPROVED'
        ORDER BY u.driverLicenseExpiryDate DESC
        """)
    List<User> findUsersWithLicenseExpiredBefore(
        @org.springframework.data.repository.query.Param("date") java.time.LocalDate date
    );
}

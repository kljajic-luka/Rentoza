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
     * Check if PIB is already registered (using Hash).
     * Used by OwnerVerificationService to prevent duplicate registrations.
     */
    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM User u WHERE u.pibHash = :pibHash")
    boolean existsByPibHash(@org.springframework.data.repository.query.Param("pibHash") String pibHash);
    
    /**
     * Find unverified owners awaiting admin review.
     */
    @Query("SELECT u FROM User u WHERE u.isIdentityVerified = false AND (u.jmbg IS NOT NULL OR u.pib IS NOT NULL) ORDER BY u.ownerVerificationSubmittedAt DESC")
    List<User> findPendingVerificationOwners();
}


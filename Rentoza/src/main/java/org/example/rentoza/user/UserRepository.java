package org.example.rentoza.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

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
}

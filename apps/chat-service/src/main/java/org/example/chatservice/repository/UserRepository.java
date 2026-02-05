package org.example.chatservice.repository;

import org.example.chatservice.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for User entity (main backend users table)
 * 
 * <p>Provides access to users table for UUID to BIGINT mapping</p>
 * <p>Used by SupabaseJwtUtil to map auth.uid (UUID) → users.id (BIGINT)</p>
 * 
 * @author Rentoza Development Team
 * @since 2.0.0 (Supabase Migration)
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Find user by Supabase auth_uid (UUID)
     * 
     * <p>Maps Supabase auth.users.id (UUID) to Rentoza users.id (BIGINT)</p>
     * 
     * @param authUid Supabase UUID from JWT 'sub' claim
     * @return Optional User if found
     */
    @Query("SELECT u FROM User u WHERE u.authUid = :authUid")
    Optional<User> findByAuthUid(@Param("authUid") UUID authUid);

    /**
     * Check if user exists by auth_uid
     * 
     * @param authUid Supabase UUID
     * @return true if user exists
     */
    boolean existsByAuthUid(UUID authUid);
}

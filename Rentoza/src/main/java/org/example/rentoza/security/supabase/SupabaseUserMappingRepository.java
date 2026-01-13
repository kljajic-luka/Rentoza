package org.example.rentoza.security.supabase;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Supabase user mapping operations.
 * 
 * <p>Provides lookups between:
 * <ul>
 *   <li>Supabase Auth UUID → Rentoza BIGINT user ID</li>
 *   <li>Rentoza BIGINT user ID → Supabase Auth UUID</li>
 * </ul>
 * 
 * @since Phase 2 - Supabase Auth Migration
 */
@Repository
public interface SupabaseUserMappingRepository extends JpaRepository<SupabaseUserMapping, UUID> {

    /**
     * Find mapping by Rentoza user ID.
     * Used when we have a Rentoza user and need their Supabase UUID.
     * 
     * @param rentozaUserId Rentoza user ID (BIGINT)
     * @return Mapping if exists
     */
    Optional<SupabaseUserMapping> findByRentozaUserId(Long rentozaUserId);

    /**
     * Check if a Rentoza user has a Supabase mapping.
     * 
     * @param rentozaUserId Rentoza user ID (BIGINT)
     * @return true if mapping exists
     */
    boolean existsByRentozaUserId(Long rentozaUserId);

    /**
     * Delete mapping by Rentoza user ID.
     * Used when deleting a user account.
     * 
     * @param rentozaUserId Rentoza user ID (BIGINT)
     */
    void deleteByRentozaUserId(Long rentozaUserId);
}

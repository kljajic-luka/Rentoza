package org.example.rentoza.admin.repository;

import org.example.rentoza.admin.entity.AdminSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for AdminSettings entity.
 * 
 * <p>Design: This table should have exactly one row (singleton pattern).
 * Use findFirst() to get the single settings row, or create default if empty.
 * 
 * @since Phase 4 - Production Readiness
 */
@Repository
public interface AdminSettingsRepository extends JpaRepository<AdminSettings, Long> {
    
    /**
     * Find the first (and only) admin settings row.
     * 
     * @return Optional containing settings if exists
     */
    default Optional<AdminSettings> findFirst() {
        return findAll().stream().findFirst();
    }
}

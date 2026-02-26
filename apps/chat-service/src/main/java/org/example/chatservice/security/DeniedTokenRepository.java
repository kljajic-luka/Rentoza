package org.example.chatservice.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Read-only repository for checking JWT denylist (shared table with backend).
 */
@Repository
public interface DeniedTokenRepository extends JpaRepository<DeniedToken, Long> {

    boolean existsByTokenHash(String tokenHash);
}

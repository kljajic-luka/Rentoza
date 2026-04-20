package org.example.rentoza.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for managing UserDeviceToken entities.
 * Handles storage and retrieval of Firebase Cloud Messaging device tokens.
 */
@Repository
public interface UserDeviceTokenRepository extends JpaRepository<UserDeviceToken, Long> {

    /**
     * Find all device tokens for a specific user.
     * A user can have multiple devices (web, mobile, tablet).
     */
    List<UserDeviceToken> findByUserId(Long userId);

    /**
     * Find a device token by the actual token string.
     * Used for token validation and deduplication.
     */
    Optional<UserDeviceToken> findByDeviceToken(String deviceToken);

    /**
     * Check if a device token already exists.
     */
    boolean existsByDeviceToken(String deviceToken);

    /**
     * Delete a device token by the token string.
     * Used when a user logs out or uninstalls the app.
     */
    void deleteByDeviceToken(String deviceToken);

    /**
     * Delete all device tokens for a specific user.
     * Used when a user account is deleted.
     */
    void deleteByUserId(Long userId);
}

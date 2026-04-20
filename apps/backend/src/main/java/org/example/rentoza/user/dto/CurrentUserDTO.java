package org.example.rentoza.user.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Typed DTO for GET /api/users/me response.
 * 
 * <p>SECURITY (H-5): Replaces untyped HashMap to prevent accidental data leaks.
 * Only exposes fields explicitly needed by the frontend for session management
 * and profile display.
 * 
 * <p>Used by frontend for:
 * <ul>
 *   <li>Session initialization and role verification</li>
 *   <li>Profile completion guard (registrationStatus)</li>
 *   <li>Conditional UI rendering (roles, ownerType)</li>
 * </ul>
 */
@Getter
@Builder
public class CurrentUserDTO {
    
    private final Long id;
    private final String email;
    private final String firstName;
    private final String lastName;
    private final String phone;
    private final Integer age;
    private final String avatarUrl;
    private final List<String> roles;
    private final boolean authenticated;
    
    /**
     * CRITICAL: Required by frontend ProfileCompletionGuard.
     * Values: ACTIVE, INCOMPLETE
     */
    private final String registrationStatus;
    
    /**
     * Owner type: INDIVIDUAL, LEGAL_ENTITY, or null for non-owners.
     */
    private final String ownerType;

    // ========== PHONE VERIFICATION TRUST FIELDS ==========

    /** Da li je telefon verifikovan putem OTP-a */
    private final boolean phoneVerified;

    /** Timestamp verifikacije (null ako nije verifikovan) */
    private final java.time.LocalDateTime phoneVerifiedAt;

    /** Telefon koji ceka verifikaciju (null ako nema pending izmene) */
    private final String pendingPhone;

    /** Stanje verifikacije: UNVERIFIED, PENDING_CHANGE, VERIFIED */
    private final String phoneVerificationState;
}

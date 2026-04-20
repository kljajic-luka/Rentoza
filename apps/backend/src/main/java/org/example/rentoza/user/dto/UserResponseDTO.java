package org.example.rentoza.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * User response DTO returned by authentication and user endpoints.
 * 
 * <p>CRITICAL: The {@code registrationStatus} field is essential for:
 * <ul>
 *   <li>Frontend OAuth callback to detect INCOMPLETE profiles</li>
 *   <li>ProfileCompletionGuard to block critical routes</li>
 *   <li>Conditional UI rendering based on profile completeness</li>
 * </ul>
 */
@Getter
@Setter
@AllArgsConstructor
@Builder
public class UserResponseDTO {
    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private Integer age;
    private String role;
    
    /**
     * Registration status for profile completion flow.
     * 
     * <p>Values:
     * <ul>
     *   <li>{@code INCOMPLETE} - Google OAuth user, needs to complete profile</li>
     *   <li>{@code ACTIVE} - Fully registered user</li>
     *   <li>{@code SUSPENDED} - Account suspended</li>
     * </ul>
     * 
     * <p>Frontend uses this to redirect INCOMPLETE users to /auth/complete-profile
     * and block access to critical routes (bookings, owner actions).
     */
    private String registrationStatus;
    
    /**
     * Owner type (INDIVIDUAL or LEGAL_ENTITY) - only set for OWNER role.
     */
    private String ownerType;
    
    /**
     * Constructor for backward compatibility (without registrationStatus).
     * New code should use the builder or full constructor.
     * 
     * @deprecated Use builder pattern or full constructor instead
     */
    @Deprecated
    public UserResponseDTO(Long id, String firstName, String lastName, String email, 
                           String phone, Integer age, String role) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.phone = phone;
        this.age = age;
        this.role = role;
        this.registrationStatus = "ACTIVE"; // Default to ACTIVE for backward compat
        this.ownerType = null;
    }
}

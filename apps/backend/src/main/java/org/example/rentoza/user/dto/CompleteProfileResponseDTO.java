package org.example.rentoza.user.dto;

import lombok.Builder;
import lombok.Getter;
import org.example.rentoza.user.OwnerType;
import org.example.rentoza.user.RegistrationStatus;
import org.example.rentoza.user.Role;

import java.time.LocalDate;

/**
 * Response DTO returned after successful profile completion.
 * 
 * <p>Contains:
 * <ul>
 *   <li>Basic user info (id, name, email)</li>
 *   <li>Updated registration status (should be ACTIVE)</li>
 *   <li>Role-specific information based on USER or OWNER</li>
 * </ul>
 */
@Getter
@Builder
public class CompleteProfileResponseDTO {

    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private LocalDate dateOfBirth;
    private Integer calculatedAge;
    
    private Role role;
    private RegistrationStatus registrationStatus;
    
    // Owner-specific fields (null for USER role)
    private OwnerType ownerType;
    private boolean hasBankAccount;
    
    // USER (renter) verification workflow state (e.g. NOT_STARTED, PENDING_REVIEW)
    private String renterVerificationStatus;
    
    private String message;
}

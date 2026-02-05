package org.example.rentoza.user;

/**
 * Registration status for user accounts.
 * 
 * <p>Used to track completion state, especially for Google OAuth users
 * who need to complete additional profile information after initial login.
 */
public enum RegistrationStatus {
    /** Google OAuth user who hasn't completed profile (missing phone, DOB, etc.) */
    INCOMPLETE,
    
    /** Fully registered and active user */
    ACTIVE,
    
    /** Temporarily suspended by admin */
    SUSPENDED,
    
    /** Soft-deleted account */
    DELETED
}

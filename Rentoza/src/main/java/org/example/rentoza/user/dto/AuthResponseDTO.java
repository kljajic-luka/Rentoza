package org.example.rentoza.user.dto;

import lombok.*;

/**
 * Authentication response DTO.
 * 
 * SECURITY HARDENING (Phase 1):
 * - Access tokens are NEVER returned in JSON body (XSS prevention)
 * - Tokens are delivered exclusively via HttpOnly cookies
 * - This DTO returns only user profile data and metadata
 * 
 * @see org.example.rentoza.auth.AuthController for cookie-based token delivery
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AuthResponseDTO {
    
    /**
     * Authenticated user profile information.
     * Contains: id, email, firstName, lastName, role, avatarUrl, etc.
     */
    private UserResponseDTO user;
    
    /**
     * Authentication status indicator.
     * true = successful authentication, cookies have been set
     * false = email confirmation required, no cookies set
     */
    @Builder.Default
    private boolean authenticated = true;
    
    /**
     * Optional message for the client (e.g., "Welcome back!", "Account created")
     */
    private String message;
    
    /**
     * Email confirmation required flag.
     * When true: user must confirm email before logging in.
     * Frontend should show "check your email" message and NOT attempt auto-login.
     */
    @Builder.Default
    private boolean emailConfirmationRequired = false;
    
    /**
     * Factory method for successful authentication
     */
    public static AuthResponseDTO success(UserResponseDTO user) {
        return AuthResponseDTO.builder()
                .user(user)
                .authenticated(true)
                .emailConfirmationRequired(false)
                .build();
    }
    
    /**
     * Factory method for successful authentication with message
     */
    public static AuthResponseDTO success(UserResponseDTO user, String message) {
        return AuthResponseDTO.builder()
                .user(user)
                .authenticated(true)
                .emailConfirmationRequired(false)
                .message(message)
                .build();
    }
    
    /**
     * Factory method for registration requiring email confirmation.
     * User is created but cannot login until email is confirmed.
     */
    public static AuthResponseDTO emailConfirmationRequired(UserResponseDTO user, String message) {
        return AuthResponseDTO.builder()
                .user(user)
                .authenticated(false)
                .emailConfirmationRequired(true)
                .message(message)
                .build();
    }
}
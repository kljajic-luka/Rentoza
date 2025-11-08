package org.example.rentoza.user;

/**
 * Enum representing the authentication provider for a user.
 * Determines how the user authenticated with the system.
 */
public enum AuthProvider {
    /**
     * Local authentication using email and password
     */
    LOCAL,

    /**
     * Google OAuth2 authentication
     */
    GOOGLE
}

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
     * Google OAuth2 authentication (legacy - direct Google OAuth)
     * @deprecated Use SUPABASE for all OAuth authentication
     */
    @Deprecated
    GOOGLE,

    /**
     * Supabase Auth authentication.
     * Covers all Supabase auth methods including:
     * - Email/password
     * - Google OAuth via Supabase
     * - Other OAuth providers configured in Supabase
     */
    SUPABASE
}

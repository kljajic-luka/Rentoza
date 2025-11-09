package org.example.rentoza.auth.oauth2;

/**
 * Enum to distinguish between OAuth2 login and registration flows
 */
public enum OAuth2Mode {
    /**
     * Standard login flow - creates user if doesn't exist (default behavior)
     */
    LOGIN,

    /**
     * Registration flow - throws error if user already exists
     */
    REGISTER
}

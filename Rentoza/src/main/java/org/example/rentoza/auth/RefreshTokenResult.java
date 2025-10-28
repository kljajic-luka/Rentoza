package org.example.rentoza.auth;

/**
 * Represents the outcome of rotating a refresh token.
 * Contains validity, user email, and newly issued raw token.
 */
public record RefreshTokenResult(boolean valid, String email, String newToken) {
    public boolean isValid() { return valid; }
}
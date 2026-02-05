package org.example.rentoza.deprecated.auth;

/**
 * Custom exception for invalid refresh tokens.
 * Copied from RefreshTokenServiceEnhanced and made public for deprecation refactoring.
 */
@Deprecated(since = "2.1.0", forRemoval = true)
public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}

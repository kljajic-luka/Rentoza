package org.example.rentoza.favorite;

/**
 * Domain-specific exception for favorite operations.
 */
public class FavoriteOperationException extends RuntimeException {
    public FavoriteOperationException(String message) {
        super(message);
    }

    public FavoriteOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}

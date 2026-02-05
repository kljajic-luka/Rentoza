package org.example.rentoza.favorite;

/**
 * Signals a missing entity when performing favorite operations.
 */
public class FavoriteNotFoundException extends FavoriteOperationException {
    public FavoriteNotFoundException(String message) {
        super(message);
    }
}

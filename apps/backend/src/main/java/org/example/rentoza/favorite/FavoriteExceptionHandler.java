package org.example.rentoza.favorite;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

@ControllerAdvice(assignableTypes = FavoriteController.class)
@Slf4j
public class FavoriteExceptionHandler {

    @ExceptionHandler(FavoriteNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleFavoriteNotFound(FavoriteNotFoundException ex) {
        log.warn("Favorite resource missing: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(errorPayload("FavoritesNotFound", ex.getMessage()));
    }

    @ExceptionHandler(FavoriteOperationException.class)
    public ResponseEntity<Map<String, String>> handleFavoriteOperationException(FavoriteOperationException ex) {
        log.warn("Favorite operation failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errorPayload("FavoritesOperationFailed", ex.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> handleDataAccess(DataAccessException ex) {
        log.error("Database error while processing favorites: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorPayload("FavoritesDataAccessError", "Unable to process favorites request"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
        log.error("Unexpected favorites error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorPayload("FavoritesOperationFailed", "Unable to process favorites request"));
    }

    private Map<String, String> errorPayload(String error, String message) {
        return Map.of(
                "error", error,
                "message", message
        );
    }
}

package org.example.rentoza.favorite;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for managing user favorites
 * All endpoints require authentication
 */
@RestController
@RequestMapping("/api/favorites")
@RequiredArgsConstructor
@Slf4j
public class FavoriteController {

    private final FavoriteService favoriteService;

    /**
     * Add a car to favorites
     * POST /api/favorites/{carId}
     */
    @PostMapping("/{carId}")
    public ResponseEntity<FavoriteDTO> addFavorite(@PathVariable Long carId) {
        Long userId = getAuthenticatedUserId();
        log.info("User {} adding car {} to favorites", userId, carId);

        try {
            FavoriteDTO favorite = favoriteService.addFavorite(userId, carId);
            return ResponseEntity.status(HttpStatus.CREATED).body(favorite);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to add favorite: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Remove a car from favorites
     * DELETE /api/favorites/{carId}
     */
    @DeleteMapping("/{carId}")
    public ResponseEntity<Void> removeFavorite(@PathVariable Long carId) {
        Long userId = getAuthenticatedUserId();
        log.info("User {} removing car {} from favorites", userId, carId);

        favoriteService.removeFavorite(userId, carId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Toggle favorite status (add if not favorited, remove if favorited)
     * PUT /api/favorites/{carId}/toggle
     */
    @PutMapping("/{carId}/toggle")
    public ResponseEntity<FavoriteService.FavoriteToggleResponse> toggleFavorite(@PathVariable Long carId) {
        Long userId = getAuthenticatedUserId();
        log.info("User {} toggling favorite for car {}", userId, carId);

        try {
            FavoriteService.FavoriteToggleResponse response = favoriteService.toggleFavorite(userId, carId);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to toggle favorite: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get all favorites for the authenticated user
     * GET /api/favorites
     */
    @GetMapping
    public ResponseEntity<List<FavoriteDTO>> getUserFavorites() {
        Long userId = getAuthenticatedUserId();
        log.debug("Fetching favorites for user {}", userId);

        List<FavoriteDTO> favorites = favoriteService.getUserFavorites(userId);
        return ResponseEntity.ok(favorites);
    }

    /**
     * Get list of favorited car IDs for the authenticated user
     * GET /api/favorites/car-ids
     */
    @GetMapping("/car-ids")
    public ResponseEntity<List<Long>> getFavoritedCarIds() {
        Long userId = getAuthenticatedUserId();
        log.debug("Fetching favorited car IDs for user {}", userId);

        List<Long> carIds = favoriteService.getFavoritedCarIds(userId);
        return ResponseEntity.ok(carIds);
    }

    /**
     * Check if a specific car is favorited
     * GET /api/favorites/{carId}/check
     */
    @GetMapping("/{carId}/check")
    public ResponseEntity<Map<String, Boolean>> checkFavorite(@PathVariable Long carId) {
        Long userId = getAuthenticatedUserId();

        boolean isFavorited = favoriteService.isFavorited(userId, carId);
        return ResponseEntity.ok(Map.of("isFavorited", isFavorited));
    }

    /**
     * Get favorite count for a specific car
     * GET /api/favorites/{carId}/count
     */
    @GetMapping("/{carId}/count")
    public ResponseEntity<Map<String, Long>> getFavoriteCount(@PathVariable Long carId) {
        long count = favoriteService.getFavoriteCount(carId);
        return ResponseEntity.ok(Map.of("count", count));
    }

    /**
     * Get total favorite count for the authenticated user
     * GET /api/favorites/count
     */
    @GetMapping("/count")
    public ResponseEntity<Map<String, Long>> getUserFavoriteCount() {
        Long userId = getAuthenticatedUserId();

        long count = favoriteService.getUserFavoriteCount(userId);
        return ResponseEntity.ok(Map.of("count", count));
    }

    /**
     * Extract authenticated user ID from security context
     */
    private Long getAuthenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("User not authenticated");
        }

        try {
            return Long.parseLong(authentication.getName());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid user ID in authentication: " + authentication.getName());
        }
    }
}

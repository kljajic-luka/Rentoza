package org.example.rentoza.favorite;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.auth.oauth2.OAuth2UserPrincipal;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
@CrossOrigin(origins = "http://localhost:4200", allowCredentials = "true")
public class FavoriteController {

    private final FavoriteService favoriteService;
    private final UserRepository userRepository;

    /**
     * Add a car to favorites
     * POST /api/favorites/{carId}
     */
    @PostMapping("/{carId}")
    public ResponseEntity<FavoriteDTO> addFavorite(@PathVariable Long carId) {
        User user = getAuthenticatedUser();
        Long userId = user.getId();
        log.info("User {} adding car {} to favorites", userId, carId);

        FavoriteDTO favorite = favoriteService.addFavorite(userId, carId);
        return ResponseEntity.status(HttpStatus.CREATED).body(favorite);
    }

    /**
     * Remove a car from favorites
     * DELETE /api/favorites/{carId}
     */
    @DeleteMapping("/{carId}")
    public ResponseEntity<Void> removeFavorite(@PathVariable Long carId) {
        User user = getAuthenticatedUser();
        Long userId = user.getId();
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
        User user = getAuthenticatedUser();
        Long userId = user.getId();
        log.info("User {} toggling favorite for car {}", userId, carId);

        FavoriteService.FavoriteToggleResponse response = favoriteService.toggleFavorite(userId, carId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get all favorites for the authenticated user
     * GET /api/favorites
     */
    @GetMapping
    public ResponseEntity<List<FavoriteDTO>> getUserFavorites() {
        User user = getAuthenticatedUser();
        Long userId = user.getId();
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
        User user = getAuthenticatedUser();
        Long userId = user.getId();
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
        User user = getAuthenticatedUser();
        Long userId = user.getId();

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
        User user = getAuthenticatedUser();
        Long userId = user.getId();

        long count = favoriteService.getUserFavoriteCount(userId);
        return ResponseEntity.ok(Map.of("count", count));
    }

    /**
     * Extract authenticated user from security context
     * Returns 401 UNAUTHORIZED if user cannot be resolved
     */
    private User getAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // Check if authentication exists and is not anonymous
        if (authentication == null || !authentication.isAuthenticated()) {
            log.warn("Favorites endpoint accessed without authentication");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }

        Object principal = authentication.getPrincipal();

        // Handle anonymous user case
        if ("anonymousUser".equals(principal)) {
            log.warn("Favorites endpoint accessed by anonymous user");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Anonymous user cannot access favorites");
        }

        String username = null;
        User currentUser = null;

        // Extract user from different principal types
        if (principal instanceof OAuth2UserPrincipal oauthPrincipal) {
            currentUser = oauthPrincipal.getUser();
            username = oauthPrincipal.getEmail();
            log.debug("OAuth2UserPrincipal accessing favorites: {}", username);
        } else if (principal instanceof DefaultOidcUser oidcUser) {
            // CRITICAL FIX: Handle DefaultOidcUser from OAuth2 session
            // This occurs when JWT filter doesn't replace OAuth2 session authentication
            username = oidcUser.getAttribute("email");
            if (username == null) {
                username = oidcUser.getAttribute("sub");
            }
            log.warn("DefaultOidcUser detected in favorites (should use JWT instead): {}", username);
        } else if (principal instanceof OAuth2User oauth2User) {
            // Generic OAuth2User fallback
            username = oauth2User.getAttribute("email");
            if (username == null) {
                username = oauth2User.getAttribute("sub");
            }
            log.warn("OAuth2User detected in favorites (should use JWT instead): {}", username);
        } else if (principal instanceof UserDetails userDetails) {
            username = userDetails.getUsername();
            log.debug("UserDetails principal accessing favorites: {}", username);
        } else if (principal instanceof User userPrincipal) {
            currentUser = userPrincipal;
            username = userPrincipal.getEmail();
            log.debug("User principal accessing favorites: {}", username);
        } else if (principal instanceof String s) {
            username = s;
            log.debug("String principal accessing favorites: {}", username);
        } else {
            log.error("Unsupported principal type in favorites: {}", principal.getClass().getName());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unsupported authentication type");
        }

        // Ensure we have a username
        if (username == null && currentUser != null) {
            username = currentUser.getEmail();
        }

        // Additional check for anonymous username string
        if ("anonymousUser".equalsIgnoreCase(username)) {
            log.warn("Anonymous username detected in favorites access");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Anonymous user cannot access favorites");
        }

        String normalizedEmail = username != null ? username.toLowerCase() : null;

        // Resolve user from database if not already present
        if (currentUser == null || currentUser.getId() == null) {
            if (normalizedEmail == null) {
                log.error("Cannot resolve user: email not available from principal");
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User email not available");
            }
            currentUser = userRepository.findByEmail(normalizedEmail)
                    .orElseThrow(() -> {
                        log.error("Authenticated user not found in database: {}", normalizedEmail);
                        return new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                            "Authenticated user not found in database");
                    });
        }

        log.debug("Favorites request for userId={} email={}",
                currentUser.getId(),
                currentUser.getEmail());

        return currentUser;
    }
}

package org.example.rentoza.favorite;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Service for managing user favorites with transaction-safe operations
 */
@Service
@Slf4j
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final CarRepository carRepository;
    private final UserRepository userRepository;
    private final org.example.rentoza.security.CurrentUser currentUser;

    public FavoriteService(FavoriteRepository favoriteRepository, CarRepository carRepository, 
                           UserRepository userRepository, org.example.rentoza.security.CurrentUser currentUser) {
        this.favoriteRepository = favoriteRepository;
        this.carRepository = carRepository;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
    }

    /**
     * Add a car to user's favorites (idempotent - won't duplicate).
     * RLS-ENFORCED: User can only add favorites to their own account.
     * 
     * @param userId User ID
     * @param carId Car ID to favorite
     * @return Favorite DTO
     * @throws org.springframework.security.access.AccessDeniedException if userId doesn't match current user
     */
    @Transactional
    public FavoriteDTO addFavorite(Long userId, Long carId) {
        // RLS ENFORCEMENT: Verify userId matches current user
        Long requesterId = currentUser.id();
        if (!requesterId.equals(userId) && !currentUser.isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Unauthorized to add favorites for user: " + userId
            );
        }
        
        log.debug("Adding car {} to favorites for user {}", carId, userId);

        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new FavoriteNotFoundException("User not found: " + userId));
            log.debug("Favorites service addFavorite for userId={} email={}", user.getId(), user.getEmail());

            Car car = carRepository.findById(carId)
                    .orElseThrow(() -> new FavoriteNotFoundException("Car not found: " + carId));

            // Check if already favorited (idempotent operation)
            if (favoriteRepository.existsByUserIdAndCarId(userId, carId)) {
                log.debug("Car {} already favorited by user {}", carId, userId);
                Favorite existing = favoriteRepository.findByUserIdAndCarId(userId, carId)
                        .orElseThrow(() -> new FavoriteOperationException("Favorite existence check failed"));
                return toDTO(existing);
            }

            // Create new favorite
            Favorite favorite = Favorite.builder()
                    .user(user)
                    .car(car)
                    .build();

            Favorite saved = favoriteRepository.save(favorite);
            log.info("User {} favorited car {}", userId, carId);

            return toDTO(saved);
        } catch (DataAccessException dae) {
            throw new FavoriteOperationException("Unable to add favorite for user " + userId, dae);
        }
    }

    /**
     * Remove a car from user's favorites (idempotent).
     * RLS-ENFORCED: User can only remove favorites from their own account.
     * 
     * @param userId User ID
     * @param carId Car ID to unfavorite
     * @throws org.springframework.security.access.AccessDeniedException if userId doesn't match current user
     */
    @Transactional
    public void removeFavorite(Long userId, Long carId) {
        // RLS ENFORCEMENT: Verify userId matches current user
        Long requesterId = currentUser.id();
        if (!requesterId.equals(userId) && !currentUser.isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Unauthorized to remove favorites for user: " + userId
            );
        }
        
        logUserContext("Removing favorite", userId);
        log.debug("Removing car {} from favorites for user {}", carId, userId);

        try {
            favoriteRepository.deleteByUserIdAndCarId(userId, carId);
            log.info("User {} unfavorited car {}", userId, carId);
        } catch (DataAccessException dae) {
            throw new FavoriteOperationException("Unable to remove favorite for user " + userId, dae);
        }
    }

    /**
     * Toggle favorite status (add if not present, remove if present).
     * RLS-ENFORCED: User can only toggle favorites on their own account.
     * 
     * @param userId User ID
     * @param carId Car ID to toggle
     * @return FavoriteToggleResponse indicating new state
     * @throws org.springframework.security.access.AccessDeniedException if userId doesn't match current user
     */
    @Transactional
    public FavoriteToggleResponse toggleFavorite(Long userId, Long carId) {
        // RLS ENFORCEMENT: Verify userId matches current user
        Long requesterId = currentUser.id();
        if (!requesterId.equals(userId) && !currentUser.isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Unauthorized to toggle favorites for user: " + userId
            );
        }
        
        logUserContext("Toggling favorite", userId);
        log.debug("Toggling favorite for car {} and user {}", carId, userId);

        try {
            boolean exists = favoriteRepository.existsByUserIdAndCarId(userId, carId);

            if (exists) {
                removeFavorite(userId, carId);
                return new FavoriteToggleResponse(false, "Favorit uklonjen");
            } else {
                FavoriteDTO favorite = addFavorite(userId, carId);
                return new FavoriteToggleResponse(true, "Dodato u favorite");
            }
        } catch (DataAccessException dae) {
            throw new FavoriteOperationException("Unable to toggle favorite for user " + userId, dae);
        }
    }

    /**
     * Check if a car is favorited by user
     */
    @Transactional(readOnly = true)
    public boolean isFavorited(Long userId, Long carId) {
        logUserContext("Checking favorite status", userId);
        try {
            return favoriteRepository.existsByUserIdAndCarId(userId, carId);
        } catch (DataAccessException dae) {
            throw new FavoriteOperationException("Unable to check favorite for user " + userId, dae);
        }
    }

    /**
     * Get all favorites for a user with car details.
     * RLS-ENFORCED: Returns favorites only if requester is the user or admin.
     * Prevents User A from viewing User B's favorites (privacy violation).
     * 
     * @param userId User ID
     * @return List of user's favorites
     * @throws org.springframework.security.access.AccessDeniedException if requester is not the user or admin
     */
    @Transactional(readOnly = true)
    public List<FavoriteDTO> getUserFavorites(Long userId) {
        // RLS ENFORCEMENT: Verify requester is the user or admin
        Long requesterId = currentUser.id();
        if (!requesterId.equals(userId) && !currentUser.isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Unauthorized to access favorites for user: " + userId
            );
        }
        
        logUserContext("Fetching favorites list", userId);
        log.debug("Fetching all favorites for user {}", userId);

        try {
            List<Favorite> favorites = favoriteRepository.findAllByUserIdWithCarDetails(userId);
            if (favorites == null || favorites.isEmpty()) {
                return List.of();
            }
            return favorites.stream()
                    .map(this::toDTO)
                    .toList();
        } catch (DataAccessException dae) {
            throw new FavoriteOperationException("Unable to fetch favorites for user " + userId, dae);
        }
    }

    /**
     * Get list of favorited car IDs for a user (lightweight).
     * RLS-ENFORCED: Returns IDs only if requester is the user or admin.
     * 
     * @param userId User ID
     * @return List of favorited car IDs
     * @throws org.springframework.security.access.AccessDeniedException if requester is not the user or admin
     */
    @Transactional(readOnly = true)
    public List<Long> getFavoritedCarIds(Long userId) {
        // RLS ENFORCEMENT: Verify requester is the user or admin
        Long requesterId = currentUser.id();
        if (!requesterId.equals(userId) && !currentUser.isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Unauthorized to access favorite car IDs for user: " + userId
            );
        }
        
        logUserContext("Fetching favorited car IDs", userId);
        try {
            List<Long> ids = favoriteRepository.findFavoritedCarIdsByUserId(userId);
            return ids == null ? List.of() : ids;
        } catch (DataAccessException dae) {
            throw new FavoriteOperationException("Unable to fetch favorite car ids for user " + userId, dae);
        }
    }

    /**
     * Get favorite count for a car
     */
    @Transactional(readOnly = true)
    public long getFavoriteCount(Long carId) {
        try {
            return favoriteRepository.countByCarId(carId);
        } catch (DataAccessException dae) {
            throw new FavoriteOperationException("Unable to count favorites for car " + carId, dae);
        }
    }

    /**
     * Get user's favorite count
     */
    @Transactional(readOnly = true)
    public long getUserFavoriteCount(Long userId) {
        logUserContext("Counting user favorites", userId);
        try {
            return favoriteRepository.countByUserId(userId);
        } catch (DataAccessException dae) {
            throw new FavoriteOperationException("Unable to count favorites for user " + userId, dae);
        }
    }

    /**
     * Remove all favorites for a user (used when deleting user account)
     */
    @Transactional
    public void removeAllUserFavorites(Long userId) {
        try {
            logUserContext("Removing all favorites for user", userId);
            log.info("Removing all favorites for user {}", userId);
            favoriteRepository.deleteAllByUserId(userId);
        } catch (DataAccessException dae) {
            throw new FavoriteOperationException("Unable to remove favorites for user " + userId, dae);
        }
    }

    /**
     * Remove all favorites for a car (used when deleting car)
     */
    @Transactional
    public void removeAllCarFavorites(Long carId) {
        try {
            log.info("Removing all favorites for car {}", carId);
            favoriteRepository.deleteAllByCarId(carId);
        } catch (DataAccessException dae) {
            throw new FavoriteOperationException("Unable to remove favorites for car " + carId, dae);
        }
    }

    /**
     * Convert Favorite entity to DTO
     */
    private FavoriteDTO toDTO(Favorite favorite) {
        Car car = favorite.getCar();
        if (car == null) {
            log.warn("Favorite {} references a missing car. Skipping car details.", favorite.getId());
        }

        return FavoriteDTO.builder()
                .id(favorite.getId())
                .userId(favorite.getUserId())
                .carId(car != null ? car.getId() : favorite.getCarId())
                .carBrand(car != null ? car.getBrand() : null)
                .carModel(car != null ? car.getModel() : null)
                .carYear(car != null ? car.getYear() : null)
                .carPricePerDay(car != null ? car.getPricePerDay() : null)
                .carLocation(car != null ? car.getLocation() : null)
                .carImageUrl(car != null ? car.getImageUrl() : null)
                .carAvailable(car != null ? car.isAvailable() : null)
                .createdAt(favorite.getCreatedAt())
                .build();
    }

    /**
     * Response DTO for toggle operation
     */
    public record FavoriteToggleResponse(
            boolean isFavorited,
            String message
    ) {}

    private void logUserContext(String action, Long userId) {
        String email = "unknown";
        if (userId != null) {
            email = userRepository.findById(userId).map(User::getEmail).orElse("unknown");
        }
        log.debug("{} | userId={} email={}", action, userId, email);
    }
}

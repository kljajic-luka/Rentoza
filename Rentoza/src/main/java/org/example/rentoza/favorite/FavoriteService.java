package org.example.rentoza.favorite;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Service for managing user favorites with transaction-safe operations
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final CarRepository carRepository;
    private final UserRepository userRepository;

    /**
     * Add a car to user's favorites (idempotent - won't duplicate)
     */
    @Transactional
    public FavoriteDTO addFavorite(Long userId, Long carId) {
        log.debug("Adding car {} to favorites for user {}", carId, userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        Car car = carRepository.findById(carId)
                .orElseThrow(() -> new IllegalArgumentException("Car not found: " + carId));

        // Check if already favorited (idempotent operation)
        if (favoriteRepository.existsByUserAndCar(user, car)) {
            log.debug("Car {} already favorited by user {}", carId, userId);
            Favorite existing = favoriteRepository.findByUserAndCar(user, car)
                    .orElseThrow(() -> new IllegalStateException("Favorite existence check failed"));
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
    }

    /**
     * Remove a car from user's favorites (idempotent)
     */
    @Transactional
    public void removeFavorite(Long userId, Long carId) {
        log.debug("Removing car {} from favorites for user {}", carId, userId);

        favoriteRepository.deleteByUserIdAndCarId(userId, carId);
        log.info("User {} unfavorited car {}", userId, carId);
    }

    /**
     * Toggle favorite status (add if not present, remove if present)
     */
    @Transactional
    public FavoriteToggleResponse toggleFavorite(Long userId, Long carId) {
        log.debug("Toggling favorite for car {} and user {}", carId, userId);

        boolean exists = favoriteRepository.existsByUserIdAndCarId(userId, carId);

        if (exists) {
            removeFavorite(userId, carId);
            return new FavoriteToggleResponse(false, "Favorit uklonjen");
        } else {
            FavoriteDTO favorite = addFavorite(userId, carId);
            return new FavoriteToggleResponse(true, "Dodato u favorite");
        }
    }

    /**
     * Check if a car is favorited by user
     */
    @Transactional(readOnly = true)
    public boolean isFavorited(Long userId, Long carId) {
        return favoriteRepository.existsByUserIdAndCarId(userId, carId);
    }

    /**
     * Get all favorites for a user with car details
     */
    @Transactional(readOnly = true)
    public List<FavoriteDTO> getUserFavorites(Long userId) {
        log.debug("Fetching all favorites for user {}", userId);

        List<Favorite> favorites = favoriteRepository.findAllByUserIdWithCarDetails(userId);

        return favorites.stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * Get list of favorited car IDs for a user (lightweight)
     */
    @Transactional(readOnly = true)
    public List<Long> getFavoritedCarIds(Long userId) {
        return favoriteRepository.findFavoritedCarIdsByUserId(userId);
    }

    /**
     * Get favorite count for a car
     */
    @Transactional(readOnly = true)
    public long getFavoriteCount(Long carId) {
        return favoriteRepository.countByCarId(carId);
    }

    /**
     * Get user's favorite count
     */
    @Transactional(readOnly = true)
    public long getUserFavoriteCount(Long userId) {
        return favoriteRepository.countByUserId(userId);
    }

    /**
     * Remove all favorites for a user (used when deleting user account)
     */
    @Transactional
    public void removeAllUserFavorites(Long userId) {
        log.info("Removing all favorites for user {}", userId);
        favoriteRepository.deleteAllByUserId(userId);
    }

    /**
     * Remove all favorites for a car (used when deleting car)
     */
    @Transactional
    public void removeAllCarFavorites(Long carId) {
        log.info("Removing all favorites for car {}", carId);
        favoriteRepository.deleteAllByCarId(carId);
    }

    /**
     * Convert Favorite entity to DTO
     */
    private FavoriteDTO toDTO(Favorite favorite) {
        return FavoriteDTO.builder()
                .id(favorite.getId())
                .userId(favorite.getUser().getId())
                .carId(favorite.getCar().getId())
                .carBrand(favorite.getCar().getBrand())
                .carModel(favorite.getCar().getModel())
                .carYear(favorite.getCar().getYear())
                .carPricePerDay(favorite.getCar().getPricePerDay())
                .carLocation(favorite.getCar().getLocation())
                .carImageUrl(favorite.getCar().getImageUrl())
                .carAvailable(favorite.getCar().isAvailable())
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
}

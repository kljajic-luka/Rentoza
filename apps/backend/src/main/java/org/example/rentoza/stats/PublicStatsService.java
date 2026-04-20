package org.example.rentoza.stats;

import lombok.RequiredArgsConstructor;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.car.ListingStatus;
import org.example.rentoza.dto.HomeStatsDTO;
import org.example.rentoza.review.ReviewDirection;
import org.example.rentoza.review.ReviewRepository;
import org.example.rentoza.review.ReviewService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class PublicStatsService {

    private final ReviewRepository reviewRepository;
    private final CarRepository carRepository;

    // Minimalni pragovi za javni prikaz — ispod ovih vrednosti API vraca null
    // tako da frontend prikazuje "—" umesto sramotno niskih statistika.
    @Value("${app.public.stats.min-rating:4.0}")
    private double minRatingThreshold;

    @Value("${app.public.stats.min-vehicles:10}")
    private long minVehiclesThreshold;

    @Value("${app.public.stats.min-reviews:5}")
    private long minReviewsForRating;

    public HomeStatsDTO getHomeStats() {
        // P2 FIX: Use visibility-filtered average so hidden (not-yet-revealed) reviews
        // do not skew the public home page stat.
        Instant visibilityTimeout = Instant.now().minus(ReviewService.REVIEW_VISIBILITY_TIMEOUT_DAYS, ChronoUnit.DAYS);
        Double averageRating = reviewRepository.findVisibleAverageRatingByDirection(ReviewDirection.FROM_USER, visibilityTimeout);
        long reviewCount = reviewRepository.countVisibleByDirection(ReviewDirection.FROM_USER, visibilityTimeout);
        long verifiedVehicles = carRepository.countByListingStatus(ListingStatus.APPROVED);

        // Prikazuj rejting samo ako ima dovoljno recenzija I prosek je iznad praga
        Double publicRating = null;
        if (averageRating != null && reviewCount >= minReviewsForRating && averageRating >= minRatingThreshold) {
            publicRating = Math.round(averageRating * 10.0) / 10.0;
        }

        // Prikazuj broj vozila samo ako prelazi minimum
        Long publicVehicles = verifiedVehicles >= minVehiclesThreshold ? verifiedVehicles : null;

        return new HomeStatsDTO(publicRating, publicVehicles, null);
    }
}

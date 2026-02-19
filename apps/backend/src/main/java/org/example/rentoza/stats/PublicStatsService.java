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

    @Value("${app.public.support-availability:24/7}")
    private String supportAvailability;

    public HomeStatsDTO getHomeStats() {
        // P2 FIX: Use visibility-filtered average so hidden (not-yet-revealed) reviews
        // do not skew the public home page stat.
        Instant visibilityTimeout = Instant.now().minus(ReviewService.REVIEW_VISIBILITY_TIMEOUT_DAYS, ChronoUnit.DAYS);
        Double averageRating = reviewRepository.findVisibleAverageRatingByDirection(ReviewDirection.FROM_USER, visibilityTimeout);
        double rating = averageRating == null ? 0.0 : Math.round(averageRating * 10.0) / 10.0;
        long verifiedVehicles = carRepository.countByListingStatus(ListingStatus.APPROVED);
        return new HomeStatsDTO(rating, verifiedVehicles, supportAvailability);
    }
}

package org.example.rentoza.stats;

import lombok.RequiredArgsConstructor;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.car.ListingStatus;
import org.example.rentoza.dto.HomeStatsDTO;
import org.example.rentoza.review.ReviewDirection;
import org.example.rentoza.review.ReviewRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PublicStatsService {

    private final ReviewRepository reviewRepository;
    private final CarRepository carRepository;

    @Value("${app.public.support-availability:24/7}")
    private String supportAvailability;

    public HomeStatsDTO getHomeStats() {
        Double averageRating = reviewRepository.findAverageRatingByDirection(ReviewDirection.FROM_USER);
        double rating = averageRating == null ? 0.0 : Math.round(averageRating * 10.0) / 10.0;
        long verifiedVehicles = carRepository.countByListingStatus(ListingStatus.APPROVED);
        return new HomeStatsDTO(rating, verifiedVehicles, supportAvailability);
    }
}

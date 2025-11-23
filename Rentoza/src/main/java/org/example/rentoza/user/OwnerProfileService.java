package org.example.rentoza.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.car.AvailabilityService;
import org.example.rentoza.dto.OwnerCarPreviewDTO;
import org.example.rentoza.dto.OwnerPublicProfileDTO;
import org.example.rentoza.dto.ReviewPreviewDTO;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.review.Review;
import org.example.rentoza.review.ReviewDirection;
import org.example.rentoza.review.ReviewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OwnerProfileService {

    private final UserRepository userRepository;
    private final CarRepository carRepository;
    private final ReviewRepository reviewRepository;
    private final BookingRepository bookingRepository;
    private final AvailabilityService availabilityService;

    private static final DateTimeFormatter JOIN_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    @Transactional(readOnly = true)
    public OwnerPublicProfileDTO getOwnerPublicProfile(Long ownerId) {
        return getOwnerPublicProfile(ownerId, null, null);
    }

    @Transactional(readOnly = true)
    public OwnerPublicProfileDTO getOwnerPublicProfile(Long ownerId, java.time.LocalDateTime start, java.time.LocalDateTime end) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Owner not found with id: " + ownerId));

        // Fetch active cars (filtered by availability if dates provided)
        List<Car> cars;
        if (start != null && end != null) {
            cars = availabilityService.getAvailableCarsForOwner(ownerId, start, end);
        } else {
            cars = carRepository.findByOwnerIdAndAvailableTrue(ownerId);
        }

        // If user has no cars and is not explicitly an owner role (optional check), maybe 404?
        // For now, we allow profiles even with 0 active cars (maybe they are all booked or disabled).

        // Fetch Stats
        Double averageRating = reviewRepository.findAverageRatingForRevieweeAndDirection(
                ownerId, ReviewDirection.FROM_USER
        );
        long totalTrips = bookingRepository.countByOwnerIdAndStatus(ownerId, BookingStatus.COMPLETED);

        // Fetch Reviews (Limit 5)
        List<Review> reviews = reviewRepository.findByRevieweeIdAndDirectionOrderByCreatedAtDesc(
                ownerId, ReviewDirection.FROM_USER
        ).stream().limit(5).collect(Collectors.toList());

        // Calculate Superhost (Placeholder logic)
        boolean isSuperHost = averageRating != null && averageRating >= 4.8 && totalTrips >= 10;

        return OwnerPublicProfileDTO.builder()
                .id(owner.getId())
                .firstName(owner.getFirstName())
                .lastName(owner.getLastName()) // Public profile shows full name usually, or First + Last Initial
                .avatarUrl(owner.getAvatarUrl())
                .joinDate(owner.getCreatedAt() != null ? 
                        java.time.LocalDateTime.ofInstant(owner.getCreatedAt(), java.time.ZoneId.systemDefault())
                        .format(JOIN_DATE_FORMATTER) : "")
                .about("Hi, I'm " + owner.getFirstName() + "! I love hosting on Rentoza.") // Placeholder if no bio field
                .averageRating(averageRating != null ? averageRating : 0.0)
                .totalTrips((int) totalTrips)
                .responseTime("1 hour") // Placeholder
                .responseRate("100%") // Placeholder
                .isSuperHost(isSuperHost)
                .recentReviews(reviews.stream().map(this::toReviewDTO).collect(Collectors.toList()))
                .cars(cars.stream().map(this::toCarDTO).collect(Collectors.toList()))
                .build();
    }

    private ReviewPreviewDTO toReviewDTO(Review review) {
        return ReviewPreviewDTO.builder()
                .rating(review.getRating())
                .comment(review.getComment())
                .build();
    }

    private OwnerCarPreviewDTO toCarDTO(Car car) {
        // Calculate car rating (average of its bookings' reviews) - simplified for now
        // Ideally Car entity should have cached rating or we query it.
        // For now, using 5.0 as placeholder or 0.0
        return OwnerCarPreviewDTO.builder()
                .id(car.getId())
                .brand(car.getBrand())
                .model(car.getModel())
                .year(car.getYear())
                .imageUrl(car.getImageUrl())
                .pricePerDay(car.getPricePerDay())
                .rating(0.0) // TODO: Implement car-specific rating
                .tripCount(0) // TODO: Implement car-specific trip count
                .build();
    }
}

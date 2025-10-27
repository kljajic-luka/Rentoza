package org.example.rentoza.review;

import jakarta.transaction.Transactional;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.review.dto.ReviewRequestDTO;
import org.example.rentoza.security.JwtUtil;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
public class ReviewService {

    private final ReviewRepository repo;
    private final CarRepository carRepo;
    private final BookingRepository bookingRepo;
    private final UserRepository userRepo;

    private final JwtUtil jwtUtil;
    public ReviewService(
            ReviewRepository repo,
            CarRepository carRepo,
            BookingRepository bookingRepo,
            UserRepository userRepo,
            JwtUtil jwtUtil
    ) {
        this.repo = repo;
        this.carRepo = carRepo;
        this.bookingRepo = bookingRepo;
        this.userRepo = userRepo;
        this.jwtUtil = jwtUtil;
    }

    @Transactional
    public Review addReview(ReviewRequestDTO dto, String reviewerEmail) {
        var car = carRepo.findById(dto.getCarId())
                .orElseThrow(() -> new RuntimeException("Car not found"));

        var reviewer = userRepo.findByEmail(reviewerEmail)
                .orElseThrow(() -> new RuntimeException("Reviewer not found"));

        if (car.getOwner().getEmail().equalsIgnoreCase(reviewerEmail)) {
            throw new RuntimeException("You cannot review your own car.");
        }

        // Check if reviewer already reviewed this car
        if (repo.existsByCarAndReviewer(car, reviewer)) {
            throw new RuntimeException("You have already reviewed this car.");
        }

        // Verify a completed booking exists
        var bookings = bookingRepo.findByCarIdAndRenterEmailIgnoreCaseAndStatusIn(
                dto.getCarId(),
                reviewerEmail,
                List.of(BookingStatus.COMPLETED)
        );

        if (bookings.isEmpty()) {
            throw new RuntimeException("You can only review cars after completing a booking.");
        }

        var booking = bookings.get(0);
        if (booking.getEndDate().isAfter(LocalDate.now())) {
            throw new RuntimeException("You can only review after your rental period has ended.");
        }

        // Validate rating range
        if (dto.getRating() < 1 || dto.getRating() > 5) {
            throw new RuntimeException("Rating must be between 1 and 5.");
        }

        var review = new Review();
        review.setCar(car);
        review.setReviewer(reviewer);
        review.setRating(dto.getRating());
        review.setComment(dto.getComment());
        review.setCreatedAt(Instant.now());

        return repo.save(review);
    }

    public List<Review> getReviewsForCar(Long carId) {
        return repo.findByCarId(carId);
    }

    public double getAverageRatingForCar(Long carId) {
        var reviews = repo.findByCarId(carId);
        return reviews.isEmpty()
                ? 0.0
                : reviews.stream().mapToInt(Review::getRating).average().orElse(0.0);
    }
}
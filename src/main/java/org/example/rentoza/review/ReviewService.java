package org.example.rentoza.review;

import jakarta.transaction.Transactional;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.review.dto.ReviewRequestDTO;
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

    public ReviewService(
            ReviewRepository repo,
            CarRepository carRepo,
            BookingRepository bookingRepo,
            UserRepository userRepo
    ) {
        this.repo = repo;
        this.carRepo = carRepo;
        this.bookingRepo = bookingRepo;
        this.userRepo = userRepo;
    }

    @Transactional
    public Review addReview(ReviewRequestDTO dto) {
        var car = carRepo.findById(dto.getCarId())
                .orElseThrow(() -> new RuntimeException("Car not found"));

        var reviewer = userRepo.findByEmail(dto.getReviewerEmail())
                .orElseThrow(() -> new RuntimeException("Reviewer not found"));

        if (car.getOwner().getEmail().equalsIgnoreCase(dto.getReviewerEmail())) {
            throw new RuntimeException("You cannot review your own car.");
        }

        // Ensure they had a completed booking
        var bookings = bookingRepo.findByCarIdAndRenterEmailIgnoreCaseAndStatusIn(
                dto.getCarId(),
                dto.getReviewerEmail(),
                List.of(BookingStatus.COMPLETED)
        );

        if (bookings.isEmpty()) {
            throw new RuntimeException("You can only review cars after completing a booking.");
        }

        Review review = new Review();
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
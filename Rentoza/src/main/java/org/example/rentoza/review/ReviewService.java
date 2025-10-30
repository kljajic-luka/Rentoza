package org.example.rentoza.review;

import jakarta.transaction.Transactional;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.review.dto.ReviewRequestDTO;
import org.example.rentoza.review.dto.ReviewResponseDTO;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.data.domain.PageRequest;
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
    public Review addReview(ReviewRequestDTO dto, String reviewerEmail) {
        var reviewer = userRepo.findByEmail(reviewerEmail)
                .orElseThrow(() -> new RuntimeException("Reviewer not found"));

        if (dto.getRating() < 1 || dto.getRating() > 5) {
            throw new RuntimeException("Rating must be between 1 and 5.");
        }

        var direction = dto.getDirection() != null ? dto.getDirection() : ReviewDirection.FROM_USER;

        return switch (direction) {
            case FROM_USER -> createRenterToOwnerReview(dto, reviewer);
            case FROM_OWNER -> createOwnerToRenterReview(dto, reviewer);
        };
    }

    private Review createRenterToOwnerReview(ReviewRequestDTO dto, User reviewer) {
        if (dto.getCarId() == null) {
            throw new RuntimeException("Car ID is required for renter reviews.");
        }

        Car car = carRepo.findById(dto.getCarId())
                .orElseThrow(() -> new RuntimeException("Car not found"));

        if (car.getOwner().getId().equals(reviewer.getId())) {
            throw new RuntimeException("You cannot review your own car.");
        }

        if (repo.existsByCarAndReviewerAndDirection(car, reviewer, ReviewDirection.FROM_USER)) {
            throw new RuntimeException("You have already reviewed this car.");
        }

        var bookings = bookingRepo.findByCarIdAndRenterEmailIgnoreCaseAndStatusIn(
                dto.getCarId(),
                reviewer.getEmail(),
                List.of(BookingStatus.COMPLETED)
        );

        if (bookings.isEmpty()) {
            throw new RuntimeException("You can only review cars after completing a booking.");
        }

        Booking booking = bookings.get(0);
        if (booking.getEndDate() != null && booking.getEndDate().isAfter(LocalDate.now())) {
            throw new RuntimeException("You can only review after your rental period has ended.");
        }

        var review = buildReview(
                reviewer,
                car.getOwner(),
                car,
                booking,
                dto,
                ReviewDirection.FROM_USER
        );

        return repo.save(review);
    }

    private Review createOwnerToRenterReview(ReviewRequestDTO dto, User reviewer) {
        if (dto.getBookingId() == null) {
            throw new RuntimeException("Booking ID is required for owner reviews.");
        }

        Booking booking = bookingRepo.findById(dto.getBookingId())
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        if (!booking.getCar().getOwner().getId().equals(reviewer.getId())) {
            throw new RuntimeException("You can only review renters for your own bookings.");
        }

        if (booking.getStatus() != BookingStatus.COMPLETED) {
            throw new RuntimeException("Reviews can be left only after the booking is completed.");
        }

        if (booking.getEndDate() != null && booking.getEndDate().isAfter(LocalDate.now())) {
            throw new RuntimeException("You can only review after the rental period has ended.");
        }

        if (repo.existsByBookingAndDirection(booking, ReviewDirection.FROM_OWNER)) {
            throw new RuntimeException("You have already reviewed this renter for this booking.");
        }

        var review = buildReview(
                reviewer,
                booking.getRenter(),
                booking.getCar(),
                booking,
                dto,
                ReviewDirection.FROM_OWNER
        );

        return repo.save(review);
    }

    private Review buildReview(
            User reviewer,
            User reviewee,
            Car car,
            Booking booking,
            ReviewRequestDTO dto,
            ReviewDirection direction
    ) {
        var review = new Review();
        review.setReviewer(reviewer);
        review.setReviewee(reviewee);
        review.setCar(car);
        review.setBooking(booking);
        review.setDirection(direction);
        review.setRating(dto.getRating());
        review.setComment(dto.getComment());
        review.setCreatedAt(Instant.now());
        return review;
    }

    public List<ReviewResponseDTO> getReviewsForCar(Long carId) {
        var reviews = repo.findByCarIdAndDirection(carId, ReviewDirection.FROM_USER);

        return reviews.stream()
                .map(this::toResponse)
                .toList();
    }

    public double getAverageRatingForCar(Long carId) {
        var reviews = repo.findByCarIdAndDirection(carId, ReviewDirection.FROM_USER);
        return reviews.isEmpty()
                ? 0.0
                : reviews.stream().mapToInt(Review::getRating).average().orElse(0.0);
    }

    public List<ReviewResponseDTO> getRecentReviews() {
        var reviews = repo.findRecentReviews(ReviewDirection.FROM_USER, PageRequest.of(0, 10));
        return reviews.stream()
                .map(this::toResponse)
                .toList();
    }

    private ReviewResponseDTO toResponse(Review review) {
        var reviewer = review.getReviewer();
        var reviewee = review.getReviewee();
        var car = review.getCar();

        return new ReviewResponseDTO(
                review.getId(),
                review.getRating(),
                review.getComment(),
                review.getCreatedAt(),
                review.getDirection(),
                reviewer != null ? reviewer.getFirstName() : null,
                reviewer != null ? reviewer.getLastName() : null,
                null,
                reviewee != null ? reviewee.getFirstName() : null,
                reviewee != null ? reviewee.getLastName() : null,
                null,
                car != null ? car.getId() : null,
                car != null ? car.getBrand() : null,
                car != null ? car.getModel() : null,
                car != null ? car.getYear() : null,
                car != null ? car.getLocation() : null
        );
    }
}

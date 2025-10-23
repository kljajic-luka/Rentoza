package org.example.rentoza.review;

import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.CarRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class ReviewService {

    private final ReviewRepository repo;
    private final CarRepository carRepo;

    private final BookingRepository bookingRepo;

    public ReviewService(ReviewRepository repo, CarRepository carRepo,BookingRepository bookingRepo) {
        this.repo = repo;
        this.carRepo = carRepo;
        this.bookingRepo = bookingRepo;
    }

    public Review addReview(Review review) {
        var car = carRepo.findById(review.getCarId())
                .orElseThrow(() -> new RuntimeException("Car not found"));

        // check valid booking
        var allowedStatuses = List.of(BookingStatus.ACTIVE, BookingStatus.COMPLETED);
        var bookings = bookingRepo.findByCarIdAndRenterEmailAndStatusIn(
                review.getCarId(),
                review.getReviewerEmail(),
                allowedStatuses
        );

        if (bookings.isEmpty()) {
            throw new RuntimeException("You can only review cars you have booked.");
        }
        var booking = bookings.get(0);
        if (booking.getEndDate().isAfter(LocalDate.now())) {
            throw new RuntimeException("You can only review after your rental period has ended.");
        }

        if (review.getRating() < 1 || review.getRating() > 5) {
            throw new RuntimeException("Rating must be between 1 and 5");
        }

        return repo.save(review);
    }

    public List<Review> getReviewsForCar(Long carId) {
        return repo.findByCarId(carId);
    }

    public double getAverageRatingForCar(Long carId) {
        var reviews = repo.findByCarId(carId);
        if (reviews.isEmpty()) return 0.0;
        return reviews.stream().mapToInt(Review::getRating).average().orElse(0.0);
    }
}

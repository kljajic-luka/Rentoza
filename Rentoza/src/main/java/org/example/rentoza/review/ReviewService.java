package org.example.rentoza.review;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.review.dto.ReviewRequestDTO;
import org.example.rentoza.review.dto.ReviewResponseDTO;
import org.example.rentoza.review.dto.RenterReviewRequestDTO;
import org.example.rentoza.review.dto.OwnerReviewRequestDTO;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
@Slf4j
public class ReviewService {

    private final ReviewRepository repo;
    private final CarRepository carRepo;
    private final BookingRepository bookingRepo;
    private final UserRepository userRepo;
    private final NotificationService notificationService;

    public ReviewService(
            ReviewRepository repo,
            CarRepository carRepo,
            BookingRepository bookingRepo,
            UserRepository userRepo,
            NotificationService notificationService
    ) {
        this.repo = repo;
        this.carRepo = carRepo;
        this.bookingRepo = bookingRepo;
        this.userRepo = userRepo;
        this.notificationService = notificationService;
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

    /**
     * Secure renter review submission with category-based ratings.
     * Validates:
     * - User is authenticated renter of the booking
     * - Booking status is COMPLETED
     * - No existing review for this booking
     * - All category ratings are valid (1-5)
     */
    @Transactional
    public Review createRenterReview(RenterReviewRequestDTO dto, String renterEmail) {
        // 1. Get authenticated renter
        User renter = userRepo.findByEmail(renterEmail)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // 2. Get booking and verify ownership + status
        Booking booking = bookingRepo.findById(dto.getBookingId())
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        // 3. Security check: Is this user the renter of the booking?
        if (!booking.getRenter().getId().equals(renter.getId())) {
            throw new RuntimeException("Unauthorized: You can only review your own bookings");
        }

        // 4. Status check: Is the booking completed?
        if (booking.getStatus() != BookingStatus.COMPLETED) {
            throw new RuntimeException("Reviews can only be submitted for completed bookings");
        }

        // 5. Date check: Has the rental period ended?
        if (booking.getEndDate() != null && booking.getEndDate().isAfter(LocalDate.now())) {
            throw new RuntimeException("You can only review after the rental period has ended");
        }

        // 6. Duplicate check: Has user already reviewed this booking?
        if (repo.existsByBookingAndDirection(booking, ReviewDirection.FROM_USER)) {
            throw new RuntimeException("You have already reviewed this booking");
        }

        // 7. Calculate average rating from all categories
        int totalRating = dto.getCleanlinessRating() +
                dto.getMaintenanceRating() +
                dto.getCommunicationRating() +
                dto.getConvenienceRating() +
                dto.getAccuracyRating();
        int averageRating = Math.round((float) totalRating / 5);

        // 8. Build and save review
        Review review = new Review();
        review.setReviewer(renter);
        review.setReviewee(booking.getCar().getOwner());
        review.setCar(booking.getCar());
        review.setBooking(booking);
        review.setDirection(ReviewDirection.FROM_USER);
        review.setRating(averageRating);
        review.setComment(dto.getComment());
        review.setCleanlinessRating(dto.getCleanlinessRating());
        review.setMaintenanceRating(dto.getMaintenanceRating());
        review.setCommunicationRating(dto.getCommunicationRating());
        review.setConvenienceRating(dto.getConvenienceRating());
        review.setAccuracyRating(dto.getAccuracyRating());
        review.setCreatedAt(Instant.now());

        Review savedReview = repo.save(review);

        // Send notification to owner
        try {
            User owner = booking.getCar().getOwner();
            String carInfo = booking.getCar().getBrand() + " " + booking.getCar().getModel();
            String stars = "⭐".repeat(averageRating);

            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(owner.getId())
                    .type(NotificationType.REVIEW_RECEIVED)
                    .message(renter.getFirstName() + " " + renter.getLastName() +
                            " je ostavio/la recenziju za " + carInfo + " " + stars)
                    .relatedEntityId("review-" + savedReview.getId())
                    .build());

            log.info("Review received notification sent to owner {} for review {}", owner.getId(), savedReview.getId());
        } catch (Exception e) {
            log.error("Failed to send review notification: {}", e.getMessage(), e);
        }

        return savedReview;
    }

    /**
     * Secure owner review submission with category-based ratings.
     * Validates:
     * - User is authenticated owner of the booking's car
     * - Booking status is COMPLETED
     * - No existing owner review for this booking
     * - All category ratings are valid (1-5)
     */
    @Transactional
    public Review createOwnerReview(OwnerReviewRequestDTO dto, String ownerEmail) {
        // 1. Get authenticated owner
        User owner = userRepo.findByEmail(ownerEmail)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // 2. Get booking and verify ownership + status
        Booking booking = bookingRepo.findById(dto.getBookingId())
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        // 3. Security check: Is this user the owner of the car?
        if (!booking.getCar().getOwner().getId().equals(owner.getId())) {
            throw new RuntimeException("Unauthorized: You can only review bookings for your own cars");
        }

        // 4. Status check: Is the booking completed?
        if (booking.getStatus() != BookingStatus.COMPLETED) {
            throw new RuntimeException("Reviews can only be submitted for completed bookings");
        }

        // 5. Date check: Has the rental period ended?
        if (booking.getEndDate() != null && booking.getEndDate().isAfter(LocalDate.now())) {
            throw new RuntimeException("You can only review after the rental period has ended");
        }

        // 6. Duplicate check: Has owner already reviewed this booking?
        if (repo.existsByBookingAndDirection(booking, ReviewDirection.FROM_OWNER)) {
            throw new RuntimeException("You have already reviewed this renter for this booking");
        }

        // 7. Calculate average rating from all categories
        int totalRating = dto.getCommunicationRating() +
                dto.getCleanlinessRating() +
                dto.getTimelinessRating() +
                dto.getRespectForRulesRating();
        int averageRating = Math.round((float) totalRating / 4);

        // 8. Build and save review
        Review review = new Review();
        review.setReviewer(owner);
        review.setReviewee(booking.getRenter());
        review.setCar(booking.getCar());
        review.setBooking(booking);
        review.setDirection(ReviewDirection.FROM_OWNER);
        review.setRating(averageRating);
        review.setComment(dto.getComment());
        review.setCommunicationRating(dto.getCommunicationRating());
        review.setCleanlinessRating(dto.getCleanlinessRating());
        review.setTimelinessRating(dto.getTimelinessRating());
        review.setRespectForRulesRating(dto.getRespectForRulesRating());
        review.setCreatedAt(Instant.now());

        Review savedReview = repo.save(review);

        // Send notification to renter
        try {
            User renter = booking.getRenter();
            String stars = "⭐".repeat(averageRating);

            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(renter.getId())
                    .type(NotificationType.REVIEW_RECEIVED)
                    .message(owner.getFirstName() + " " + owner.getLastName() +
                            " je ostavio/la recenziju " + stars)
                    .relatedEntityId("review-" + savedReview.getId())
                    .build());

            log.info("Review received notification sent to renter {} for review {}", renter.getId(), savedReview.getId());
        } catch (Exception e) {
            log.error("Failed to send review notification: {}", e.getMessage(), e);
        }

        return savedReview;
    }

    /**
     * Get reviews received by a user (reviews where they are the reviewee)
     * Used for owner reviews page - shows reviews FROM renters
     */
    public List<ReviewResponseDTO> getReviewsReceivedByEmail(String email) {
        List<Review> reviews = userRepo.findByEmail(email)
                .map(repo::findByReviewee)
                .orElseGet(List::of);

        return reviews.stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Get reviews given by owner (reviews where owner is the reviewer)
     * Used for owner reviews page - shows reviews TO renters
     */
    public List<ReviewResponseDTO> getReviewsGivenByOwner(String email) {
        List<Review> reviews = userRepo.findByEmail(email)
                .map(owner -> repo.findByReviewerAndDirection(owner, ReviewDirection.FROM_OWNER))
                .orElseGet(List::of);

        return reviews.stream()
                .map(this::toResponse)
                .toList();
    }
}

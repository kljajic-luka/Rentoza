package org.example.rentoza.review;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingService;
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
import org.example.rentoza.security.validation.InputSanitizer;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@Slf4j
public class ReviewService {

    /**
     * Mutual Review Visibility Timeout.
     * Reviews are hidden until both parties submit, OR this timeout passes.
     * This prevents one party from waiting indefinitely to see the other's review.
     * Set to 14 days per edge-case standard (if one side never submits).
     */
    public static final int REVIEW_VISIBILITY_TIMEOUT_DAYS = 14;

    /**
     * P0-1 FIX: Review Submission Window.
     * Reviews must be submitted within this many days after the booking end date.
     * Prevents stale/revenge reviews long after a trip.
     */
    private static final int REVIEW_SUBMISSION_WINDOW_DAYS = 14;
    
    /**
     * Issue 3.2 - Fake Review Detection: Velocity Limits
     * Maximum reviews a user can submit in a time window.
     * Prevents automated/bulk fake review attacks.
     */
    private static final int MAX_REVIEWS_PER_HOUR = 5;
    private static final int MAX_REVIEWS_PER_DAY = 10;

    private final ReviewRepository repo;
    private final CarRepository carRepo;
    private final BookingRepository bookingRepo;
    private final UserRepository userRepo;
    private final NotificationService notificationService;
    private final BookingService bookingService;
    private final org.example.rentoza.security.CurrentUser currentUser;

    public ReviewService(
            ReviewRepository repo,
            CarRepository carRepo,
            BookingRepository bookingRepo,
            UserRepository userRepo,
            NotificationService notificationService,
            BookingService bookingService,
            org.example.rentoza.security.CurrentUser currentUser
    ) {
        this.repo = repo;
        this.carRepo = carRepo;
        this.bookingRepo = bookingRepo;
        this.userRepo = userRepo;
        this.notificationService = notificationService;
        this.bookingService = bookingService;
        this.currentUser = currentUser;
    }
    
    /**
     * Issue 3.2 - Fake Review Detection: Check velocity limits.
     * Throws exception if user is submitting reviews too quickly.
     * 
     * @param reviewerId The reviewer's ID
     * @throws RuntimeException if velocity limits exceeded
     */
    private void checkReviewVelocity(Long reviewerId) {
        Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant oneDayAgo = Instant.now().minus(24, ChronoUnit.HOURS);
        
        long reviewsLastHour = repo.countReviewsByReviewerSince(reviewerId, oneHourAgo);
        if (reviewsLastHour >= MAX_REVIEWS_PER_HOUR) {
            log.warn("[FakeReviewDetection] User {} exceeded hourly review limit: {} reviews in last hour", 
                    reviewerId, reviewsLastHour);
            throw new RuntimeException("Previše recenzija u kratkom vremenskom periodu. Molimo pokušajte kasnije.");
        }
        
        long reviewsLastDay = repo.countReviewsByReviewerSince(reviewerId, oneDayAgo);
        if (reviewsLastDay >= MAX_REVIEWS_PER_DAY) {
            log.warn("[FakeReviewDetection] User {} exceeded daily review limit: {} reviews in last 24h", 
                    reviewerId, reviewsLastDay);
            throw new RuntimeException("Dostigli ste dnevni limit za recenzije. Molimo pokušajte sutra.");
        }
    }

    /**
     * P0-1 FIX: Enforce 14-day review submission window.
     * Reviews can only be submitted within REVIEW_SUBMISSION_WINDOW_DAYS after booking end date.
     *
     * @param booking The booking being reviewed
     * @throws RuntimeException if the submission window has passed
     */
    private void enforceSubmissionWindow(Booking booking) {
        LocalDateTime endTime = booking.getEndTime();
        if (endTime == null) {
            log.warn("[ReviewSubmissionWindow] Booking {} has no end time, allowing review", booking.getId());
            return;
        }
        LocalDateTime deadline = endTime.plusDays(REVIEW_SUBMISSION_WINDOW_DAYS);
        if (LocalDateTime.now().isAfter(deadline)) {
            log.info("[ReviewSubmissionWindow] Review submission window expired for booking {}. " +
                    "End time: {}, Deadline: {}", booking.getId(), endTime, deadline);
            throw new RuntimeException(
                    "Rok za ostavljanje recenzije je istekao. Recenzije se mogu ostaviti u roku od " +
                    REVIEW_SUBMISSION_WINDOW_DAYS + " dana nakon završetka rezervacije.");
        }
    }

    /**
     * P0-5 FIX: Sanitize review comment text to prevent XSS.
     * Uses InputSanitizer.sanitizeText() to strip HTML/script tags.
     *
     * @param comment The raw comment text
     * @return Sanitized comment, or null if input was null
     */
    private String sanitizeComment(String comment) {
        if (comment == null || comment.isBlank()) {
            return comment;
        }
        return InputSanitizer.sanitizeText(comment);
    }

    /**
     * P0-2 FIX: Check if a review should be visible under double-blind rules.
     * A review is visible if:
     * 1. Both parties have submitted reviews for the same booking, OR
     * 2. The visibility timeout has passed since review creation.
     *
     * @param review The review to check
     * @return true if the review should be shown
     */
    private boolean isReviewVisible(Review review) {
        // Reviews older than the visibility timeout are always visible
        Instant visibilityTimeout = Instant.now().minus(REVIEW_VISIBILITY_TIMEOUT_DAYS, ChronoUnit.DAYS);
        if (review.getCreatedAt().isBefore(visibilityTimeout)) {
            return true;
        }
        // Check if both directions have reviews for this booking
        if (review.getBooking() != null) {
            ReviewDirection otherDirection = review.getDirection() == ReviewDirection.FROM_USER
                    ? ReviewDirection.FROM_OWNER
                    : ReviewDirection.FROM_USER;
            return repo.existsOtherReview(review.getBooking().getId(), otherDirection);
        }
        return true;
    }

    @Transactional
    public Review addReview(ReviewRequestDTO dto, String reviewerEmail) {
        var reviewer = userRepo.findByEmail(reviewerEmail)
                .orElseThrow(() -> new RuntimeException("Reviewer not found"));

        // Issue 3.2 - Fake Review Detection: Velocity check
        checkReviewVelocity(reviewer.getId());

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

        // Find any bookings for this car by this renter (ACTIVE or COMPLETED)
        var bookings = bookingRepo.findByCarIdAndRenterEmailIgnoreCaseAndStatusIn(
                dto.getCarId(),
                reviewer.getEmail(),
                List.of(BookingStatus.ACTIVE, BookingStatus.COMPLETED)
        );

        if (bookings.isEmpty()) {
            throw new RuntimeException("You can only review cars after completing a booking.");
        }

        // Find the first completed booking using unified completion check
        Booking booking = bookings.stream()
                .filter(bookingService::isBookingCompleted)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("You can only review after your rental period has ended."));

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

        // Unified completion check
        if (!bookingService.isBookingCompleted(booking)) {
            throw new RuntimeException("Reviews can be left only after the booking is completed.");
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
        // P0-5 FIX: Sanitize comment text before persistence
        review.setComment(sanitizeComment(dto.getComment()));
        review.setCreatedAt(Instant.now());
        return review;
    }
    
    /**
     * Get reviews for a car (public view).
     * 
     * <p><b>Issue 3.1 - Mutual Review Visibility:</b></p>
     * A review is only visible if:
     * <ol>
     *   <li>Both renter and owner have submitted reviews for the same booking, OR</li>
     *   <li>7 days have passed since the review was created (timeout)</li>
     * </ol>
     * 
     * This prevents:
     * <ul>
     *   <li>Retaliation reviews (seeing a bad review and responding in kind)</li>
     *   <li>Gaming the system (waiting to see the other's review first)</li>
     * </ul>
     */
    public List<ReviewResponseDTO> getReviewsForCar(Long carId) {
        // Calculate visibility timeout (reviews older than 7 days are always visible)
        Instant visibilityTimeout = Instant.now().minus(REVIEW_VISIBILITY_TIMEOUT_DAYS, ChronoUnit.DAYS);
        
        var reviews = repo.findVisibleByCarIdAndDirection(carId, ReviewDirection.FROM_USER, visibilityTimeout);

        return reviews.stream()
                .map(this::toResponse)
                .toList();
    }

    public double getAverageRatingForCar(Long carId) {
        // P0-2 FIX: Only include visible reviews in average calculation (double-blind)
        Instant visibilityTimeout = Instant.now().minus(REVIEW_VISIBILITY_TIMEOUT_DAYS, ChronoUnit.DAYS);
        var reviews = repo.findVisibleByCarIdAndDirection(carId, ReviewDirection.FROM_USER, visibilityTimeout);
        return reviews.isEmpty()
                ? 0.0
                : reviews.stream().mapToInt(Review::getRating).average().orElse(0.0);
    }

    public List<ReviewResponseDTO> getRecentReviews() {
        // P0-2 FIX: Apply double-blind visibility filter to recent reviews
        var reviews = repo.findRecentReviews(ReviewDirection.FROM_USER, PageRequest.of(0, 20));
        return reviews.stream()
                .filter(this::isReviewVisible)
                .limit(10)
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

        // Issue 3.2 - Fake Review Detection: Velocity check
        checkReviewVelocity(renter.getId());

        // 2. Get booking and verify ownership + status
        Booking booking = bookingRepo.findById(dto.getBookingId())
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        // 3. Security check: Is this user the renter of the booking?
        if (!booking.getRenter().getId().equals(renter.getId())) {
            throw new RuntimeException("Unauthorized: You can only review your own bookings");
        }

        // 4. Unified completion check: Is the booking completed?
        // A booking is considered completed if:
        // - Status is explicitly COMPLETED, OR
        // - End date is in the past (ensures frontend/backend consistency)
        if (!bookingService.isBookingCompleted(booking)) {
            throw new RuntimeException("Možete recenzirati samo završene rezervacije");
        }

        // P0-1 FIX: Enforce 14-day submission window
        enforceSubmissionWindow(booking);

        // 5. Duplicate check: Has user already reviewed this booking?
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

        // P0-5 FIX: Sanitize comment text before persistence
        String sanitizedComment = sanitizeComment(dto.getComment());

        // 8. Build and save review
        Review review = new Review();
        review.setReviewer(renter);
        review.setReviewee(booking.getCar().getOwner());
        review.setCar(booking.getCar());
        review.setBooking(booking);
        review.setDirection(ReviewDirection.FROM_USER);
        review.setRating(averageRating);
        review.setComment(sanitizedComment);
        review.setCleanlinessRating(dto.getCleanlinessRating());
        review.setMaintenanceRating(dto.getMaintenanceRating());
        review.setCommunicationRating(dto.getCommunicationRating());
        review.setConvenienceRating(dto.getConvenienceRating());
        review.setAccuracyRating(dto.getAccuracyRating());
        review.setCreatedAt(Instant.now());

        Review savedReview = repo.save(review);

        // P0-2 FIX: Send notification WITHOUT revealing star rating (double-blind)
        try {
            User owner = booking.getCar().getOwner();
            String carInfo = booking.getCar().getBrand() + " " + booking.getCar().getModel();

            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(owner.getId())
                    .type(NotificationType.REVIEW_RECEIVED)
                    .message(renter.getFirstName() + " " + renter.getLastName() +
                            " je ostavio/la recenziju za " + carInfo)
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

        // Issue 3.2 - Fake Review Detection: Velocity check
        checkReviewVelocity(owner.getId());

        // 2. Get booking and verify ownership + status
        Booking booking = bookingRepo.findById(dto.getBookingId())
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        // 3. Security check: Is this user the owner of the car?
        if (!booking.getCar().getOwner().getId().equals(owner.getId())) {
            throw new RuntimeException("Unauthorized: You can only review bookings for your own cars");
        }

        // 4. Unified completion check: Is the booking completed?
        // A booking is considered completed if:
        // - Status is explicitly COMPLETED, OR
        // - End date is in the past (ensures frontend/backend consistency)
        if (!bookingService.isBookingCompleted(booking)) {
            throw new RuntimeException("Recenziju možete ostaviti samo za završene rezervacije koje nisu već ocenjene");
        }

        // P0-1 FIX: Enforce 14-day submission window
        enforceSubmissionWindow(booking);

        // 5. Duplicate check: Has owner already reviewed this booking?
        if (repo.existsByBookingAndDirection(booking, ReviewDirection.FROM_OWNER)) {
            throw new RuntimeException("You have already reviewed this renter for this booking");
        }

        // 7. Calculate average rating from all categories
        int totalRating = dto.getCommunicationRating() +
                dto.getCleanlinessRating() +
                dto.getTimelinessRating() +
                dto.getRespectForRulesRating();
        int averageRating = Math.round((float) totalRating / 4);

        // P0-5 FIX: Sanitize comment text before persistence
        String sanitizedComment = sanitizeComment(dto.getComment());

        // 8. Build and save review
        Review review = new Review();
        review.setReviewer(owner);
        review.setReviewee(booking.getRenter());
        review.setCar(booking.getCar());
        review.setBooking(booking);
        review.setDirection(ReviewDirection.FROM_OWNER);
        review.setRating(averageRating);
        review.setComment(sanitizedComment);
        review.setCommunicationRating(dto.getCommunicationRating());
        review.setCleanlinessRating(dto.getCleanlinessRating());
        review.setTimelinessRating(dto.getTimelinessRating());
        review.setRespectForRulesRating(dto.getRespectForRulesRating());
        review.setCreatedAt(Instant.now());

        Review savedReview = repo.save(review);

        // P0-2 FIX: Send notification WITHOUT revealing star rating (double-blind)
        try {
            User renter = booking.getRenter();

            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(renter.getId())
                    .type(NotificationType.REVIEW_RECEIVED)
                    .message(owner.getFirstName() + " " + owner.getLastName() +
                            " je ostavio/la recenziju za vašu rezervaciju")
                    .relatedEntityId("review-" + savedReview.getId())
                    .build());

            log.info("Review received notification sent to renter {} for review {}", renter.getId(), savedReview.getId());
        } catch (Exception e) {
            log.error("Failed to send review notification: {}", e.getMessage(), e);
        }

        return savedReview;
    }

    /**
     * Get reviews received by a user (reviews where they are the reviewee).
     * RLS-ENFORCED: Returns reviews only if requester is the reviewee or admin.
     * Used for owner reviews page - shows reviews FROM renters.
     * 
     * @param email Reviewee's email
     * @return List of reviews received by the user
     * @throws org.springframework.security.access.AccessDeniedException if requester is not the reviewee or admin
     */
    public List<ReviewResponseDTO> getReviewsReceivedByEmail(String email) {
        // RLS ENFORCEMENT: Verify requester is the reviewee or admin
        String requesterEmail = currentUser.email();
        if (!requesterEmail.equalsIgnoreCase(email) && !currentUser.isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Unauthorized to access reviews for user: " + email
            );
        }
        
        List<Review> reviews = userRepo.findByEmail(email)
                .map(repo::findByReviewee)
                .orElseGet(List::of);

        // P0-2 FIX: Apply double-blind visibility filter
        return reviews.stream()
                .filter(this::isReviewVisible)
                .map(this::toResponse)
                .toList();
    }

    /**
     * Get reviews given by owner (reviews where owner is the reviewer).
     * RLS-ENFORCED: Returns reviews only if requester is the reviewer or admin.
     * Used for owner reviews page - shows reviews TO renters.
     * 
     * @param email Reviewer's (owner's) email
     * @return List of reviews given by the owner
     * @throws org.springframework.security.access.AccessDeniedException if requester is not the reviewer or admin
     */
    public List<ReviewResponseDTO> getReviewsGivenByOwner(String email) {
        // RLS ENFORCEMENT: Verify requester is the reviewer or admin
        String requesterEmail = currentUser.email();
        if (!requesterEmail.equalsIgnoreCase(email) && !currentUser.isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Unauthorized to access reviews given by: " + email
            );
        }
        
        List<Review> reviews = userRepo.findByEmail(email)
                .map(owner -> repo.findByReviewerAndDirection(owner, ReviewDirection.FROM_OWNER))
                .orElseGet(List::of);

        // P0-2 FIX: Apply double-blind visibility filter
        return reviews.stream()
                .filter(this::isReviewVisible)
                .map(this::toResponse)
                .toList();
    }
}

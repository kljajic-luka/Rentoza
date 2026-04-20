package org.example.rentoza.review;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingService;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.review.dto.OwnerReviewRequestDTO;
import org.example.rentoza.review.dto.RenterReviewRequestDTO;
import org.example.rentoza.review.dto.ReviewResponseDTO;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ReviewService - Feature 11 Pre-Production Hardening.
 *
 * Covers:
 * - P0-1: 14-day submission window enforcement
 * - P0-2: Double-blind visibility enforcement
 * - P0-3: Legacy endpoint deprecation (tested at controller level)
 * - P0-5: Text sanitization before persistence
 * - P1-1: Duplicate review prevention
 * - General security: booking ownership, completion checks
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewService - Feature 11 Hardening Tests")
class ReviewServiceTest {

    @Mock private ReviewRepository reviewRepo;
    @Mock private CarRepository carRepo;
    @Mock private BookingRepository bookingRepo;
    @Mock private UserRepository userRepo;
    @Mock private NotificationService notificationService;
    @Mock private BookingService bookingService;
    @Mock private CurrentUser currentUser;

    @InjectMocks
    private ReviewService reviewService;

    private User renter;
    private User owner;
    private Car car;
    private Booking completedBooking;
    private Booking expiredBooking;

    @BeforeEach
    void setUp() {
        renter = new User();
        renter.setId(1L);
        renter.setEmail("renter@test.com");
        renter.setFirstName("Marko");
        renter.setLastName("Renter");

        owner = new User();
        owner.setId(2L);
        owner.setEmail("owner@test.com");
        owner.setFirstName("Jovana");
        owner.setLastName("Owner");

        car = new Car();
        car.setId(10L);
        car.setBrand("BMW");
        car.setModel("320d");
        car.setOwner(owner);

        // Booking that ended 3 days ago (within 14-day window)
        completedBooking = new Booking();
        completedBooking.setId(100L);
        completedBooking.setStatus(BookingStatus.COMPLETED);
        completedBooking.setRenter(renter);
        completedBooking.setCar(car);
        completedBooking.setStartTime(LocalDateTime.now().minusDays(10));
        completedBooking.setEndTime(LocalDateTime.now().minusDays(3));

        // Booking that ended 20 days ago (outside 14-day window)
        expiredBooking = new Booking();
        expiredBooking.setId(200L);
        expiredBooking.setStatus(BookingStatus.COMPLETED);
        expiredBooking.setRenter(renter);
        expiredBooking.setCar(car);
        expiredBooking.setStartTime(LocalDateTime.now().minusDays(30));
        expiredBooking.setEndTime(LocalDateTime.now().minusDays(20));
    }

    // ========== P0-1: 14-Day Submission Window ==========

    @Nested
    @DisplayName("P0-1: 14-Day Submission Window")
    class SubmissionWindowTests {

        @Test
        @DisplayName("Should allow renter review within 14-day window")
        void shouldAllowRenterReviewWithinWindow() {
            RenterReviewRequestDTO dto = createValidRenterDTO(completedBooking.getId());

            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(bookingRepo.findById(100L)).thenReturn(Optional.of(completedBooking));
            when(bookingService.isBookingCompleted(completedBooking)).thenReturn(true);
            when(reviewRepo.existsByBookingAndDirection(completedBooking, ReviewDirection.FROM_USER)).thenReturn(false);
            when(reviewRepo.countReviewsByReviewerSince(eq(1L), any(Instant.class))).thenReturn(0L);
            when(reviewRepo.save(any(Review.class))).thenAnswer(inv -> {
                Review r = inv.getArgument(0);
                r.setId(1L);
                return r;
            });

            Review result = reviewService.createRenterReview(dto, "renter@test.com");

            assertThat(result).isNotNull();
            assertThat(result.getRating()).isBetween(1, 5);
            verify(reviewRepo).save(any(Review.class));
        }

        @Test
        @DisplayName("Should reject renter review outside 14-day window")
        void shouldRejectRenterReviewOutsideWindow() {
            RenterReviewRequestDTO dto = createValidRenterDTO(expiredBooking.getId());

            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(bookingRepo.findById(200L)).thenReturn(Optional.of(expiredBooking));
            when(bookingService.isBookingCompleted(expiredBooking)).thenReturn(true);
            when(reviewRepo.countReviewsByReviewerSince(eq(1L), any(Instant.class))).thenReturn(0L);

            assertThatThrownBy(() -> reviewService.createRenterReview(dto, "renter@test.com"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("istekao");
        }

        @Test
        @DisplayName("Should allow owner review within 14-day window")
        void shouldAllowOwnerReviewWithinWindow() {
            OwnerReviewRequestDTO dto = createValidOwnerDTO(completedBooking.getId());

            when(userRepo.findByEmail("owner@test.com")).thenReturn(Optional.of(owner));
            when(bookingRepo.findById(100L)).thenReturn(Optional.of(completedBooking));
            when(bookingService.isBookingCompleted(completedBooking)).thenReturn(true);
            when(reviewRepo.existsByBookingAndDirection(completedBooking, ReviewDirection.FROM_OWNER)).thenReturn(false);
            when(reviewRepo.countReviewsByReviewerSince(eq(2L), any(Instant.class))).thenReturn(0L);
            when(reviewRepo.save(any(Review.class))).thenAnswer(inv -> {
                Review r = inv.getArgument(0);
                r.setId(2L);
                return r;
            });

            Review result = reviewService.createOwnerReview(dto, "owner@test.com");

            assertThat(result).isNotNull();
            verify(reviewRepo).save(any(Review.class));
        }

        @Test
        @DisplayName("Should reject owner review outside 14-day window")
        void shouldRejectOwnerReviewOutsideWindow() {
            OwnerReviewRequestDTO dto = createValidOwnerDTO(expiredBooking.getId());

            when(userRepo.findByEmail("owner@test.com")).thenReturn(Optional.of(owner));
            when(bookingRepo.findById(200L)).thenReturn(Optional.of(expiredBooking));
            when(bookingService.isBookingCompleted(expiredBooking)).thenReturn(true);
            when(reviewRepo.countReviewsByReviewerSince(eq(2L), any(Instant.class))).thenReturn(0L);

            assertThatThrownBy(() -> reviewService.createOwnerReview(dto, "owner@test.com"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("istekao");
        }
    }

    // ========== P0-5: XSS Sanitization ==========

    @Nested
    @DisplayName("P0-5: XSS Sanitization")
    class SanitizationTests {

        @Test
        @DisplayName("Should strip HTML tags from review comment")
        void shouldStripHtmlTagsFromComment() {
            RenterReviewRequestDTO dto = createValidRenterDTO(completedBooking.getId());
            dto.setComment("Great car! <script>alert('xss')</script> Very clean.");

            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(bookingRepo.findById(100L)).thenReturn(Optional.of(completedBooking));
            when(bookingService.isBookingCompleted(completedBooking)).thenReturn(true);
            when(reviewRepo.existsByBookingAndDirection(completedBooking, ReviewDirection.FROM_USER)).thenReturn(false);
            when(reviewRepo.countReviewsByReviewerSince(eq(1L), any(Instant.class))).thenReturn(0L);
            when(reviewRepo.save(any(Review.class))).thenAnswer(inv -> {
                Review r = inv.getArgument(0);
                r.setId(1L);
                return r;
            });

            reviewService.createRenterReview(dto, "renter@test.com");

            verify(reviewRepo).save(argThat(review -> {
                // Should not contain script tags
                return review.getComment() != null
                        && !review.getComment().contains("<script>")
                        && !review.getComment().contains("</script>");
            }));
        }

        @Test
        @DisplayName("Should strip img/svg XSS payloads from owner comment")
        void shouldStripXssFromOwnerComment() {
            OwnerReviewRequestDTO dto = createValidOwnerDTO(completedBooking.getId());
            dto.setComment("Good renter <img src=x onerror=alert(1)> overall");

            when(userRepo.findByEmail("owner@test.com")).thenReturn(Optional.of(owner));
            when(bookingRepo.findById(100L)).thenReturn(Optional.of(completedBooking));
            when(bookingService.isBookingCompleted(completedBooking)).thenReturn(true);
            when(reviewRepo.existsByBookingAndDirection(completedBooking, ReviewDirection.FROM_OWNER)).thenReturn(false);
            when(reviewRepo.countReviewsByReviewerSince(eq(2L), any(Instant.class))).thenReturn(0L);
            when(reviewRepo.save(any(Review.class))).thenAnswer(inv -> {
                Review r = inv.getArgument(0);
                r.setId(2L);
                return r;
            });

            reviewService.createOwnerReview(dto, "owner@test.com");

            verify(reviewRepo).save(argThat(review ->
                    review.getComment() != null
                            && !review.getComment().contains("<img")
                            && !review.getComment().contains("onerror")
            ));
        }

        @Test
        @DisplayName("Should allow null comment without error")
        void shouldAllowNullComment() {
            RenterReviewRequestDTO dto = createValidRenterDTO(completedBooking.getId());
            dto.setComment(null);

            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(bookingRepo.findById(100L)).thenReturn(Optional.of(completedBooking));
            when(bookingService.isBookingCompleted(completedBooking)).thenReturn(true);
            when(reviewRepo.existsByBookingAndDirection(completedBooking, ReviewDirection.FROM_USER)).thenReturn(false);
            when(reviewRepo.countReviewsByReviewerSince(eq(1L), any(Instant.class))).thenReturn(0L);
            when(reviewRepo.save(any(Review.class))).thenAnswer(inv -> {
                Review r = inv.getArgument(0);
                r.setId(1L);
                return r;
            });

            assertThatCode(() -> reviewService.createRenterReview(dto, "renter@test.com"))
                    .doesNotThrowAnyException();
        }
    }

    // ========== Security: Booking Ownership ==========

    @Nested
    @DisplayName("Security: Booking Ownership Checks")
    class OwnershipTests {

        @Test
        @DisplayName("Should reject renter review for someone else's booking")
        void shouldRejectRenterReviewForOtherBooking() {
            User otherRenter = new User();
            otherRenter.setId(99L);
            otherRenter.setEmail("other@test.com");

            RenterReviewRequestDTO dto = createValidRenterDTO(completedBooking.getId());

            when(userRepo.findByEmail("other@test.com")).thenReturn(Optional.of(otherRenter));
            when(bookingRepo.findById(100L)).thenReturn(Optional.of(completedBooking));
            when(reviewRepo.countReviewsByReviewerSince(eq(99L), any(Instant.class))).thenReturn(0L);

            assertThatThrownBy(() -> reviewService.createRenterReview(dto, "other@test.com"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Unauthorized");
        }

        @Test
        @DisplayName("Should reject owner review for booking not on their car")
        void shouldRejectOwnerReviewForOtherCar() {
            User otherOwner = new User();
            otherOwner.setId(88L);
            otherOwner.setEmail("notowner@test.com");

            OwnerReviewRequestDTO dto = createValidOwnerDTO(completedBooking.getId());

            when(userRepo.findByEmail("notowner@test.com")).thenReturn(Optional.of(otherOwner));
            when(bookingRepo.findById(100L)).thenReturn(Optional.of(completedBooking));
            when(reviewRepo.countReviewsByReviewerSince(eq(88L), any(Instant.class))).thenReturn(0L);

            assertThatThrownBy(() -> reviewService.createOwnerReview(dto, "notowner@test.com"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Unauthorized");
        }

        @Test
        @DisplayName("Should reject review for incomplete booking")
        void shouldRejectReviewForIncompleteBooking() {
            RenterReviewRequestDTO dto = createValidRenterDTO(completedBooking.getId());

            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(bookingRepo.findById(100L)).thenReturn(Optional.of(completedBooking));
            when(bookingService.isBookingCompleted(completedBooking)).thenReturn(false);
            when(reviewRepo.countReviewsByReviewerSince(eq(1L), any(Instant.class))).thenReturn(0L);

            assertThatThrownBy(() -> reviewService.createRenterReview(dto, "renter@test.com"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("završene");
        }
    }

    // ========== P1-1: Duplicate Prevention ==========

    @Nested
    @DisplayName("P1-1: Duplicate Review Prevention")
    class DuplicateTests {

        @Test
        @DisplayName("Should reject duplicate renter review for same booking")
        void shouldRejectDuplicateRenterReview() {
            RenterReviewRequestDTO dto = createValidRenterDTO(completedBooking.getId());

            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(bookingRepo.findById(100L)).thenReturn(Optional.of(completedBooking));
            when(bookingService.isBookingCompleted(completedBooking)).thenReturn(true);
            when(reviewRepo.existsByBookingAndDirection(completedBooking, ReviewDirection.FROM_USER)).thenReturn(true);
            when(reviewRepo.countReviewsByReviewerSince(eq(1L), any(Instant.class))).thenReturn(0L);

            assertThatThrownBy(() -> reviewService.createRenterReview(dto, "renter@test.com"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("already reviewed");
        }

        @Test
        @DisplayName("Should reject duplicate owner review for same booking")
        void shouldRejectDuplicateOwnerReview() {
            OwnerReviewRequestDTO dto = createValidOwnerDTO(completedBooking.getId());

            when(userRepo.findByEmail("owner@test.com")).thenReturn(Optional.of(owner));
            when(bookingRepo.findById(100L)).thenReturn(Optional.of(completedBooking));
            when(bookingService.isBookingCompleted(completedBooking)).thenReturn(true);
            when(reviewRepo.existsByBookingAndDirection(completedBooking, ReviewDirection.FROM_OWNER)).thenReturn(true);
            when(reviewRepo.countReviewsByReviewerSince(eq(2L), any(Instant.class))).thenReturn(0L);

            assertThatThrownBy(() -> reviewService.createOwnerReview(dto, "owner@test.com"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("already reviewed");
        }
    }

    // ========== P0-2: Double-Blind Read-Path Visibility ==========

    @Nested
    @DisplayName("P0-2: Double-Blind Read-Path Visibility")
    class DoubleBlindReadPathTests {

        private Review makeReview(Long id, Booking booking, ReviewDirection direction, Instant createdAt) {
            Review r = new Review();
            r.setId(id);
            r.setBooking(booking);
            r.setDirection(direction);
            r.setCreatedAt(createdAt);
            r.setRating(4);
            r.setComment("Test review");
            r.setReviewer(direction == ReviewDirection.FROM_USER ? renter : owner);
            r.setReviewee(direction == ReviewDirection.FROM_USER ? owner : renter);
            r.setCar(car);
            return r;
        }

        @Test
        @DisplayName("P0-2: Visibility timeout constant is 14 days (not 7)")
        void visibilityTimeoutIs14Days() {
            assertThat(ReviewService.REVIEW_VISIBILITY_TIMEOUT_DAYS).isEqualTo(14);
        }

        @Test
        @DisplayName("getRecentReviews filters out non-visible reviews (no reciprocal, within timeout)")
        void getRecentReviewsShouldFilterInvisibleReviews() {
            // Review created 5 days ago — within 14-day timeout, no reciprocal
            Review recentUnpaired = makeReview(1L, completedBooking, ReviewDirection.FROM_USER,
                    Instant.now().minus(5, ChronoUnit.DAYS));
            // Review created 20 days ago — beyond timeout, always visible
            Review oldVisible = makeReview(2L, expiredBooking, ReviewDirection.FROM_USER,
                    Instant.now().minus(20, ChronoUnit.DAYS));

            when(reviewRepo.findRecentReviews(eq(ReviewDirection.FROM_USER), any()))
                    .thenReturn(List.of(recentUnpaired, oldVisible));

            // recentUnpaired has no reciprocal review (other direction)
            when(reviewRepo.existsOtherReview(completedBooking.getId(), ReviewDirection.FROM_OWNER))
                    .thenReturn(false);

            List<ReviewResponseDTO> result = reviewService.getRecentReviews();

            // Only the old visible review should pass the filter
            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo(2L);
        }

        @Test
        @DisplayName("getRecentReviews includes review when reciprocal exists (within timeout)")
        void getRecentReviewsShouldIncludeWhenReciprocal() {
            Review pairedReview = makeReview(1L, completedBooking, ReviewDirection.FROM_USER,
                    Instant.now().minus(2, ChronoUnit.DAYS));

            when(reviewRepo.findRecentReviews(eq(ReviewDirection.FROM_USER), any()))
                    .thenReturn(List.of(pairedReview));
            when(reviewRepo.existsOtherReview(completedBooking.getId(), ReviewDirection.FROM_OWNER))
                    .thenReturn(true); // reciprocal exists

            List<ReviewResponseDTO> result = reviewService.getRecentReviews();

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("getReviewsReceivedByEmail filters invisible reviews")
        void getReviewsReceivedByEmailShouldFilter() {
            Review invisible = makeReview(1L, completedBooking, ReviewDirection.FROM_USER,
                    Instant.now().minus(3, ChronoUnit.DAYS));

            when(currentUser.email()).thenReturn("owner@test.com");
            when(userRepo.findByEmail("owner@test.com")).thenReturn(Optional.of(owner));
            when(reviewRepo.findByReviewee(owner)).thenReturn(List.of(invisible));
            when(reviewRepo.existsOtherReview(completedBooking.getId(), ReviewDirection.FROM_OWNER))
                    .thenReturn(false);

            var result = reviewService.getReviewsReceivedByEmail("owner@test.com");

            // Invisible review should be filtered out
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("getReviewsGivenByOwner filters invisible reviews")
        void getReviewsGivenByOwnerShouldFilter() {
            Review invisible = makeReview(1L, completedBooking, ReviewDirection.FROM_OWNER,
                    Instant.now().minus(3, ChronoUnit.DAYS));

            when(currentUser.email()).thenReturn("owner@test.com");
            when(userRepo.findByEmail("owner@test.com")).thenReturn(Optional.of(owner));
            when(reviewRepo.findByReviewerAndDirection(owner, ReviewDirection.FROM_OWNER))
                    .thenReturn(List.of(invisible));
            when(reviewRepo.existsOtherReview(completedBooking.getId(), ReviewDirection.FROM_USER))
                    .thenReturn(false);

            var result = reviewService.getReviewsGivenByOwner("owner@test.com");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Review exactly at 14-day boundary is visible (timeout passed)")
        void reviewAtExact14DayBoundaryIsVisible() {
            Review atBoundary = makeReview(1L, completedBooking, ReviewDirection.FROM_USER,
                    Instant.now().minus(14, ChronoUnit.DAYS).minus(1, ChronoUnit.SECONDS));

            when(reviewRepo.findRecentReviews(eq(ReviewDirection.FROM_USER), any()))
                    .thenReturn(List.of(atBoundary));

            List<ReviewResponseDTO> result = reviewService.getRecentReviews();

            // 14+ days old — should be visible regardless of reciprocal
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("Review at 13 days is NOT visible without reciprocal")
        void reviewAt13DaysWithoutReciprocalIsNotVisible() {
            Review notYetTimeout = makeReview(1L, completedBooking, ReviewDirection.FROM_USER,
                    Instant.now().minus(13, ChronoUnit.DAYS));

            when(reviewRepo.findRecentReviews(eq(ReviewDirection.FROM_USER), any()))
                    .thenReturn(List.of(notYetTimeout));
            when(reviewRepo.existsOtherReview(completedBooking.getId(), ReviewDirection.FROM_OWNER))
                    .thenReturn(false);

            List<ReviewResponseDTO> result = reviewService.getRecentReviews();

            assertThat(result).isEmpty();
        }
    }

    // ========== P0-2: Double-Blind Notification ==========

    @Nested
    @DisplayName("P0-2: Double-Blind Notification")
    class DoubleBlindNotificationTests {

        @Test
        @DisplayName("Renter review notification should not reveal star rating")
        void renterReviewNotificationShouldNotRevealStars() {
            RenterReviewRequestDTO dto = createValidRenterDTO(completedBooking.getId());

            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(bookingRepo.findById(100L)).thenReturn(Optional.of(completedBooking));
            when(bookingService.isBookingCompleted(completedBooking)).thenReturn(true);
            when(reviewRepo.existsByBookingAndDirection(completedBooking, ReviewDirection.FROM_USER)).thenReturn(false);
            when(reviewRepo.countReviewsByReviewerSince(eq(1L), any(Instant.class))).thenReturn(0L);
            when(reviewRepo.save(any(Review.class))).thenAnswer(inv -> {
                Review r = inv.getArgument(0);
                r.setId(1L);
                return r;
            });

            reviewService.createRenterReview(dto, "renter@test.com");

            verify(notificationService).createNotification(argThat(notification -> {
                String msg = notification.getMessage();
                // Should not contain star emoji or any rating number
                return !msg.contains("⭐")
                        && !msg.matches(".*\\d+.*star.*")
                        && msg.contains("recenziju");
            }));
        }

        @Test
        @DisplayName("Owner review notification should not reveal star rating")
        void ownerReviewNotificationShouldNotRevealStars() {
            OwnerReviewRequestDTO dto = createValidOwnerDTO(completedBooking.getId());

            when(userRepo.findByEmail("owner@test.com")).thenReturn(Optional.of(owner));
            when(bookingRepo.findById(100L)).thenReturn(Optional.of(completedBooking));
            when(bookingService.isBookingCompleted(completedBooking)).thenReturn(true);
            when(reviewRepo.existsByBookingAndDirection(completedBooking, ReviewDirection.FROM_OWNER)).thenReturn(false);
            when(reviewRepo.countReviewsByReviewerSince(eq(2L), any(Instant.class))).thenReturn(0L);
            when(reviewRepo.save(any(Review.class))).thenAnswer(inv -> {
                Review r = inv.getArgument(0);
                r.setId(2L);
                return r;
            });

            reviewService.createOwnerReview(dto, "owner@test.com");

            verify(notificationService).createNotification(argThat(notification -> {
                String msg = notification.getMessage();
                return !msg.contains("⭐");
            }));
        }
    }

    // ========== Velocity Limit Tests ==========

    @Nested
    @DisplayName("Velocity Limits (Anti-Fake Review)")
    class VelocityTests {

        @Test
        @DisplayName("Should reject reviews exceeding hourly limit")
        void shouldRejectReviewsExceedingHourlyLimit() {
            RenterReviewRequestDTO dto = createValidRenterDTO(completedBooking.getId());

            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(reviewRepo.countReviewsByReviewerSince(eq(1L), any(Instant.class)))
                    .thenReturn(5L); // At hourly limit

            assertThatThrownBy(() -> reviewService.createRenterReview(dto, "renter@test.com"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Previše recenzija");
        }

        @Test
        @DisplayName("Should reject reviews exceeding daily limit")
        void shouldRejectReviewsExceedingDailyLimit() {
            RenterReviewRequestDTO dto = createValidRenterDTO(completedBooking.getId());

            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            // First call (hourly) returns 0, second call (daily) returns 10
            when(reviewRepo.countReviewsByReviewerSince(eq(1L), any(Instant.class)))
                    .thenReturn(0L)
                    .thenReturn(10L);

            assertThatThrownBy(() -> reviewService.createRenterReview(dto, "renter@test.com"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("dnevni limit");
        }
    }

    // ========== Helper Methods ==========

    private RenterReviewRequestDTO createValidRenterDTO(Long bookingId) {
        RenterReviewRequestDTO dto = new RenterReviewRequestDTO();
        dto.setBookingId(bookingId);
        dto.setCleanlinessRating(4);
        dto.setMaintenanceRating(5);
        dto.setCommunicationRating(4);
        dto.setConvenienceRating(3);
        dto.setAccuracyRating(5);
        dto.setComment("Excellent car, very clean!");
        return dto;
    }

    private OwnerReviewRequestDTO createValidOwnerDTO(Long bookingId) {
        OwnerReviewRequestDTO dto = new OwnerReviewRequestDTO();
        dto.setBookingId(bookingId);
        dto.setCommunicationRating(5);
        dto.setCleanlinessRating(4);
        dto.setTimelinessRating(5);
        dto.setRespectForRulesRating(4);
        dto.setComment("Great renter, very respectful.");
        return dto;
    }
}

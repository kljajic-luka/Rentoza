package org.example.rentoza.user;

import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.review.ReviewDirection;
import org.example.rentoza.review.ReviewRepository;
import org.example.rentoza.user.dto.ProfileDetailsDTO;
import org.example.rentoza.user.dto.ProfileReviewDTO;
import org.example.rentoza.user.dto.ProfileStatsDTO;
import org.example.rentoza.user.dto.UserProfileSummaryDTO;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class ProfileService {

    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;
    private final ReviewRepository reviewRepository;

    public ProfileService(
            UserRepository userRepository,
            BookingRepository bookingRepository,
            ReviewRepository reviewRepository
    ) {
        this.userRepository = userRepository;
        this.bookingRepository = bookingRepository;
        this.reviewRepository = reviewRepository;
    }

    public UserProfileSummaryDTO getProfileSummary(String email) {
        var user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        var userId = user.getId();

        return new UserProfileSummaryDTO(
                String.valueOf(userId),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                List.of(user.getRole().name()),
                user.getAvatarUrl(),
                user.getCreatedAt()
        );
    }

    public ProfileDetailsDTO getProfileDetails(String email) {
        var user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        var userId = user.getId();

        ReviewDirection incomingDirection = switch (user.getRole()) {
            case OWNER -> ReviewDirection.FROM_USER;
            case USER -> ReviewDirection.FROM_OWNER;
            default -> ReviewDirection.FROM_USER;
        };

        long completedTrips = bookingRepository.countByRenterIdAndStatus(userId, BookingStatus.COMPLETED);
        long hostedTrips = bookingRepository.countByOwnerIdAndStatus(userId, BookingStatus.COMPLETED);

        // P0-2 FIX: Visibility-filtered rating and reviews (double-blind enforcement)
        Instant visibilityTimeout = Instant.now().minus(14, ChronoUnit.DAYS);
        var rawAverage = reviewRepository.findVisibleAverageRatingForReviewee(userId, incomingDirection, visibilityTimeout);
        double averageRating = rawAverage != null ? Math.round(rawAverage * 10.0) / 10.0 : 0.0;

        var reviews = reviewRepository.findVisibleByRevieweeIdAndDirection(
                userId,
                incomingDirection,
                visibilityTimeout
        );

        var reviewDtos = reviews.stream()
                .map(review -> new ProfileReviewDTO(
                        review.getId(),
                        review.getRating(),
                        review.getComment(),
                        review.getCreatedAt(),
                        review.getDirection(),
                        review.getReviewer() != null ? review.getReviewer().getFirstName() : null,
                        review.getReviewer() != null ? review.getReviewer().getLastName() : null,
                        null
                ))
                .toList();

        var stats = new ProfileStatsDTO(
                completedTrips,
                hostedTrips,
                reviewDtos.size()
        );

        return new ProfileDetailsDTO(
                String.valueOf(userId),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole().name(),
                List.of(user.getRole().name()),
                user.getAvatarUrl(),
                user.getBio(),
                user.getCreatedAt(),
                averageRating,
                stats,
                reviewDtos,
                // DOB fields (enterprise-grade age management)
                user.getDateOfBirth(),
                user.getAge(), // Calculated from DOB dynamically
                user.isDobVerified(),
                // Phone verification fields
                user.isPhoneVerified(),
                user.getPhoneVerifiedAt(),
                user.getPendingPhone(),
                user.isPhoneVerified()
                        ? (user.getPendingPhone() != null ? "PENDING_CHANGE" : "VERIFIED")
                        : "UNVERIFIED"
        );
    }
}

package org.example.rentoza.mapper;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.dto.GuestBookingPreviewDTO;
import org.example.rentoza.dto.ReviewPreviewDTO;
import org.example.rentoza.review.Review;
import org.example.rentoza.user.User;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Maps Booking + User data to GuestBookingPreviewDTO for host approval workflow.
 * 
 * Enterprise-grade mapping includes:
 * - Guest demographics (age, verification status)
 * - Driving experience (license country, categories, tenure)
 * - Reliability stats (trip count, cancellation rate)
 * - Achievement badges (Experienced Guest, Top Rated)
 */
public class GuestBookingMapper {

    private static final DateTimeFormatter JOIN_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    /**
     * Map booking and user data to guest preview DTO.
     * 
     * @param booking The booking request
     * @param hostReviews Reviews from previous hosts about this guest
     * @param averageRating Guest's average star rating
     * @param tripCount Number of completed trips
     * @param cancelledTripsCount Number of guest-initiated cancellations
     * @return GuestBookingPreviewDTO with all enterprise-grade fields
     */
    public static GuestBookingPreviewDTO toDTO(
            Booking booking, 
            List<Review> hostReviews, 
            Double averageRating, 
            int tripCount,
            int cancelledTripsCount) {
        
        User renter = booking.getRenter();
        
        // Calculate enterprise achievement badges (NOT verification badges)
        // Verification status is shown separately via boolean flags
        List<String> badges = new ArrayList<>();
        
        // Experienced Guest: 10+ trips
        if (tripCount > 10) {
            badges.add("Iskusan gost");
        }
        
        // Top Rated: 4.5+ stars with at least 3 trips
        if (averageRating != null && averageRating >= 4.5 && tripCount >= 3) {
            badges.add("Top ocenjen");
        }
        
        // Reliable Guest: 0 cancellations with 5+ trips
        if (cancelledTripsCount == 0 && tripCount >= 5) {
            badges.add("Pouzdan");
        }
        
        // Calculate cancellation rate
        int totalBookings = tripCount + cancelledTripsCount;
        Double cancellationRate = totalBookings > 0 
            ? (cancelledTripsCount * 100.0) / totalBookings 
            : 0.0;

        return GuestBookingPreviewDTO.builder()
                // Basic Profile
                .profilePhotoUrl(renter.getAvatarUrl())
                .firstName(renter.getFirstName())
                .lastInitial(formatLastInitial(renter.getLastName()))
                .joinDate(renter.getCreatedAt() != null ? 
                        org.example.rentoza.config.timezone.SerbiaTimeZone.toLocalDateTime(renter.getCreatedAt())
                        .format(JOIN_DATE_FORMATTER) : "")
                
                // Verification Status
                .emailVerified(renter.isEnabled())
                .phoneVerified(renter.getPhone() != null && !renter.getPhone().isBlank())
                .identityVerified(Boolean.TRUE.equals(renter.getIsIdentityVerified()))
                .drivingEligibilityStatus(renter.getDriverLicenseStatus() != null 
                    ? renter.getDriverLicenseStatus().name() 
                    : "NOT_STARTED")
                
                // Guest Demographics
                .age(renter.getAge())
                .ageVerified(renter.isAgeVerified())
                
                // Driving Experience
                .licenseCountry(renter.getDriverLicenseCountry())
                .licenseCategories(renter.getDriverLicenseCategories())
                .licenseTenureMonths(renter.getDriverLicenseTenureMonths())
                .licenseExpiryDate(renter.getDriverLicenseExpiryDate())
                
                // Reliability Stats
                .starRating(averageRating != null ? averageRating : 0.0)
                .tripCount(tripCount)
                .cancelledTripsCount(cancelledTripsCount)
                .cancellationRate(cancellationRate)
                
                // Achievement Badges
                .badges(badges)
                
                // Host Reviews
                .hostReviews(hostReviews.stream()
                        .map(GuestBookingMapper::toReviewDTO)
                        .collect(Collectors.toList()))
                
                // Trip Details
                .requestedStartDateTime(booking.getStartTime())
                .requestedEndDateTime(booking.getEndTime())
                .message(null) // Message not in Booking entity currently
                .protectionPlan(booking.getInsuranceType())
                .build();
    }
    
    /**
     * Backward-compatible overload for callers not providing cancellation count.
     * @deprecated Use the full version with cancelledTripsCount parameter
     */
    @Deprecated
    public static GuestBookingPreviewDTO toDTO(
            Booking booking, 
            List<Review> hostReviews, 
            Double averageRating, 
            int tripCount) {
        return toDTO(booking, hostReviews, averageRating, tripCount, 0);
    }

    private static ReviewPreviewDTO toReviewDTO(Review review) {
        return ReviewPreviewDTO.builder()
                .rating(review.getRating())
                .comment(review.getComment())
                .build();
    }

    private static String formatLastInitial(String lastName) {
        if (lastName == null || lastName.isEmpty()) {
            return "";
        }
        return lastName.charAt(0) + ".";
    }
}


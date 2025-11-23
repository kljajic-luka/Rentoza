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

public class GuestBookingMapper {

    private static final DateTimeFormatter JOIN_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    public static GuestBookingPreviewDTO toDTO(Booking booking, List<Review> hostReviews, Double averageRating, int tripCount) {
        User renter = booking.getRenter();
        
        // Calculate badges
        List<String> badges = new ArrayList<>();
        if (tripCount > 10) {
            badges.add("Experienced Guest");
        }
        // Placeholder for identity verification check
        if (true) { 
            badges.add("Verified Identity");
        }

        return GuestBookingPreviewDTO.builder()
                .profilePhotoUrl(renter.getAvatarUrl())
                .firstName(renter.getFirstName())
                .lastInitial(formatLastInitial(renter.getLastName()))
                .joinDate(renter.getCreatedAt() != null ? 
                        java.time.LocalDateTime.ofInstant(renter.getCreatedAt(), java.time.ZoneId.systemDefault())
                        .format(JOIN_DATE_FORMATTER) : "")
                
                // Verification flags - assuming logic based on existing fields or placeholders if not available
                .emailVerified(true) // Assuming email is verified if they can book
                .phoneVerified(renter.getPhone() != null)
                .identityVerified(true) // Placeholder logic
                .drivingEligibilityStatus("APPROVED") // Placeholder logic
                
                .starRating(averageRating != null ? averageRating : 0.0)
                .tripCount(tripCount)
                .badges(badges)
                
                .hostReviews(hostReviews.stream()
                        .map(GuestBookingMapper::toReviewDTO)
                        .collect(Collectors.toList()))
                
                .requestedStartDateTime(booking.getStartDate().atStartOfDay()) // Booking has LocalDate
                .requestedEndDateTime(booking.getEndDate().atStartOfDay())
                .message(null) // Message not in Booking entity currently
                .protectionPlan(booking.getInsuranceType())
                .build();
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

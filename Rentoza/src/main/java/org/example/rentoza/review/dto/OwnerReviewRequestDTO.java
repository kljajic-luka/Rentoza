package org.example.rentoza.review.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * DTO for owner-to-renter review submission with category-based ratings.
 * Used when an owner completes a booking and reviews the renter's behavior.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OwnerReviewRequestDTO {

    @NotNull(message = "Booking ID is required")
    private Long bookingId;

    /**
     * Category ratings for renter behavior (all required, 1-5 stars)
     */
    @NotNull(message = "Communication rating is required")
    @Min(value = 1, message = "Rating must be at least 1")
    @Max(value = 5, message = "Rating cannot exceed 5")
    private Integer communicationRating;

    @NotNull(message = "Cleanliness rating is required")
    @Min(value = 1, message = "Rating must be at least 1")
    @Max(value = 5, message = "Rating cannot exceed 5")
    private Integer cleanlinessRating;

    @NotNull(message = "Timeliness rating is required")
    @Min(value = 1, message = "Rating must be at least 1")
    @Max(value = 5, message = "Rating cannot exceed 5")
    private Integer timelinessRating;

    @NotNull(message = "Respect for rules rating is required")
    @Min(value = 1, message = "Rating must be at least 1")
    @Max(value = 5, message = "Rating cannot exceed 5")
    private Integer respectForRulesRating;

    /**
     * Optional comment (max 500 characters)
     */
    @Size(max = 500, message = "Comment cannot exceed 500 characters")
    private String comment;
}

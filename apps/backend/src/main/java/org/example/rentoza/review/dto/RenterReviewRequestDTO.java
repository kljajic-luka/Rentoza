package org.example.rentoza.review.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * DTO for renter-to-owner review submission with category-based ratings.
 * Used when a renter completes a trip and reviews their experience.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RenterReviewRequestDTO {

    @NotNull(message = "Booking ID is required")
    private Long bookingId;

    /**
     * Category ratings (all required, 1-5 stars)
     */
    @NotNull(message = "Cleanliness rating is required")
    @Min(value = 1, message = "Rating must be at least 1")
    @Max(value = 5, message = "Rating cannot exceed 5")
    private Integer cleanlinessRating;

    @NotNull(message = "Maintenance rating is required")
    @Min(value = 1, message = "Rating must be at least 1")
    @Max(value = 5, message = "Rating cannot exceed 5")
    private Integer maintenanceRating;

    @NotNull(message = "Communication rating is required")
    @Min(value = 1, message = "Rating must be at least 1")
    @Max(value = 5, message = "Rating cannot exceed 5")
    private Integer communicationRating;

    @NotNull(message = "Convenience rating is required")
    @Min(value = 1, message = "Rating must be at least 1")
    @Max(value = 5, message = "Rating cannot exceed 5")
    private Integer convenienceRating;

    @NotNull(message = "Accuracy rating is required")
    @Min(value = 1, message = "Rating must be at least 1")
    @Max(value = 5, message = "Rating cannot exceed 5")
    private Integer accuracyRating;

    /**
     * Optional comment (max 500 characters)
     */
    @Size(max = 500, message = "Comment cannot exceed 500 characters")
    private String comment;
}

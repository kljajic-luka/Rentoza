package org.example.rentoza.review.dto;

import lombok.Getter;
import lombok.Setter;
import org.example.rentoza.review.ReviewDirection;

@Getter
@Setter
public class ReviewRequestDTO {
    private Long carId;
    private Long bookingId;
    private ReviewDirection direction = ReviewDirection.FROM_USER;
    private int rating;
    private String comment;
}

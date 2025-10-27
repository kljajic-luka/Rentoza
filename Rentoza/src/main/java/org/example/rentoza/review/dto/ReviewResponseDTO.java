package org.example.rentoza.review.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@AllArgsConstructor
public class ReviewResponseDTO {
    private Long id;
    private int rating;
    private String comment;
    private String reviewerEmail;
    private Long carId;
    private Instant createdAt;
}
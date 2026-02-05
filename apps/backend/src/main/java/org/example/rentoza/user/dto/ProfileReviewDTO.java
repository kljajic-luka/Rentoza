package org.example.rentoza.user.dto;

import org.example.rentoza.review.ReviewDirection;

import java.time.Instant;

public record ProfileReviewDTO(
        Long id,
        int rating,
        String comment,
        Instant createdAt,
        ReviewDirection direction,
        String reviewerFirstName,
        String reviewerLastName,
        String reviewerAvatarUrl
) {}

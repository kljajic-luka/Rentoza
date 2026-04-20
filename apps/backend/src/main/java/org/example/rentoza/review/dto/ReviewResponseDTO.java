package org.example.rentoza.review.dto;

import java.time.Instant;

public record ReviewResponseDTO(
        Long id,
        int rating,
        String comment,
        Instant createdAt,
        org.example.rentoza.review.ReviewDirection direction,
        String reviewerFirstName,
        String reviewerLastName,
        String reviewerAvatarUrl,
        String revieweeFirstName,
        String revieweeLastName,
        String revieweeAvatarUrl,
        Long carId,
        String carBrand,
        String carModel,
        Integer carYear,
        String carLocation
) {}

package org.example.rentoza.review.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

public record ReviewResponseDTO(
        Long id,
        int rating,
        String comment,
        Instant createdAt,
        String reviewerFirstName,
        String reviewerLastName
) {}
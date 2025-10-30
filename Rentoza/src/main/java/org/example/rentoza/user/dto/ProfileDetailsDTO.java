package org.example.rentoza.user.dto;

import java.time.Instant;
import java.util.List;

public record ProfileDetailsDTO(
        String id,
        String firstName,
        String lastName,
        String email,
        String phone,
        String role,
        List<String> roles,
        String avatarUrl,
        Instant createdAt,
        double averageRating,
        ProfileStatsDTO stats,
        List<ProfileReviewDTO> reviews
) {}

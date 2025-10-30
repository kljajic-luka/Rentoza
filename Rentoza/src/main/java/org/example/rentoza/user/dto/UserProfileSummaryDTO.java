package org.example.rentoza.user.dto;

import java.time.Instant;
import java.util.List;

public record UserProfileSummaryDTO(
        String id,
        String firstName,
        String lastName,
        String email,
        String phone,
        List<String> roles,
        String avatarUrl,
        Instant createdAt
) {}

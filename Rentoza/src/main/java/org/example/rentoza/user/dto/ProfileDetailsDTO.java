package org.example.rentoza.user.dto;

import java.time.Instant;
import java.time.LocalDate;
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
        String bio,
        Instant createdAt,
        double averageRating,
        ProfileStatsDTO stats,
        List<ProfileReviewDTO> reviews,
        
        // ========== Age/DOB Fields (Enterprise-Grade) ==========
        /** User's date of birth (ISO format: YYYY-MM-DD) */
        LocalDate dateOfBirth,
        /** Calculated age from DOB (null if DOB not set) */
        Integer age,
        /** Whether DOB was verified via official document (license OCR) */
        boolean dobVerified
) {
    /**
     * Constructor without DOB fields (backward compatibility).
     */
    public ProfileDetailsDTO(
            String id, String firstName, String lastName, String email, String phone,
            String role, List<String> roles, String avatarUrl, String bio,
            Instant createdAt, double averageRating, ProfileStatsDTO stats,
            List<ProfileReviewDTO> reviews
    ) {
        this(id, firstName, lastName, email, phone, role, roles, avatarUrl, bio,
             createdAt, averageRating, stats, reviews, null, null, false);
    }
}

package org.example.rentoza.user.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
        boolean dobVerified,

        // ========== Phone Verification Fields ==========
        boolean phoneVerified,
        LocalDateTime phoneVerifiedAt,
        String pendingPhone,
        String phoneVerificationState
) {
    /**
     * Constructor without DOB/phone fields (backward compatibility).
     */
    public ProfileDetailsDTO(
            String id, String firstName, String lastName, String email, String phone,
            String role, List<String> roles, String avatarUrl, String bio,
            Instant createdAt, double averageRating, ProfileStatsDTO stats,
            List<ProfileReviewDTO> reviews
    ) {
        this(id, firstName, lastName, email, phone, role, roles, avatarUrl, bio,
             createdAt, averageRating, stats, reviews, null, null, false,
             false, null, null, null);
    }

    /**
     * Constructor without phone fields (DOB backward compatibility).
     */
    public ProfileDetailsDTO(
            String id, String firstName, String lastName, String email, String phone,
            String role, List<String> roles, String avatarUrl, String bio,
            Instant createdAt, double averageRating, ProfileStatsDTO stats,
            List<ProfileReviewDTO> reviews,
            LocalDate dateOfBirth, Integer age, boolean dobVerified
    ) {
        this(id, firstName, lastName, email, phone, role, roles, avatarUrl, bio,
             createdAt, averageRating, stats, reviews, dateOfBirth, age, dobVerified,
             false, null, null, null);
    }
}

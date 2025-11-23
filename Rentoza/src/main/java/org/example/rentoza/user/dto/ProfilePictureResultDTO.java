package org.example.rentoza.user.dto;

/**
 * Response DTO for profile picture upload operations.
 *
 * @param profilePictureUrl The URL to the uploaded profile picture,
 *                          including cache-busting timestamp parameter.
 *                          Example: "/uploads/profile-pictures/123.jpg?t=1700000000"
 */
public record ProfilePictureResultDTO(
        String profilePictureUrl
) {}

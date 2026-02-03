package org.example.rentoza.user.dto;

/**
 * Response DTO for profile picture upload operations.
 *
 * @param profilePictureUrl The URL to the uploaded profile picture in Supabase Storage.
 *                          Format: "https://{supabase-project}.supabase.co/storage/v1/object/public/user-avatars/users/{userId}/avatar/avatar_{timestamp}.jpg"
 */
public record ProfilePictureResultDTO(
        String profilePictureUrl
) {}

package org.example.rentoza.user;

import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.security.JwtUserPrincipal;
import org.example.rentoza.user.dto.ProfilePictureResultDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * REST Controller for profile picture upload operations.
 *
 * Security:
 * - All endpoints require authentication
 * - Users can only modify their own profile picture (RBAC enforced)
 * - File validation happens in ProfilePictureService
 *
 * Endpoint:
 * POST /api/users/me/profile-picture
 *   - Accepts multipart/form-data with "file" parameter
 *   - Returns { profilePictureUrl: "https://{supabase-project}.supabase.co/storage/v1/object/public/user-avatars/..." }
 */
@RestController
@RequestMapping("/api/users/me")
public class ProfilePictureController {

    private static final Logger log = LoggerFactory.getLogger(ProfilePictureController.class);

    private final ProfilePictureService profilePictureService;
    private final UserService userService;
    private final CurrentUser currentUser;

    public ProfilePictureController(
            ProfilePictureService profilePictureService,
            UserService userService,
            CurrentUser currentUser
    ) {
        this.profilePictureService = profilePictureService;
        this.userService = userService;
        this.currentUser = currentUser;
    }

    /**
     * Upload a new profile picture for the authenticated user.
     *
     * Security flow:
     * 1. JWT token validated by JwtAuthFilter
     * 2. @PreAuthorize ensures user is authenticated
     * 3. CurrentUser extracts user ID from security context
     * 4. ProfilePictureService validates and processes the file
     * 5. UserService updates the user's avatarUrl in database
     *
     * @param file The image file (JPEG, PNG, or WebP, max 4MB)
     * @return ProfilePictureResultDTO with the new avatar URL
     */
    @PostMapping("/profile-picture")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> uploadProfilePicture(
            @RequestParam("file") MultipartFile file
    ) {
        try {
            // Get authenticated user's ID from security context
            JwtUserPrincipal principal = currentUser.getPrincipal()
                    .orElseThrow(() -> new SecurityException("User not authenticated"));

            Long userId = principal.id();
            log.info("📸 Profile picture upload request from user {}", userId);

            // Process and save the profile picture
            ProfilePictureResultDTO result = profilePictureService.uploadProfilePicture(userId, file);

            // Update user's avatarUrl in database
            userService.updateAvatarUrl(userId, result.profilePictureUrl());

            log.info("✅ Profile picture updated for user {}: {}", userId, result.profilePictureUrl());

            // Return success with no-store cache directive
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .header("X-Content-Type-Options", "nosniff")
                    .body(result);

        } catch (ProfilePictureService.ProfilePictureException e) {
            // Validation or processing error (client error)
            log.warn("⚠️ Profile picture validation failed: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));

        } catch (SecurityException e) {
            // Authentication error
            log.warn("🔒 Unauthorized profile picture upload attempt: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));

        } catch (Exception e) {
            // Unexpected server error
            log.error("❌ Profile picture upload failed unexpectedly", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "An unexpected error occurred. Please try again."));
        }
    }

    /**
     * Delete the authenticated user's profile picture.
     * (Optional endpoint for future use)
     */
    @DeleteMapping("/profile-picture")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> deleteProfilePicture() {
        try {
            JwtUserPrincipal principal = currentUser.getPrincipal()
                    .orElseThrow(() -> new SecurityException("User not authenticated"));

            Long userId = principal.id();

            // Get current avatar URL before deletion
            String currentAvatarUrl = userService.getUserById(userId)
                    .map(user -> user.getAvatarUrl())
                    .orElse(null);

            // Delete the file
            profilePictureService.deleteProfilePicture(userId, currentAvatarUrl);

            // Clear avatarUrl in database
            userService.updateAvatarUrl(userId, null);

            log.info("🗑️ Profile picture deleted for user {}", userId);

            return ResponseEntity.ok()
                    .body(Map.of("message", "Profile picture deleted successfully"));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));

        } catch (Exception e) {
            log.error("❌ Profile picture deletion failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete profile picture"));
        }
    }
}

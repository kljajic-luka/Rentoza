package org.example.rentoza.user;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.security.JwtUserPrincipal;

import org.example.rentoza.user.dto.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService service;
    private final ProfileService profileService;
    private final CurrentUser currentUser;

    public UserController(UserService service, ProfileService profileService, CurrentUser currentUser) {
        this.service = service;
        this.profileService = profileService;
        this.currentUser = currentUser;
    }

    /**
     * GET /api/users/me - Backend-verified session endpoint for RLS synchronization
     * Returns authenticated user's complete profile with backend-verified roles
     * Used by frontend for session initialization and role verification
     * 
     * @return CurrentUserDTO with id, email, roles array, authenticated flag
     */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getMyProfile() {
        try {
            // Use CurrentUser to get backend-verified authentication context
            JwtUserPrincipal principal = currentUser.getPrincipal()
                    .orElseThrow(() -> new EntityNotFoundException("User not authenticated"));
            
            // Fetch complete user data from database
            var user = service.getUserById(principal.id())
                    .orElseThrow(() -> new EntityNotFoundException("User not found"));
            
            // Return complete user profile with roles array (not single role string)
            return ResponseEntity.ok(Map.of(
                    "id", user.getId(),
                    "email", user.getEmail(),
                    "firstName", user.getFirstName(),
                    "lastName", user.getLastName(),
                    "phone", user.getPhone() != null ? user.getPhone() : "",
                    "age", user.getAge() != null ? user.getAge() : 0,
                    "avatarUrl", user.getAvatarUrl() != null ? user.getAvatarUrl() : "",
                    "roles", List.of(user.getRole().name()), // ✅ Roles as array
                    "authenticated", true  // ✅ Backend verification flag
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", e.getMessage(),
                    "authenticated", false
            ));
        }
    }

    @PatchMapping("/profile")
    public ResponseEntity<?> updateProfile(
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal,
            @Valid @RequestBody UserProfileDTO dto
    ) {
        try {
            User updated = service.updateProfile(principal.getUsername(), dto);

            return ResponseEntity.ok(new UserResponseDTO(
                    updated.getId(),
                    updated.getFirstName(),
                    updated.getLastName(),
                    updated.getEmail(),
                    updated.getPhone(),
                    updated.getAge(),
                    updated.getRole().name()
            ));

        } catch (io.jsonwebtoken.JwtException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Token expired or invalid"));
        } catch (RuntimeException e) {
            if ("Missing or invalid token".equals(e.getMessage())) {
                return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
            }
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/profile")
    public ResponseEntity<?> getProfileSummary(@org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal) {
        try {
            return ResponseEntity.ok(profileService.getProfileSummary(principal.getUsername()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/profile/details")
    public ResponseEntity<?> getProfileDetails(@org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal) {
        try {
            return ResponseEntity.ok(profileService.getProfileDetails(principal.getUsername()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * PATCH /api/users/me - Secure partial profile update endpoint
     * Only allows updating safe fields: phone, avatarUrl, bio
     * Sensitive fields (name, email, role) are blocked to enforce identity integrity
     */
    @PatchMapping("/me")
    public ResponseEntity<?> updateMyProfile(
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal,
            @Valid @RequestBody UpdateProfileRequestDTO dto
    ) {
        try {
            User updated = service.updateProfileSecure(principal.getUsername(), dto);

            // Return updated ProfileDetailsDTO to refresh frontend state
            ProfileDetailsDTO details = profileService.getProfileDetails(principal.getUsername());
            return ResponseEntity.ok(details);

        } catch (UserService.BadRequestException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
    }

    /**
     * Get user profile by ID (for chat service enrichment)
     * Public endpoint for inter-service communication
     */
    @GetMapping("/profile/{userId}")
    public ResponseEntity<?> getUserProfileById(@PathVariable Long userId) {
        try {
            User user = service.getUserById(userId)
                    .orElseThrow(() -> new EntityNotFoundException("User not found with ID: " + userId));
            return ResponseEntity.ok(new UserResponseDTO(
                    user.getId(),
                    user.getFirstName(),
                    user.getLastName(),
                    user.getEmail(),
                    user.getPhone(),
                    user.getAge(),
                    user.getRole().name()
            ));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }


}

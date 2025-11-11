package org.example.rentoza.user;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.security.JwtUserPrincipal;
import org.example.rentoza.security.JwtUtil;
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
    private final JwtUtil jwtUtil;
    private final CurrentUser currentUser;

    public UserController(UserService service, ProfileService profileService, JwtUtil jwtUtil, CurrentUser currentUser) {
        this.service = service;
        this.profileService = profileService;
        this.jwtUtil = jwtUtil;
        this.currentUser = currentUser;
    }

    // ✅ REGISTER
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody UserRegisterDTO dto) {
        try {
            User user = service.register(dto);
            return ResponseEntity.ok(new UserResponseDTO(
                    user.getId(),
                    user.getFirstName(),
                    user.getLastName(),
                    user.getEmail(),
                    user.getPhone(),
                    user.getAge(),
                    user.getRole().name()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ✅ LOGIN
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody UserLoginDTO dto) {
        var userOpt = service.getUserByEmail(dto.getEmail());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "User not found"));
        }

        var user = userOpt.get();
        if (!service.passwordMatches(dto.getPassword(), user.getPassword())) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }

        String token = jwtUtil.generateToken(user.getEmail(), user.getRole().name());

        // ✅ Build proper UserResponseDTO
        UserResponseDTO userResponse = new UserResponseDTO(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                user.getAge(),
                user.getRole().name()
        );

        // ✅ Pass UserResponseDTO instead of String
        AuthResponseDTO response = new AuthResponseDTO(token, null, userResponse);
        return ResponseEntity.ok(response);
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
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody UserProfileDTO dto
    ) {
        try {
            String email = extractEmail(authHeader);

            if (email == null) {
                return ResponseEntity.status(401).body(Map.of("error", "Invalid token"));
            }

            User updated = service.updateProfile(email, dto);

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
    public ResponseEntity<?> getProfileSummary(@RequestHeader("Authorization") String authHeader) {
        try {
            String email = extractEmail(authHeader);
            return ResponseEntity.ok(profileService.getProfileSummary(email));
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/profile/details")
    public ResponseEntity<?> getProfileDetails(@RequestHeader("Authorization") String authHeader) {
        try {
            String email = extractEmail(authHeader);
            return ResponseEntity.ok(profileService.getProfileDetails(email));
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
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody UpdateProfileRequestDTO dto
    ) {
        try {
            String email = extractEmail(authHeader);
            User updated = service.updateProfileSecure(email, dto);

            // Return updated ProfileDetailsDTO to refresh frontend state
            ProfileDetailsDTO details = profileService.getProfileDetails(email);
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

    private String extractEmail(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("Missing or invalid token");
        }
        String token = authHeader.substring(7);
        return jwtUtil.getEmailFromToken(token);
    }
}

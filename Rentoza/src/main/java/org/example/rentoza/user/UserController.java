package org.example.rentoza.user;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.example.rentoza.security.JwtUtil;
import org.example.rentoza.user.dto.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService service;
    private final ProfileService profileService;
    private final JwtUtil jwtUtil;

    public UserController(UserService service, ProfileService profileService, JwtUtil jwtUtil) {
        this.service = service;
        this.profileService = profileService;
        this.jwtUtil = jwtUtil;
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

    @GetMapping("/me")
    public ResponseEntity<?> getMyProfile(@RequestHeader("Authorization") String authHeader) {
        try {
            String email = extractEmail(authHeader);
            var user = service.getUserByEmail(email)
                    .orElseThrow(() -> new EntityNotFoundException("User not found"));
            return ResponseEntity.ok(new UserResponseDTO(
                    user.getId(), user.getFirstName(), user.getLastName(), user.getEmail(), user.getPhone(), user.getAge(), user.getRole().name()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
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

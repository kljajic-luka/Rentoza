package org.example.rentoza.user;

import jakarta.validation.Valid;
import org.example.rentoza.security.JwtUtil;
import org.example.rentoza.user.dto.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "*")
public class UserController {

    private final UserService service;
    private final JwtUtil jwtUtil;

    public UserController(UserService service, JwtUtil jwtUtil) {
        this.service = service;
        this.jwtUtil = jwtUtil;
    }

    // ✅ REGISTER
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody UserRegisterDTO dto) {
        try {
            User user = service.register(dto);
            return ResponseEntity.ok(new UserResponseDTO(
                    user.getId(),
                    user.getFullName(),
                    user.getEmail(),
                    user.getPhone(),
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

        String token = jwtUtil.generateToken(user.getEmail());
        return ResponseEntity.ok(new AuthResponseDTO(token, user.getEmail(), user.getRole().name()));
    }

    // ✅ GET USER BY EMAIL
    @GetMapping("/{email}")
    public ResponseEntity<?> getUserByEmail(@PathVariable String email) {
        return service.getUserByEmail(email)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity
                        .status(404)
                        .body(Map.of("error", "User not found")));
    }

    // ✅ UPDATE PROFILE
    @PutMapping("/profile/{email}")
    public ResponseEntity<?> updateProfile(
            @PathVariable String email,
            @Valid @RequestBody UserProfileDTO dto
    ) {
        try {
            User updated = service.updateProfile(email, dto);
            return ResponseEntity.ok(new UserResponseDTO(
                    updated.getId(),
                    updated.getFullName(),
                    updated.getEmail(),
                    updated.getPhone(),
                    updated.getRole().name()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
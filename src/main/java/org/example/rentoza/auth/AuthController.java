package org.example.rentoza.auth;

import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.example.rentoza.security.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private final UserRepository userRepo;
    private final PasswordEncoder encoder;
    private final JwtUtil jwtUtil;

    public AuthController(UserRepository userRepo, PasswordEncoder encoder, JwtUtil jwtUtil) {
        this.userRepo = userRepo;
        this.encoder = encoder;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> loginRequest) {
        String email = loginRequest.get("email");
        String password = loginRequest.get("password");

        User user = userRepo.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!encoder.matches(password, user.getPassword())) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }

        String token = jwtUtil.generateToken(email);
        return ResponseEntity.ok(Map.of(
                "token", token,
                "email", email,
                "role", user.getRole()
        ));
    }

    // 🔁 refresh token endpoint
    @PostMapping("/refresh-token")
    public ResponseEntity<?> refreshToken(@RequestHeader("Authorization") String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return ResponseEntity.status(401).body(Map.of("error", "Missing or invalid token"));
        }

        String token = header.substring(7);
        try {
            String newToken = jwtUtil.refreshToken(token);
            return ResponseEntity.ok(Map.of("token", newToken));
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid or expired token"));
        }
    }
}
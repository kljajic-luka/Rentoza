package org.example.rentoza.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.example.rentoza.security.JwtUtil;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.example.rentoza.user.UserService;
import org.example.rentoza.user.dto.AuthResponseDTO;
import org.example.rentoza.user.dto.UserLoginDTO;
import org.example.rentoza.user.dto.UserRegisterDTO;
import org.example.rentoza.user.dto.UserResponseDTO;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "http://localhost:4200", allowCredentials = "true")
public class AuthController {

    private static final String REFRESH_COOKIE = "rentoza_refresh";

    private final UserService userService;
    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokenService;

    public AuthController(UserService userService,
                          UserRepository userRepo,
                          PasswordEncoder passwordEncoder,
                          JwtUtil jwtUtil,
                          RefreshTokenService refreshTokenService) {
        this.userService = userService;
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.refreshTokenService = refreshTokenService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody UserRegisterDTO dto) {
        try {
            User user = userService.register(dto);
            String accessToken = jwtUtil.generateToken(user.getEmail(), user.getRole().name());
            String refreshRaw = refreshTokenService.issue(user.getEmail());

            // set HttpOnly secure cookie for refresh token
            ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, refreshRaw)
                    .httpOnly(true)
                    .path("/api/auth/refresh")
                    .sameSite("Strict")
                    .maxAge(Duration.ofDays(14))
                    .build();

            UserResponseDTO userResponse = new UserResponseDTO(
                    user.getId(),
                    user.getFirstName(),
                    user.getLastName(),
                    user.getEmail(),
                    user.getPhone(),
                    user.getRole().name()
            );

            AuthResponseDTO response = new AuthResponseDTO(accessToken, null, userResponse);
            return ResponseEntity.ok()
                    .header("Set-Cookie", cookie.toString())
                    .body(response);

        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody UserLoginDTO dto, HttpServletResponse res) {
        var userOpt = userService.getUserByEmail(dto.getEmail());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error","User not found"));
        }

        User user = userOpt.get();
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            return ResponseEntity.status(401).body(Map.of("error","Invalid credentials"));
        }

        String accessToken = jwtUtil.generateToken(user.getEmail(), user.getRole().name());
        String refreshRaw = refreshTokenService.issue(user.getEmail());

        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, refreshRaw)
                .httpOnly(true)
                .secure(true)
                .path("/api/auth/refresh")
                .sameSite("Strict")
                .maxAge(Duration.ofDays(14))
                .build();
        res.addHeader("Set-Cookie", cookie.toString());

        UserResponseDTO userResponse = new UserResponseDTO(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole().name()
        );

        AuthResponseDTO response = new AuthResponseDTO(accessToken, null, userResponse);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(
            @CookieValue(value = REFRESH_COOKIE, required = false) String refreshCookie,
            HttpServletResponse res) {

        // ✅ No cookie = guest user, not an error
        if (refreshCookie == null || refreshCookie.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("message", "No session"));
        }

        try {
            var result = refreshTokenService.rotate(refreshCookie);
            var accessToken = jwtUtil.generateToken(result.email(), "USER");
            ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, result.newToken())
                    .httpOnly(true).secure(true).path("/api/auth/refresh").sameSite("Strict")
                    .maxAge(Duration.ofDays(14)).build();
            res.addHeader("Set-Cookie", cookie.toString());
            return ResponseEntity.ok(Map.of("accessToken", accessToken));
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value="Authorization", required=false) String authHeader,
                                    @CookieValue(value = REFRESH_COOKIE, required = false) String refreshCookie,
                                    HttpServletResponse res) {
        if (refreshCookie != null) {
            String email = null;
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                email = jwtUtil.getEmailFromToken(authHeader.substring(7));
            }
            if (email != null) {
                refreshTokenService.revokeAll(email);
            }
        }

        // clear cookie
        ResponseCookie cleared = ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .path("/api/auth/refresh")
                .sameSite("Strict")
                .maxAge(0)
                .build();
        res.addHeader("Set-Cookie", cleared.toString());

        return ResponseEntity.ok(Map.of("status","logged_out"));
    }
}
package org.example.rentoza.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.example.rentoza.config.AppProperties;
import org.example.rentoza.security.JwtUtil;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.example.rentoza.user.UserService;
import org.example.rentoza.user.dto.AuthResponseDTO;
import org.example.rentoza.user.dto.UserLoginDTO;
import org.example.rentoza.user.dto.UserRegisterDTO;
import org.example.rentoza.user.dto.UserResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

/**
 * Authentication endpoints for user registration, login, token refresh, and logout.
 * Uses JWT for access tokens and secure HttpOnly cookies for refresh tokens.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String REFRESH_COOKIE = "rentoza_refresh";

    private final UserService userService;
    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RefreshTokenServiceEnhanced refreshTokenService;
    private final AppProperties appProperties;

    public AuthController(UserService userService,
                          UserRepository userRepo,
                          PasswordEncoder passwordEncoder,
                          JwtUtil jwtUtil,
                          RefreshTokenServiceEnhanced refreshTokenService,
                          AppProperties appProperties) {
        this.userService = userService;
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.refreshTokenService = refreshTokenService;
        this.appProperties = appProperties;
    }

    /**
     * Create a standardized refresh token cookie with environment-based security settings
     */
    private ResponseCookie createRefreshTokenCookie(String token) {
        return ResponseCookie.from(REFRESH_COOKIE, token)
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .path("/api/auth/refresh")
                .domain(appProperties.getCookie().getDomain())
                .sameSite(appProperties.getCookie().getSameSite())
                .maxAge(Duration.ofDays(14))
                .build();
    }

    /**
     * Create a cookie to clear the refresh token
     */
    private ResponseCookie clearRefreshTokenCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .path("/api/auth/refresh")
                .domain(appProperties.getCookie().getDomain())
                .sameSite(appProperties.getCookie().getSameSite())
                .maxAge(0)
                .build();
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody UserRegisterDTO dto, HttpServletRequest request) {
        try {
            User user = userService.register(dto);
            String accessToken = jwtUtil.generateToken(user.getEmail(), user.getRole().name(), user.getId());

            // Issue refresh token with IP/UserAgent fingerprinting
            String ipAddress = RefreshTokenServiceEnhanced.extractIpAddress(request);
            String userAgent = RefreshTokenServiceEnhanced.extractUserAgent(request);
            String refreshRaw = refreshTokenService.issue(user.getEmail(), ipAddress, userAgent);

            ResponseCookie cookie = createRefreshTokenCookie(refreshRaw);

            UserResponseDTO userResponse = new UserResponseDTO(
                    user.getId(),
                    user.getFirstName(),
                    user.getLastName(),
                    user.getEmail(),
                    user.getPhone(),
                    user.getRole().name()
            );

            log.info("User registered successfully: email={}, role={}", user.getEmail(), user.getRole());

            AuthResponseDTO response = new AuthResponseDTO(accessToken, null, userResponse);
            return ResponseEntity.ok()
                    .header("Set-Cookie", cookie.toString())
                    .body(response);

        } catch (RuntimeException e) {
            log.warn("Registration failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Registration failed"));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody UserLoginDTO dto,
                                   HttpServletRequest request,
                                   HttpServletResponse res) {
        var userOpt = userService.getUserByEmail(dto.getEmail());
        if (userOpt.isEmpty()) {
            log.warn("Login attempt for non-existent user: email={}", dto.getEmail());
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }

        User user = userOpt.get();
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            log.warn("Failed login attempt: email={}", dto.getEmail());
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }

        String accessToken = jwtUtil.generateToken(user.getEmail(), user.getRole().name(), user.getId());

        // Issue refresh token with IP/UserAgent fingerprinting
        String ipAddress = RefreshTokenServiceEnhanced.extractIpAddress(request);
        String userAgent = RefreshTokenServiceEnhanced.extractUserAgent(request);
        String refreshRaw = refreshTokenService.issue(user.getEmail(), ipAddress, userAgent);

        ResponseCookie cookie = createRefreshTokenCookie(refreshRaw);
        res.addHeader("Set-Cookie", cookie.toString());

        UserResponseDTO userResponse = new UserResponseDTO(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole().name()
        );

        log.info("User logged in successfully: email={}, role={}", user.getEmail(), user.getRole());

        AuthResponseDTO response = new AuthResponseDTO(accessToken, null, userResponse);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(
            @CookieValue(value = REFRESH_COOKIE, required = false) String refreshCookie,
            HttpServletRequest request,
            HttpServletResponse res) {

        if (refreshCookie == null || refreshCookie.isBlank()) {
            log.debug("Refresh attempt with no cookie - guest user");
            return ResponseEntity.status(401).body(Map.of("error", "No session"));
        }

        try {
            // Extract IP/UserAgent for fingerprint validation
            String ipAddress = RefreshTokenServiceEnhanced.extractIpAddress(request);
            String userAgent = RefreshTokenServiceEnhanced.extractUserAgent(request);

            // Rotate token with enhanced security (reuse detection, fingerprint validation)
            var result = refreshTokenService.rotate(refreshCookie, ipAddress, userAgent);

            // Fetch user to get actual role (not hardcoded)
            var userOpt = userService.getUserByEmail(result.email());
            if (userOpt.isEmpty()) {
                log.error("User not found during token refresh: email={}", result.email());
                return ResponseEntity.status(401).body(Map.of("error", "Invalid session"));
            }

            User user = userOpt.get();
            String role = user.getRole().name();
            var accessToken = jwtUtil.generateToken(result.email(), role, user.getId());

            ResponseCookie cookie = createRefreshTokenCookie(result.newToken());
            res.addHeader("Set-Cookie", cookie.toString());

            log.debug("Token refreshed successfully: email={}", result.email());
            return ResponseEntity.ok(Map.of("accessToken", accessToken));

        } catch (InvalidRefreshTokenException e) {
            // Standardized error response for token issues (401)
            log.warn("Token refresh failed: {}", e.getMessage());

            // Clear cookie on any token error
            ResponseCookie cleared = clearRefreshTokenCookie();
            res.addHeader("Set-Cookie", cleared.toString());

            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error during token refresh", e);

            // Clear cookie on error
            ResponseCookie cleared = clearRefreshTokenCookie();
            res.addHeader("Set-Cookie", cleared.toString());

            return ResponseEntity.status(401).body(Map.of("error", "Invalid or expired session"));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                    @CookieValue(value = REFRESH_COOKIE, required = false) String refreshCookie,
                                    HttpServletResponse res) {
        String email = null;

        // Extract email from access token
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                email = jwtUtil.getEmailFromToken(authHeader.substring(7));
            } catch (Exception e) {
                log.warn("Invalid access token during logout: {}", e.getMessage());
            }
        }

        // Revoke all refresh tokens for this user with audit trail
        if (email != null) {
            refreshTokenService.revokeAll(email, "USER_LOGOUT");
            log.info("User logged out successfully: email={}", email);
        }

        // Always clear the cookie regardless of validation status
        ResponseCookie cleared = clearRefreshTokenCookie();
        res.addHeader("Set-Cookie", cleared.toString());

        return ResponseEntity.ok(Map.of("status", "logged_out"));
    }
}
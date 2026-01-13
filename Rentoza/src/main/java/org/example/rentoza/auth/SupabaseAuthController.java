package org.example.rentoza.auth;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.example.rentoza.config.AppProperties;
import org.example.rentoza.security.CookieConstants;
import org.example.rentoza.security.JwtUserPrincipal;
import org.example.rentoza.security.supabase.SupabaseAuthClient.SupabaseAuthException;
import org.example.rentoza.security.supabase.SupabaseAuthService;
import org.example.rentoza.security.supabase.SupabaseAuthService.SupabaseAuthResult;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.dto.AuthResponseDTO;
import org.example.rentoza.user.dto.UserResponseDTO;
import org.example.rentoza.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

/**
 * Supabase Authentication Controller.
 * 
 * <p>Handles authentication operations using Supabase Auth:
 * <ul>
 *   <li>POST /api/auth/supabase/register - User registration</li>
 *   <li>POST /api/auth/supabase/login - User login</li>
 *   <li>POST /api/auth/supabase/refresh - Token refresh</li>
 *   <li>POST /api/auth/supabase/logout - User logout</li>
 * </ul>
 * 
 * <p>This controller runs alongside the legacy AuthController during migration.
 * Once migration is complete, the legacy controller will be deprecated.
 * 
 * @since Phase 2 - Supabase Auth Migration
 */
@RestController
@RequestMapping("/api/auth/supabase")
public class SupabaseAuthController {

    private static final Logger log = LoggerFactory.getLogger(SupabaseAuthController.class);

    private final SupabaseAuthService supabaseAuthService;
    private final UserService userService;
    private final AppProperties appProperties;

    // Token expiration (1 hour for access, 7 days for refresh)
    private static final long ACCESS_TOKEN_EXPIRY_MS = 3600000L;
    private static final long REFRESH_TOKEN_EXPIRY_DAYS = 7L;

    public SupabaseAuthController(
            SupabaseAuthService supabaseAuthService,
            UserService userService,
            AppProperties appProperties
    ) {
        this.supabaseAuthService = supabaseAuthService;
        this.userService = userService;
        this.appProperties = appProperties;
    }

    // =====================================================
    // 📝 REGISTRATION
    // =====================================================

    /**
     * Register a new user with Supabase Auth.
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(
            @Valid @RequestBody SupabaseRegisterRequest dto,
            HttpServletResponse response
    ) {
        try {
            Role role = dto.getRole() != null ? dto.getRole() : Role.USER;

            SupabaseAuthResult result = supabaseAuthService.register(
                    dto.getEmail(),
                    dto.getPassword(),
                    dto.getFirstName(),
                    dto.getLastName(),
                    role
            );

            // Set cookies
            setAuthCookies(response, result.getAccessToken(), result.getRefreshToken());

            // Build response
            UserResponseDTO userResponse = userService.toUserResponse(result.getUser());
            
            log.info("User registered via Supabase: email={}, role={}", dto.getEmail(), role);
            
            return ResponseEntity.ok(AuthResponseDTO.success(userResponse, "Account created successfully"));

        } catch (SupabaseAuthException e) {
            log.warn("Supabase registration failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "REGISTRATION_FAILED",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Unexpected error during Supabase registration", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "SERVER_ERROR",
                    "message", "Registration failed. Please try again."
            ));
        }
    }

    // =====================================================
    // 🔑 LOGIN
    // =====================================================

    /**
     * Login user with Supabase Auth.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody SupabaseLoginRequest dto,
            HttpServletResponse response
    ) {
        try {
            SupabaseAuthResult result = supabaseAuthService.login(dto.getEmail(), dto.getPassword());

            // Set cookies
            setAuthCookies(response, result.getAccessToken(), result.getRefreshToken());

            // Build response
            UserResponseDTO userResponse = userService.toUserResponse(result.getUser());
            
            log.info("User logged in via Supabase: email={}", dto.getEmail());
            
            return ResponseEntity.ok(AuthResponseDTO.success(userResponse));

        } catch (SupabaseAuthException e) {
            log.warn("Supabase login failed for {}: {}", dto.getEmail(), e.getMessage());
            return ResponseEntity.status(401).body(Map.of(
                    "error", "INVALID_CREDENTIALS",
                    "message", "Invalid email or password."
            ));
        } catch (Exception e) {
            log.error("Unexpected error during Supabase login", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "SERVER_ERROR",
                    "message", "Login failed. Please try again."
            ));
        }
    }

    // =====================================================
    // 🔄 TOKEN REFRESH
    // =====================================================

    /**
     * Refresh access token using Supabase refresh token.
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(
            @CookieValue(value = CookieConstants.REFRESH_TOKEN, required = false) String refreshCookie,
            HttpServletResponse response
    ) {
        if (refreshCookie == null || refreshCookie.isBlank()) {
            log.debug("Refresh attempt with no cookie");
            return ResponseEntity.status(401).body(Map.of("error", "No session"));
        }

        try {
            SupabaseAuthResult result = supabaseAuthService.refreshToken(refreshCookie);

            // Set new cookies
            setAuthCookies(response, result.getAccessToken(), result.getRefreshToken());

            // Build response
            UserResponseDTO userResponse = userService.toUserResponse(result.getUser());
            
            log.debug("Token refreshed via Supabase: email={}", result.getUser().getEmail());
            
            return ResponseEntity.ok(AuthResponseDTO.success(userResponse));

        } catch (SupabaseAuthException e) {
            log.warn("Supabase token refresh failed: {}", e.getMessage());
            clearAuthCookies(response);
            return ResponseEntity.status(401).body(Map.of(
                    "error", "SESSION_EXPIRED",
                    "message", "Session expired. Please login again."
            ));
        } catch (Exception e) {
            log.error("Unexpected error during Supabase token refresh", e);
            clearAuthCookies(response);
            return ResponseEntity.status(401).body(Map.of(
                    "error", "REFRESH_FAILED",
                    "message", "Session refresh failed."
            ));
        }
    }

    // =====================================================
    // ✉️ EMAIL CONFIRMATION
    // =====================================================

    /**
     * Handle email confirmation callback from Supabase.
     * 
     * <p>Flow:
     * <ol>
     *   <li>User clicks verification link in email</li>
     *   <li>Supabase verifies token and redirects to frontend with access_token in URL hash</li>
     *   <li>Frontend extracts token and calls this endpoint</li>
     *   <li>This endpoint validates token, enables user, and sets session cookies</li>
     * </ol>
     */
    @PostMapping("/confirm-email")
    public ResponseEntity<?> confirmEmail(
            @RequestBody EmailConfirmRequest dto,
            HttpServletResponse response
    ) {
        try {
            // Use the access token from Supabase to verify and get user info
            SupabaseAuthResult result = supabaseAuthService.confirmEmail(
                    dto.getAccessToken(),
                    dto.getRefreshToken()
            );

            // Set session cookies
            if (result.getAccessToken() != null) {
                setAuthCookies(response, result.getAccessToken(), result.getRefreshToken());
            }

            UserResponseDTO userResponse = userService.toUserResponse(result.getUser());
            
            log.info("Email confirmed via Supabase: email={}", result.getUser().getEmail());
            
            return ResponseEntity.ok(AuthResponseDTO.success(userResponse, "Email confirmed successfully"));

        } catch (SupabaseAuthException e) {
            log.warn("Email confirmation failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "CONFIRMATION_FAILED",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Unexpected error during email confirmation", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "CONFIRMATION_FAILED",
                    "message", "Email confirmation failed. Please try again."
            ));
        }
    }

    // =====================================================
    // 🚪 LOGOUT
    // =====================================================

    /**
     * Logout user and revoke Supabase tokens.
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @CookieValue(value = CookieConstants.ACCESS_TOKEN, required = false) String accessToken,
            HttpServletResponse response
    ) {
        // Revoke token in Supabase
        if (accessToken != null && !accessToken.isBlank()) {
            supabaseAuthService.logout(accessToken);
        }

        // Clear cookies
        clearAuthCookies(response);

        String email = principal != null ? principal.getUsername() : "anonymous";
        log.info("User logged out via Supabase: email={}", email);

        return ResponseEntity.ok(Map.of("status", "logged_out"));
    }

    // =====================================================
    // 🔧 HELPER METHODS
    // =====================================================

    /**
     * Set authentication cookies (access token + refresh token).
     */
    private void setAuthCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        // Access token cookie
        ResponseCookie accessCookie = ResponseCookie.from(CookieConstants.ACCESS_TOKEN, accessToken)
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .path("/")
                .sameSite(appProperties.getCookie().getSameSite())
                .maxAge(Duration.ofMillis(ACCESS_TOKEN_EXPIRY_MS))
                .domain(getCookieDomain())
                .build();

        // Refresh token cookie
        ResponseCookie refreshCookie = ResponseCookie.from(CookieConstants.REFRESH_TOKEN, refreshToken)
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .path("/api/auth")
                .sameSite(appProperties.getCookie().getSameSite())
                .maxAge(Duration.ofDays(REFRESH_TOKEN_EXPIRY_DAYS))
                .domain(getCookieDomain())
                .build();

        response.addHeader("Set-Cookie", accessCookie.toString());
        response.addHeader("Set-Cookie", refreshCookie.toString());
    }

    /**
     * Clear authentication cookies.
     */
    private void clearAuthCookies(HttpServletResponse response) {
        // Clear access token
        ResponseCookie clearedAccess = ResponseCookie.from(CookieConstants.ACCESS_TOKEN, "")
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .path("/")
                .sameSite(appProperties.getCookie().getSameSite())
                .maxAge(0)
                .domain(getCookieDomain())
                .build();

        // Clear refresh token
        ResponseCookie clearedRefresh = ResponseCookie.from(CookieConstants.REFRESH_TOKEN, "")
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .path("/api/auth")
                .sameSite(appProperties.getCookie().getSameSite())
                .maxAge(0)
                .domain(getCookieDomain())
                .build();

        response.addHeader("Set-Cookie", clearedAccess.toString());
        response.addHeader("Set-Cookie", clearedRefresh.toString());
    }

    private String getCookieDomain() {
        String domain = appProperties.getCookie().getDomain();
        return (domain != null && !domain.isBlank()) ? domain : null;
    }

    // =====================================================
    // 📦 REQUEST DTOs
    // =====================================================

    /**
     * Registration request DTO.
     */
    public static class SupabaseRegisterRequest {
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        private String email;

        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        private String password;

        @NotBlank(message = "First name is required")
        @Size(min = 2, max = 50, message = "First name must be 2-50 characters")
        private String firstName;

        @NotBlank(message = "Last name is required")
        @Size(min = 2, max = 50, message = "Last name must be 2-50 characters")
        private String lastName;

        private Role role;

        // Getters and setters
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public String getFirstName() { return firstName; }
        public void setFirstName(String firstName) { this.firstName = firstName; }

        public String getLastName() { return lastName; }
        public void setLastName(String lastName) { this.lastName = lastName; }

        public Role getRole() { return role; }
        public void setRole(Role role) { this.role = role; }
    }

    /**
     * Login request DTO.
     */
    public static class SupabaseLoginRequest {
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        private String email;

        @NotBlank(message = "Password is required")
        private String password;

        // Getters and setters
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    /**
     * Email confirmation request DTO.
     * Contains tokens from Supabase email verification redirect.
     */
    public static class EmailConfirmRequest {
        @NotBlank(message = "Access token is required")
        private String accessToken;
        
        private String refreshToken;

        // Getters and setters
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    }
}

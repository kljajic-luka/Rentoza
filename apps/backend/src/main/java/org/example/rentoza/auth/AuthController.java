package org.example.rentoza.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.example.rentoza.config.AppProperties;
import org.example.rentoza.deprecated.auth.InvalidRefreshTokenException;
import org.example.rentoza.deprecated.auth.RefreshTokenServiceEnhanced;
import org.example.rentoza.security.CookieConstants;

import org.example.rentoza.deprecated.jwt.JwtUtil;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.example.rentoza.user.UserService;
import org.example.rentoza.user.dto.AuthResponseDTO;
import org.example.rentoza.user.dto.UserLoginDTO;
import org.example.rentoza.user.dto.UserRegisterDTO;

import org.example.rentoza.user.dto.UserResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

/**
 * Authentication endpoints for user registration, login, token refresh, and logout.
 * Uses JWT for access tokens and secure HttpOnly cookies for refresh tokens.
 * 
 * SECURITY: All cookie names are centralized in CookieConstants to prevent typos
 * and ensure consistency across the codebase.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserService userService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RefreshTokenServiceEnhanced refreshTokenService;
    private final AppProperties appProperties;
    private final CsrfTokenRepository csrfTokenRepository;
    
    @Value("${jwt.expiration}")
    private long jwtExpirationMs;

    public AuthController(UserService userService,
                          UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtUtil jwtUtil,
                          RefreshTokenServiceEnhanced refreshTokenService,
                          AppProperties appProperties,
                          CsrfTokenRepository csrfTokenRepository) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.refreshTokenService = refreshTokenService;
        this.appProperties = appProperties;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    /**
     * Create a standardized refresh token cookie with environment-based security settings
     */
    private ResponseCookie createRefreshTokenCookie(String token) {
        log.debug("AUDIT: Issuing refresh token cookie");
        var builder = ResponseCookie.from(CookieConstants.REFRESH_TOKEN, token)
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .path("/")
                .sameSite(appProperties.getCookie().getSameSite())
                .maxAge(Duration.ofDays(14));
        
        String domain = appProperties.getCookie().getDomain();
        if (domain != null && !domain.isBlank()) {
            builder.domain(domain);
        }
        
        return builder.build();
    }

    /**
     * Create a standardized access token cookie
     */
    private ResponseCookie createAccessTokenCookie(String token) {
        log.debug("AUDIT: Issuing access token cookie");
        var builder = ResponseCookie.from(CookieConstants.ACCESS_TOKEN, token)
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .path("/")
                .sameSite(appProperties.getCookie().getSameSite())
                .maxAge(Duration.ofMillis(jwtExpirationMs));
        
        // Only set domain if explicitly configured (empty = use request host)
        String domain = appProperties.getCookie().getDomain();
        if (domain != null && !domain.isBlank()) {
            builder.domain(domain);
        }
        
        return builder.build();
    }

    /**
     * Create a cookie to clear the refresh token
     */
    private ResponseCookie clearRefreshTokenCookie() {
        log.debug("AUDIT: Clearing refresh token cookie");
        var builder = ResponseCookie.from(CookieConstants.REFRESH_TOKEN, "")
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .path("/")
                .sameSite(appProperties.getCookie().getSameSite())
                .maxAge(0);
        
        String domain = appProperties.getCookie().getDomain();
        if (domain != null && !domain.isBlank()) {
            builder.domain(domain);
        }
        
        return builder.build();
    }

    /**
     * Create a cookie to clear the access token
     */
    private ResponseCookie clearAccessTokenCookie() {
        log.debug("AUDIT: Clearing access token cookie");
        var builder = ResponseCookie.from(CookieConstants.ACCESS_TOKEN, "")
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .path("/")
                .sameSite(appProperties.getCookie().getSameSite())
                .maxAge(0);
        
        String domain = appProperties.getCookie().getDomain();
        if (domain != null && !domain.isBlank()) {
            builder.domain(domain);
        }
        
        return builder.build();
    }

    /**
     * Legacy registration endpoint — DISABLED.
     * Returns 410 GONE unconditionally. All registration must go through
     * /api/auth/supabase/register (USER) or the dedicated owner registration flow (OWNER).
     * Endpoint kept in SecurityConfig permitAll list; will be fully removed in Phase 4.
     */
    @Deprecated
    @PostMapping("/register")
    public ResponseEntity<?> register() {
        log.warn("SECURITY: Legacy /api/auth/register called but disabled in production");
        return ResponseEntity.status(410).body(Map.of(
                "error", "ENDPOINT_DEPRECATED",
                "message", "This registration endpoint is deprecated. Please use the current registration flow."
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody UserLoginDTO dto,
                                   HttpServletRequest request,
                                   HttpServletResponse res) {
        var userOpt = userService.getUserByEmail(dto.getEmail());
        
        // SECURITY: Check if user exists first
        if (userOpt.isEmpty()) {
            log.warn("Login attempt for non-existent user: email={}", dto.getEmail());
            // Generic error message to prevent username enumeration
            return ResponseEntity.status(401).body(Map.of(
                "error", "INVALID_CREDENTIALS",
                "message", "Pogrešna email adresa ili lozinka."
            ));
        }
        
        User user = userOpt.get();
        
        // SECURITY: Check if user is banned FIRST (admin action takes priority)
        // Banned users cannot login regardless of password or lockout status
        if (user.isBanned()) {
            log.warn("Banned user attempted login: email={}", dto.getEmail());
            String banReason = user.getBanReason() != null ? user.getBanReason() : "Contact support for details.";
            return ResponseEntity.status(403).body(Map.of(
                "error", "ACCOUNT_BANNED",
                "message", "Vaš nalog je suspendovan. Razlog: " + banReason
            ));
        }
        
        // SECURITY (VAL-038): Check if account is locked due to failed attempts
        if (user.isAccountLocked()) {
            log.warn("SECURITY: Login blocked - account locked. email={}, remaining={}",
                    dto.getEmail(), user.getRemainingLockoutTime());
            return ResponseEntity.status(403).body(Map.of(
                "error", "ACCOUNT_LOCKED",
                "message", "Nalog je privremeno zaključan. Pokušajte ponovo za " + user.getRemainingLockoutTime() + ".",
                "lockedUntil", user.getLockedUntil().toString(),
                "remainingTime", user.getRemainingLockoutTime()
            ));
        }
        
        // SECURITY: Validate password using BCrypt constant-time comparison
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            // VAL-038: Increment failed attempts and apply progressive lockout
            String clientIp = getClientIp(request);
            user.incrementFailedLoginAttempts(clientIp);
            userRepository.save(user);
            
            log.warn("SECURITY: Invalid password attempt. email={}, attempts={}, locked={}, ip={}",
                    dto.getEmail(), user.getFailedLoginAttempts(), user.isAccountLocked(), clientIp);
            
            // Provide lockout warning or locked message
            if (user.isAccountLocked()) {
                return ResponseEntity.status(403).body(Map.of(
                    "error", "ACCOUNT_LOCKED",
                    "message", "Previše neuspelih pokušaja. Nalog je zaključan na " + user.getRemainingLockoutTime() + ".",
                    "lockedUntil", user.getLockedUntil().toString(),
                    "remainingTime", user.getRemainingLockoutTime()
                ));
            }
            
            // Warning message with remaining attempts
            int remaining = user.getRemainingAttemptsBeforeLockout();
            String warningMsg = remaining > 0 
                ? String.format(" Preostalo pokušaja: %d.", remaining)
                : "";
            
            return ResponseEntity.status(401).body(Map.of(
                "error", "INVALID_CREDENTIALS",
                "message", "Pogrešna email adresa ili lozinka." + warningMsg,
                "remainingAttempts", remaining
            ));
        }
        
        // SECURITY FIX: Revoke all existing tokens before issuing new ones
        // This prevents old token chains from causing false theft detection
        // when browser sends stale cookies from previous sessions
        refreshTokenService.revokeAll(user.getEmail(), "NEW_LOGIN_SESSION");
        
        // VAL-038: Reset failed login attempts on successful authentication
        if (user.getFailedLoginAttempts() > 0) {
            user.resetFailedLoginAttempts();
            userRepository.save(user);
            log.info("SECURITY: Reset failed login attempts for user: email={}", dto.getEmail());
        }
        
        String accessToken = jwtUtil.generateToken(user.getEmail(), user.getRole().name(), user.getId());

        // Issue refresh token with IP/UserAgent fingerprinting
        String ipAddress = RefreshTokenServiceEnhanced.extractIpAddress(request);
        String userAgent = RefreshTokenServiceEnhanced.extractUserAgent(request);
        String refreshRaw = refreshTokenService.issue(user.getEmail(), ipAddress, userAgent);

        ResponseCookie cookie = createRefreshTokenCookie(refreshRaw);
        ResponseCookie accessCookie = createAccessTokenCookie(accessToken);
        
        res.addHeader("Set-Cookie", cookie.toString());
        res.addHeader("Set-Cookie", accessCookie.toString());
        ensureCsrfCookie(request, res);

        UserResponseDTO userResponse = userService.toUserResponse(user);

        log.info("User logged in successfully: email={}, role={}", user.getEmail(), user.getRole());

        // SECURITY: Token delivered via HttpOnly cookie, NOT in JSON body
        return ResponseEntity.ok(AuthResponseDTO.success(userResponse));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(
            @CookieValue(value = CookieConstants.REFRESH_TOKEN, required = false) String refreshCookie,
            HttpServletRequest request,
            HttpServletResponse res) {

        if (refreshCookie == null || refreshCookie.isBlank()) {
            log.warn("🔒 Refresh attempt with no cookie - guest user or cookie not sent");
            log.debug("Request cookies: {}", java.util.Arrays.toString(request.getCookies() != null ? 
                java.util.Arrays.stream(request.getCookies()).map(c -> c.getName()).toArray() : new String[]{"none"}));
            return ResponseEntity.status(401).body(Map.of("error", "No session"));
        }

        log.debug("🔄 Refresh cookie present (length: {})", refreshCookie.length());

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
            
            // SECURITY: Check if user was banned AFTER their last login
            // If banned, clear cookies and reject refresh
            if (user.isBanned()) {
                log.warn("Banned user attempted token refresh: email={}", user.getEmail());
                
                // Clear all cookies
                ResponseCookie clearedRefresh = clearRefreshTokenCookie();
                ResponseCookie clearedAccess = clearAccessTokenCookie();
                res.addHeader("Set-Cookie", clearedRefresh.toString());
                res.addHeader("Set-Cookie", clearedAccess.toString());
                
                // Revoke all their tokens
                refreshTokenService.revokeAll(user.getEmail(), "USER_BANNED");
                
                String banReason = user.getBanReason() != null ? user.getBanReason() : "Contact support for details.";
                return ResponseEntity.status(403).body(Map.of(
                    "error", "ACCOUNT_BANNED",
                    "message", "Your account has been suspended. Reason: " + banReason
                ));
            }
            
            String role = user.getRole().name();
            var accessToken = jwtUtil.generateToken(result.email(), role, user.getId());

            ResponseCookie cookie = createRefreshTokenCookie(result.newToken());
            ResponseCookie accessCookie = createAccessTokenCookie(accessToken);
            
            res.addHeader("Set-Cookie", cookie.toString());
            res.addHeader("Set-Cookie", accessCookie.toString());
            ensureCsrfCookie(request, res);

            UserResponseDTO userResponse = userService.toUserResponse(user);

            log.debug("Token refreshed successfully: email={}", result.email());
            
            // SECURITY: Token delivered via HttpOnly cookie, NOT in JSON body
            return ResponseEntity.ok(AuthResponseDTO.success(userResponse));

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
    public ResponseEntity<?> logout(@org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal,
                                    @CookieValue(value = CookieConstants.REFRESH_TOKEN, required = false) String refreshCookie,
                                    HttpServletResponse res) {
        String email = principal != null ? principal.getUsername() : null;

        // Revoke all refresh tokens for this user with audit trail
        if (email != null) {
            refreshTokenService.revokeAll(email, "USER_LOGOUT");
            log.info("User logged out successfully: email={}", email);
        }

        // Always clear the cookie regardless of validation status
        ResponseCookie cleared = clearRefreshTokenCookie();
        ResponseCookie clearedAccess = clearAccessTokenCookie();
        
        res.addHeader("Set-Cookie", cleared.toString());
        res.addHeader("Set-Cookie", clearedAccess.toString());

        return ResponseEntity.ok(Map.of("status", "logged_out"));
    }

    private void ensureCsrfCookie(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken token = csrfTokenRepository.loadToken(request);
        if (token == null) {
            token = csrfTokenRepository.generateToken(request);
        }
        csrfTokenRepository.saveToken(token, request, response);
    }
    
    /**
     * Extract client IP address from request, handling reverse proxy headers.
     * Checks X-Forwarded-For first (from load balancers/proxies), then falls back to remote address.
     * 
     * @param request HTTP request
     * @return Client IP address (IPv4 or IPv6)
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // X-Forwarded-For can contain multiple IPs: "client, proxy1, proxy2"
            // First IP is the original client
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr();
    }
}
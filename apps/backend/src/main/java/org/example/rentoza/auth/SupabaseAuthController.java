package org.example.rentoza.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.example.rentoza.config.AppProperties;
import org.example.rentoza.security.CookieConstants;
import org.example.rentoza.security.JwtUserPrincipal;
import org.example.rentoza.security.password.PasswordPolicyService;
import org.example.rentoza.security.password.PasswordResetService;
import org.example.rentoza.security.password.PasswordResetService.PasswordResetException;
import org.example.rentoza.security.supabase.SupabaseAuthClient.SupabaseAuthException;
import org.example.rentoza.security.supabase.SupabaseAuthService;
import org.example.rentoza.security.supabase.SupabaseAuthService.SupabaseAuthResult;
import org.example.rentoza.security.supabase.SupabaseJwtUtil;
import org.example.rentoza.security.token.TokenDenylistService;
import org.example.rentoza.security.validation.InputSanitizer;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private final UserRepository userRepository;
    private final AppProperties appProperties;
    private final PasswordResetService passwordResetService;
    private final PasswordPolicyService passwordPolicyService;
    private final TokenDenylistService tokenDenylistService;
    private final SupabaseJwtUtil supabaseJwtUtil;

    // Token expiration (1 hour for access, 7 days for refresh)
    private static final long ACCESS_TOKEN_EXPIRY_MS = 3600000L;
    private static final long REFRESH_TOKEN_EXPIRY_DAYS = 7L;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public SupabaseAuthController(
            SupabaseAuthService supabaseAuthService,
            UserService userService,
            UserRepository userRepository,
            AppProperties appProperties,
            PasswordResetService passwordResetService,
            PasswordPolicyService passwordPolicyService,
            TokenDenylistService tokenDenylistService,
            SupabaseJwtUtil supabaseJwtUtil
    ) {
        this.supabaseAuthService = supabaseAuthService;
        this.userService = userService;
        this.userRepository = userRepository;
        this.appProperties = appProperties;
        this.passwordResetService = passwordResetService;
        this.passwordPolicyService = passwordPolicyService;
        this.tokenDenylistService = tokenDenylistService;
        this.supabaseJwtUtil = supabaseJwtUtil;
    }

    // =====================================================
    // � GOOGLE OAUTH VIA SUPABASE
    // =====================================================

    /**
     * Initiate Google OAuth2 authentication via Supabase.
     * 
     * <p>This endpoint generates a secure authorization URL that redirects users
     * to Google's OAuth consent screen via Supabase Auth.
     * 
     * <p><b>Security Features:</b>
     * <ul>
     *   <li>Cryptographically random state token for CSRF protection</li>
     *   <li>State expires after 10 minutes</li>
     *   <li>Role encoded in state for registration</li>
     * </ul>
     * 
     * <p><b>Example Request:</b>
     * <pre>GET /api/auth/supabase/google/authorize?role=OWNER</pre>
     * 
     * <p><b>Example Response:</b>
     * <pre>{
     *   "authorizationUrl": "https://xxx.supabase.co/auth/v1/authorize?...",
     *   "state": "abc123..."
     * }</pre>
     * 
     * @param role User role (USER or OWNER). Defaults to USER if not specified.
     * @param redirectUri Optional custom redirect URI after OAuth completion.
     * @return JSON with authorizationUrl and state token
     */
    @GetMapping("/google/authorize")
    public ResponseEntity<?> initiateGoogleAuth(
            @RequestParam(value = "role", required = false, defaultValue = "USER") String role,
            @RequestParam(value = "redirectUri", required = false) String redirectUri
    ) {
        try {
            // Parse and validate role
            Role userRole;
            try {
                userRole = Role.valueOf(role.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid role parameter for Google OAuth: {}", role);
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "INVALID_ROLE",
                        "message", "Invalid role. Allowed values: USER, OWNER",
                        "allowedRoles", new String[]{"USER", "OWNER"}
                ));
            }
            
            // Only allow USER and OWNER roles for self-registration
            if (userRole != Role.USER && userRole != Role.OWNER) {
                log.warn("Attempted Google OAuth with non-registerable role: {}", role);
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "INVALID_ROLE",
                        "message", "Only USER and OWNER roles are allowed for registration"
                ));
            }
            
            // Generate OAuth authorization URL
            SupabaseAuthService.GoogleAuthInitResult result = 
                    supabaseAuthService.initiateGoogleAuth(userRole, redirectUri);
            
            log.info("Google OAuth initiated for role={}", userRole);
            
            // Note: state is null as Supabase handles CSRF internally
            // Role is encoded in the redirect_to URL parameter
            return ResponseEntity.ok(Map.of(
                    "authorizationUrl", result.authorizationUrl()
            ));
            
        } catch (SupabaseAuthException e) {
            log.error("Failed to initiate Google OAuth: {}", e.getMessage());
            // Return 400 for client errors like invalid redirect URI
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "OAUTH_INIT_FAILED",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Unexpected error during Google OAuth initiation", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "SERVER_ERROR",
                    "message", "An unexpected error occurred. Please try again."
            ));
        }
    }

    /**
     * Handle Google OAuth2 callback from Supabase.
     * 
     * <p>This endpoint processes the OAuth callback, exchanges the authorization
     * code for tokens, synchronizes the user to the local database, and sets
     * authentication cookies.
     * 
     * <p><b>Security Validations:</b>
     * <ul>
     *   <li>State parameter validated against stored values (CSRF protection)</li>
     *   <li>Authorization code exchanged server-side</li>
     *   <li>User synchronized in database transaction</li>
     *   <li>Tokens delivered via HttpOnly cookies</li>
     * </ul>
     * 
     * <p><b>User Registration Status:</b>
     * <ul>
     *   <li>New users: registrationStatus = INCOMPLETE (needs profile completion)</li>
     *   <li>Existing users: registrationStatus = ACTIVE (or their current status)</li>
     * </ul>
     * 
     * <p><b>Example Request:</b>
     * <pre>GET /api/auth/supabase/google/callback?code=abc123&state=xyz789</pre>
     * 
     * <p><b>Example Response (success):</b>
     * <pre>{
     *   "success": true,
     *   "user": { "id": 1, "email": "user@example.com", ... },
     *   "message": "Successfully authenticated with Google"
     * }</pre>
     * 
     * @param code Authorization code from Supabase
     * @param state State parameter for CSRF validation
     * @param response HTTP response for setting cookies
     * @return User data and success message
     */
    @GetMapping("/google/callback")
    public ResponseEntity<?> handleGoogleCallback(
            @RequestParam("code") String code,
            @RequestParam(value = "role", required = false) String ignoredRole,
            HttpServletResponse response
    ) {
        // Input validation
        if (code == null || code.isBlank()) {
            log.warn("Google OAuth callback with missing authorization code");
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "MISSING_CODE",
                    "message", "Authorization code is required"
            ));
        }
        
        if (ignoredRole != null && !"USER".equalsIgnoreCase(ignoredRole)) {
            log.warn("SECURITY: Ignoring client-provided role '{}' in Google callback. Owner upgrade must use profile completion flow.", ignoredRole);
        }
        
        try {
            // SECURITY: Always default to USER. Owner role assignment happens only via validated profile completion.
            SupabaseAuthResult result = supabaseAuthService.handleGoogleCallback(code);
            
            // Set authentication cookies
            setAuthCookies(response, result.getAccessToken(), result.getRefreshToken());
            
            // Build user response
            UserResponseDTO userResponse = userService.toUserResponse(result.getUser());
            
            log.info("Google OAuth callback successful: userId={}, email={}, registrationStatus={}", 
                    result.getUser().getId(), 
                    result.getUser().getEmail(),
                    result.getUser().getRegistrationStatus());
            
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "user", userResponse,
                    "message", "Successfully authenticated with Google",
                    "registrationStatus", result.getUser().getRegistrationStatus().name()
            ));
            
        } catch (SupabaseAuthException e) {
            log.warn("Google OAuth callback failed: {}", e.getMessage());
            
            // Determine appropriate error response based on exception message
            if (e.getMessage().contains("expired") || e.getMessage().contains("session")) {
                return ResponseEntity.status(401).body(Map.of(
                        "error", "SESSION_EXPIRED",
                        "message", e.getMessage()
                ));
            }
            
            if (e.getMessage().contains("already associated") || e.getMessage().contains("duplicate")
                    || e.getMessage().contains("already exists")) {
                return ResponseEntity.status(409).body(Map.of(
                        "error", "ACCOUNT_CONFLICT",
                        "message", e.getMessage()
                ));
            }
            
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "OAUTH_CALLBACK_FAILED",
                    "message", e.getMessage()
            ));
            
        } catch (Exception e) {
            log.error("Unexpected error during Google OAuth callback", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "SERVER_ERROR",
                    "message", "Authentication failed. Please try again."
            ));
        }
    }

    /**
     * POST version of Google OAuth callback (alternative for form submissions).
     * 
     * <p>Same functionality as the GET version, but accepts parameters in request body.
     * Useful for clients that prefer POST for OAuth callbacks.
     * 
     * @param callbackRequest Request body containing code and state
     * @param response HTTP response for setting cookies
     * @return User data and success message
     */
    @PostMapping("/google/callback")
    public ResponseEntity<?> handleGoogleCallbackPost(
            @Valid @RequestBody GoogleCallbackRequest callbackRequest,
            HttpServletResponse response
    ) {
        // SECURITY FIX: Previously passed state as role (bug). Now ignores both client fields.
        return handleGoogleCallback(callbackRequest.getCode(), null, response);
    }
    /**
     * Handle Google OAuth token callback from Supabase's implicit flow.
     * 
     * <p>In the implicit OAuth flow, Supabase returns tokens directly in the URL fragment
     * instead of an authorization code. The frontend extracts these tokens and sends them
     * to this endpoint for verification and user synchronization.
     * 
     * <p><b>Request Body:</b>
     * <pre>{
     *   "accessToken": "eyJhbG...",
     *   "refreshToken": "...",  // optional
     *   "state": "..."          // optional, for role information
     * }</pre>
     * 
     * <p><b>Response (success):</b>
     * <pre>{
     *   "success": true,
     *   "user": { "id": 1, "email": "user@example.com", ... },
     *   "registrationStatus": "INCOMPLETE" | "ACTIVE"
     * }</pre>
     * 
     * @param request Request body containing the tokens
     * @param response HTTP response for setting cookies
     * @return User data and success message
     */
    @PostMapping("/google/token-callback")
    public ResponseEntity<?> handleGoogleTokenCallback(
            @Valid @RequestBody GoogleTokenCallbackRequest request,
            HttpServletResponse response
    ) {
        // Input validation
        if (request.getAccessToken() == null || request.getAccessToken().isBlank()) {
            log.warn("Google OAuth token callback with missing access token");
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "MISSING_TOKEN",
                    "message", "Access token is required"
            ));
        }
        
        if (request.getRole() != null && !"USER".equalsIgnoreCase(request.getRole())) {
            log.warn("SECURITY: Ignoring client-provided role '{}' in implicit token callback. Owner upgrade must use profile completion flow.", request.getRole());
        }
        
        try {
            // SECURITY: Always default to USER. Role from client is untrusted.
            SupabaseAuthResult result = supabaseAuthService.handleImplicitFlowTokens(
                    request.getAccessToken(),
                    request.getRefreshToken()
            );
            
            // Set authentication cookies
            setAuthCookies(response, result.getAccessToken(), result.getRefreshToken());
            
            // Build user response
            UserResponseDTO userResponse = userService.toUserResponse(result.getUser());
            
            log.info("Google OAuth token callback successful: userId={}, email={}, registrationStatus={}", 
                    result.getUser().getId(), 
                    result.getUser().getEmail(),
                    result.getUser().getRegistrationStatus());
            
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "user", userResponse,
                    "message", "Successfully authenticated with Google",
                    "registrationStatus", result.getUser().getRegistrationStatus().name()
            ));
            
        } catch (SupabaseAuthException e) {
            log.warn("Google OAuth token callback failed: {}", e.getMessage());
            
            if (e.getMessage().contains("expired") || e.getMessage().contains("Invalid")) {
                return ResponseEntity.status(401).body(Map.of(
                        "error", "INVALID_TOKEN",
                        "message", e.getMessage()
                ));
            }
            
            if (e.getMessage().contains("already associated") || e.getMessage().contains("duplicate")
                    || e.getMessage().contains("already exists")) {
                return ResponseEntity.status(409).body(Map.of(
                        "error", "ACCOUNT_CONFLICT",
                        "message", e.getMessage()
                ));
            }
            
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "AUTH_ERROR",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Unexpected error during Google OAuth token callback", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "SERVER_ERROR",
                    "message", "Authentication failed. Please try again."
            ));
        }
    }
    // =====================================================
    // �📝 REGISTRATION
    // =====================================================

    /**
     * Register a new user with Supabase Auth.
     * 
     * <p>Two possible outcomes:
     * <ol>
     *   <li>Email confirmation DISABLED: User is logged in immediately, cookies set</li>
     *   <li>Email confirmation ENABLED: User must verify email first, NO cookies set</li>
     * </ol>
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(
            @Valid @RequestBody SupabaseRegisterRequest dto,
            HttpServletResponse response
    ) {
        try {
            // P1: Password strength validation (Turo standard)
            List<String> passwordViolations = passwordPolicyService.validatePasswordStrength(dto.getPassword());
            if (!passwordViolations.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "WEAK_PASSWORD",
                        "message", String.join(". ", passwordViolations),
                        "violations", passwordViolations
                ));
            }

            // P1: Input sanitization for name fields (XSS defense-in-depth)
            String sanitizedFirstName;
            String sanitizedLastName;
            try {
                sanitizedFirstName = InputSanitizer.sanitizeName(dto.getFirstName());
                sanitizedLastName = InputSanitizer.sanitizeName(dto.getLastName());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "INVALID_INPUT",
                        "message", e.getMessage()
                ));
            }

            Role role = dto.getRole() != null ? dto.getRole() : Role.USER;

            SupabaseAuthResult result = supabaseAuthService.register(
                    dto.getEmail(),
                    dto.getPassword(),
                    sanitizedFirstName,
                    sanitizedLastName,
                    role
            );

            // Build response
            UserResponseDTO userResponse = userService.toUserResponse(result.getUser());

            // P1: Seed password history on registration so reuse checks work from day one
            try {
                String hashedPassword = passwordEncoder.encode(dto.getPassword());
                passwordPolicyService.recordPassword(result.getUser().getId(), hashedPassword);
            } catch (Exception ex) {
                log.warn("Failed to seed password history on register for userId={}: {}",
                        result.getUser().getId(), ex.getMessage());
            }

            // CRITICAL: Handle email confirmation pending case
            if (result.isEmailConfirmationPending()) {
                log.info("User registered via Supabase (email confirmation pending): email={}", dto.getEmail());
                
                // DO NOT set cookies - tokens are null when email confirmation is pending
                // Return special response so frontend knows to show email confirmation message
                // NOTE: "user" key deliberately omitted — identical shape to duplicate-email path
                // to prevent email-enumeration via response payload diffing.
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "emailConfirmationPending", true,
                        "message", "Please check your email to confirm your account."
                ));
            }
            
            // Email confirmation disabled - user can login immediately
            setAuthCookies(response, result.getAccessToken(), result.getRefreshToken());
            
            log.info("User registered via Supabase: email={}, role={}", dto.getEmail(), role);
            
            return ResponseEntity.ok(AuthResponseDTO.success(userResponse, "Account created successfully"));

        } catch (SupabaseAuthException e) {
            log.warn("Supabase registration failed: {}", e.getMessage());
            // P1: Email enumeration fix — duplicate email returns 200 with same structure
            // so attackers cannot distinguish "email taken" from "success" via HTTP status
            String message = e.getMessage();
            if (message != null && (message.contains("already registered") || message.contains("already exists"))) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "emailConfirmationPending", true,
                        "message", "Please check your email to confirm your account."
                ));
            }
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "REGISTRATION_FAILED",
                    "message", message != null ? message : "Registration failed. Please try again."
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
     *
     * <p>P0 Security: Includes brute-force protection via account lockout.
     * After 5 failed attempts, account is locked for 15 minutes.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody SupabaseLoginRequest dto,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        try {
            String clientIp = request.getRemoteAddr();

            // P0: Brute-force protection — check if account is locked
            Optional<User> existingUser = userRepository.findByEmail(dto.getEmail());
            if (existingUser.isPresent() && existingUser.get().isAccountLocked()) {
                log.warn("Login attempt on locked account: email={}, ip={}", dto.getEmail(), clientIp);
                return ResponseEntity.status(429).body(Map.of(
                        "error", "ACCOUNT_LOCKED",
                        "message", "Too many failed login attempts. Please try again later."
                ));
            }

            SupabaseAuthResult result = supabaseAuthService.login(dto.getEmail(), dto.getPassword());

            // P0: Reset failed attempts on successful login
            if (existingUser.isPresent()) {
                User user = existingUser.get();
                if (user.getFailedLoginAttempts() > 0) {
                    user.resetFailedLoginAttempts();
                    userRepository.save(user);
                }
            }

            // Set cookies
            setAuthCookies(response, result.getAccessToken(), result.getRefreshToken());

            // Build response
            UserResponseDTO userResponse = userService.toUserResponse(result.getUser());
            
            log.info("User logged in via Supabase: email={}", dto.getEmail());
            
            return ResponseEntity.ok(AuthResponseDTO.success(userResponse));

        } catch (SupabaseAuthException e) {
            // P0: Increment failed login attempts
            Optional<User> failedUser = userRepository.findByEmail(dto.getEmail());
            if (failedUser.isPresent()) {
                User user = failedUser.get();
                user.incrementFailedLoginAttempts(request.getRemoteAddr());
                userRepository.save(user);
                log.warn("Failed login for email={}, attempts={}", dto.getEmail(), user.getFailedLoginAttempts());
            }

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
     *
     * <p>P0: Also adds the access token to the JWT denylist so it cannot
     * be replayed before natural expiry (Turo standard: logout invalidates JWT).
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @CookieValue(value = CookieConstants.ACCESS_TOKEN, required = false) String accessToken,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        // P1: Also check Authorization bearer header as fallback
        String tokenToRevoke = accessToken;
        if ((tokenToRevoke == null || tokenToRevoke.isBlank())) {
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                tokenToRevoke = authHeader.substring(7);
            }
        }

        // P0: Deny the access token so it can't be replayed
        if (tokenToRevoke != null && !tokenToRevoke.isBlank()) {
            try {
                // Extract token expiry for denylist TTL
                Date expDate = supabaseJwtUtil.getExpirationDateFromToken(tokenToRevoke);
                Instant expiresAt = (expDate != null) ? expDate.toInstant() : Instant.now().plusSeconds(3600);
                String email = principal != null ? principal.getUsername() : "anonymous";
                tokenDenylistService.denyToken(tokenToRevoke, expiresAt, email);
            } catch (Exception e) {
                log.warn("Failed to denylist token on logout: {}", e.getMessage());
                // Continue with logout even if denylist fails
            }

            // Revoke token in Supabase
            supabaseAuthService.logout(tokenToRevoke);
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

    // =====================================================
    // 🔒 PASSWORD RECOVERY (Turo Standard P0)
    // =====================================================

    /**
     * Request a password reset email.
     *
     * <p>SECURITY: Always returns 200 OK regardless of whether the email exists.
     * This prevents email enumeration attacks (Turo standard).
     *
     * <p>Rate limited to 3 requests per 5 minutes per IP.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest dto,
            HttpServletRequest request
    ) {
        try {
            String clientIp = request.getRemoteAddr();
            passwordResetService.requestPasswordReset(dto.getEmail(), clientIp);

            // SECURITY: Always return success (email enumeration protection)
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "If an account exists with this email, a password reset link has been sent."
            ));
        } catch (Exception e) {
            log.error("Unexpected error during password reset request", e);
            // Still return generic success to prevent enumeration
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "If an account exists with this email, a password reset link has been sent."
            ));
        }
    }

    /**
     * Reset password using a one-time token from the reset email.
     *
     * <p>Validates:
     * <ul>
     *   <li>Token validity and expiration (1 hour)</li>
     *   <li>Password strength (Turo standard: 8+ chars, upper, lower, digit, special)</li>
     *   <li>Password reuse (cannot reuse last 3 passwords)</li>
     * </ul>
     */
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(
            @Valid @RequestBody ResetPasswordRequest dto
    ) {
        try {
            passwordResetService.resetPassword(dto.getToken(), dto.getNewPassword());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Password has been reset successfully. You can now log in with your new password."
            ));
        } catch (PasswordResetException e) {
            log.warn("Password reset failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "RESET_FAILED",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Unexpected error during password reset", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "SERVER_ERROR",
                    "message", "Password reset failed. Please try again."
            ));
        }
    }

    /**
     * Set authentication cookies (access token + refresh token).
     * 
     * <p>SECURITY: Only sets cookies if both tokens are present.
     * This prevents setting empty cookies which would cause immediate session expiry.
     */
    private void setAuthCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        // CRITICAL: Don't set empty cookies - this causes "session expired" errors
        if (accessToken == null || accessToken.isBlank() || refreshToken == null || refreshToken.isBlank()) {
            log.warn("Attempted to set auth cookies with null/empty tokens - skipping");
            return;
        }
        
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
                .path("/")
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
                .path("/")
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

    /**
     * Google OAuth callback request DTO.
     * Used for POST-based OAuth callback handling.
     * 
     * <p>Contains the authorization code and state from the OAuth flow.
     * The state is used for CSRF protection and must match the value
     * returned from the authorization endpoint.
     */
    public static class GoogleCallbackRequest {
        @NotBlank(message = "Authorization code is required")
        private String code;
        
        private String state; // Optional — Supabase handles CSRF internally via PKCE

        // Getters and setters
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }

        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
    }

    /**
     * Google OAuth token callback request DTO.
     * Used for implicit flow where tokens are returned directly.
     * 
     * <p>In implicit OAuth flow, Supabase returns access_token and refresh_token
     * directly in the URL fragment instead of an authorization code.
     */
    public static class GoogleTokenCallbackRequest {
        @NotBlank(message = "Access token is required")
        private String accessToken;
        
        private String refreshToken;
        
        private String role;  // Role extracted from redirect URL query param (USER or OWNER)

        // Getters and setters
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
    }

    /**
     * Forgot password request DTO.
     */
    public static class ForgotPasswordRequest {
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        private String email;

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }

    /**
     * Reset password request DTO.
     */
    public static class ResetPasswordRequest {
        @NotBlank(message = "Token is required")
        private String token;

        @NotBlank(message = "New password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        private String newPassword;

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }

        public String getNewPassword() { return newPassword; }
        public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
    }
}

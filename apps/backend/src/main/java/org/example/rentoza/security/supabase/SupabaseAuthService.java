package org.example.rentoza.security.supabase;

import org.example.rentoza.security.supabase.SupabaseAuthClient.SupabaseAuthException;
import org.example.rentoza.security.supabase.SupabaseAuthClient.SupabaseAuthResponse;
import org.example.rentoza.security.supabase.SupabaseAuthClient.SupabaseUser;
import org.example.rentoza.user.AuthProvider;
import org.example.rentoza.user.RegistrationStatus;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for Supabase authentication operations.
 * 
 * <p>Handles:
 * <ul>
 *   <li>User registration (Supabase + Rentoza + mapping)</li>
 *   <li>User login (validates Supabase, returns Rentoza user)</li>
 *   <li>Google OAuth2 authentication via Supabase</li>
 *   <li>Token refresh</li>
 *   <li>User logout</li>
 *   <li>OAuth2 user linking</li>
 * </ul>
 * 
 * <p>This service coordinates between:
 * <ul>
 *   <li>Supabase Auth (authentication)</li>
 *   <li>Rentoza users table (application user data)</li>
 *   <li>Mapping table (UUID ↔ BIGINT bridge)</li>
 * </ul>
 * 
 * <p><b>Security Architecture:</b>
 * <ul>
 *   <li>All tokens are Supabase-managed (ES256 JWTs)</li>
 *   <li>State parameter uses cryptographic hashing (SHA-256) for CSRF protection</li>
 *   <li>User synchronization is idempotent and transactional</li>
 *   <li>All operations are logged for security auditing</li>
 * </ul>
 * 
 * @since Phase 2 - Supabase Auth Migration
 */
@Service
public class SupabaseAuthService {

    private static final Logger log = LoggerFactory.getLogger(SupabaseAuthService.class);
    
    /** State token expiration time in milliseconds (10 minutes) */
    private static final long STATE_EXPIRATION_MS = 10 * 60 * 1000;
    
    /** Thread-safe store for OAuth state tokens with expiration */
    private final ConcurrentHashMap<String, OAuthStateData> pendingOAuthStates = new ConcurrentHashMap<>();
    
    /** Secure random generator for state token creation */
    private final SecureRandom secureRandom = new SecureRandom();

    private final SupabaseAuthClient supabaseClient;
    private final SupabaseUserMappingRepository mappingRepository;
    private final UserRepository userRepository;
    private final SupabaseJwtUtil supabaseJwtUtil;
    
    @Value("${supabase.url}")
    private String supabaseUrl;
    
    @Value("${app.oauth2-redirect-uri:#{null}}")
    private String oauth2RedirectUri;
    
    @Value("${app.oauth2-redirect-allowed-uris:#{null}}")
    private String oauth2RedirectAllowedUrisRaw;

    public SupabaseAuthService(
            SupabaseAuthClient supabaseClient,
            SupabaseUserMappingRepository mappingRepository,
            UserRepository userRepository,
            SupabaseJwtUtil supabaseJwtUtil
    ) {
        this.supabaseClient = supabaseClient;
        this.mappingRepository = mappingRepository;
        this.userRepository = userRepository;
        this.supabaseJwtUtil = supabaseJwtUtil;
    }

    // =====================================================
    // � GOOGLE OAUTH2 VIA SUPABASE
    // =====================================================

    /**
     * Initiates Google OAuth2 authentication flow via Supabase.
     * 
     * <p>This method generates a secure authorization URL that redirects users
     * to Google's OAuth consent screen via Supabase Auth.
     * 
     * <p><b>Security Features:</b>
     * <ul>
     *   <li>Cryptographically random state token (32 bytes, Base64 URL-encoded)</li>
     *   <li>State token hashed with SHA-256 for storage (prevents timing attacks)</li>
     *   <li>State includes role and timestamp for CSRF protection</li>
     *   <li>State expires after 10 minutes</li>
     * </ul>
     * 
     * <p><b>Flow:</b>
     * <ol>
     *   <li>Generate cryptographically secure state token</li>
     *   <li>Store state with role and expiration</li>
     *   <li>Build Supabase OAuth authorization URL</li>
     *   <li>Return URL and state to frontend</li>
     * </ol>
     * 
     * @param role User role to assign after successful authentication (USER or OWNER)
     * @param redirectUri Optional custom redirect URI (uses configured default if null)
     * @return GoogleAuthInitResult containing authorization URL and state token
     * @throws SupabaseAuthException if configuration is invalid
     */
    public GoogleAuthInitResult initiateGoogleAuth(Role role, String redirectUri) {
        // Validate role
        if (role == null) {
            role = Role.USER;
            log.debug("No role specified for Google OAuth, defaulting to USER");
        }
        
        if (role != Role.USER && role != Role.OWNER) {
            log.warn("Invalid role {} for Google OAuth registration, defaulting to USER", role);
            role = Role.USER;
        }
        
        // Determine redirect URI
        String effectiveRedirectUri = resolveRedirectUri(redirectUri);
        
        // Build Supabase OAuth authorization URL
        // Note: We no longer store state locally - Supabase handles CSRF protection internally.
        // The role is encoded in the redirect_to URL and will be retrieved from query params after callback.
        String authUrl = buildSupabaseGoogleAuthUrl(role, effectiveRedirectUri);
        
        log.info("Initiated Google OAuth for role={}, redirectUri={}", role, effectiveRedirectUri);
        
        // We return null for stateToken since we're not using custom state anymore
        // The frontend should not need the state token - Supabase handles it
        return new GoogleAuthInitResult(authUrl, null);
    }

    /**
     * Handles the Google OAuth2 callback from Supabase (PKCE/code flow).
     * 
     * <p><b>Note:</b> This method is for the PKCE flow where an authorization code is 
     * returned. For Supabase's default implicit flow (tokens in URL fragment), use 
     * {@link #handleImplicitFlowTokens} instead.
     * 
     * <p>This method exchanges the authorization code for tokens and synchronizes 
     * the user to the local database.
     * 
     * <p><b>User Synchronization (Idempotent):</b>
     * <ul>
     *   <li>If user exists by email: update auth_uid and return existing user</li>
     *   <li>If user exists by auth_uid: return existing user</li>
     *   <li>If new user: create with INCOMPLETE registration status</li>
     * </ul>
     * 
     * @param code Authorization code from Supabase callback
     * @param roleString Role as string (USER or OWNER) from redirect URL query param
     * @return SupabaseAuthResult containing tokens and user data
     * @throws SupabaseAuthException if validation fails or user sync fails
     */
    @Transactional
    public SupabaseAuthResult handleGoogleCallback(String code) {
        // Validate inputs
        if (code == null || code.isBlank()) {
            log.warn("Google OAuth callback with missing authorization code");
            throw new SupabaseAuthException("Authorization code is required");
        }
        
        // SECURITY: Role is always USER. Owner upgrade happens only through profile completion.
        Role role = Role.USER;
        
        log.info("Processing Google OAuth callback (role locked to USER)");
        
        // Exchange authorization code for tokens via Supabase
        SupabaseAuthResponse authResponse;
        try {
            authResponse = supabaseClient.exchangeCodeForToken(code);
        } catch (SupabaseAuthException e) {
            log.error("Failed to exchange authorization code: {}", e.getMessage());
            throw new SupabaseAuthException("Authentication failed: " + e.getMessage(), e);
        }
        
        if (authResponse.getUser() == null) {
            log.error("Supabase returned null user after code exchange");
            throw new SupabaseAuthException("Authentication failed: no user data returned");
        }
        
        UUID supabaseId = authResponse.getUser().getId();
        String email = authResponse.getUser().getEmail();
        
        if (email == null || email.isBlank()) {
            log.error("Supabase user has no email address: {}", supabaseId);
            throw new SupabaseAuthException("Email address is required for registration");
        }
        
        log.info("Google OAuth successful for email={}, supabaseId={}", email, supabaseId);
        
        // Synchronize user to local database (idempotent)
        User user = syncGoogleUserToLocalDatabase(authResponse.getUser(), role);
        
        // Create/update mapping
        ensureUserMapping(supabaseId, user.getId());
        
        log.info("Google OAuth complete: userId={}, role={}, registrationStatus={}", 
                user.getId(), user.getRole(), user.getRegistrationStatus());
        
        return SupabaseAuthResult.builder()
                .accessToken(authResponse.getAccessToken())
                .refreshToken(authResponse.getRefreshToken())
                .expiresIn(authResponse.getExpiresIn())
                .user(user)
                .supabaseUserId(supabaseId)
                .build();
    }

    /**
     * Handle implicit OAuth flow tokens from Supabase.
     * 
     * <p>In the implicit flow, Supabase returns tokens directly in the URL fragment
     * instead of an authorization code. This method verifies the access token with
     * Supabase and synchronizes the user to our local database.
     * 
     * <p><b>Flow:</b>
     * <ol>
     *   <li>Verify access token with Supabase /auth/v1/user endpoint</li>
     *   <li>Extract user info from Supabase response</li>
     *   <li>Sync user to local database (create or find existing)</li>
     *   <li>Return auth result with tokens and user data</li>
     * </ol>
     * 
     * @param accessToken Supabase access token from URL fragment
     * @param refreshToken Supabase refresh token (optional)
     * @param roleString Role as string from URL query param (e.g., "USER" or "OWNER")
     * @return SupabaseAuthResult containing tokens and user data
     * @throws SupabaseAuthException if token is invalid or user sync fails
     */
    @Transactional
    public SupabaseAuthResult handleImplicitFlowTokens(String accessToken, String refreshToken) {
        // Validate access token
        if (accessToken == null || accessToken.isBlank()) {
            log.warn("Implicit flow callback with missing access token");
            throw new SupabaseAuthException("Access token is required");
        }
        
        // SECURITY: Role is always USER. Owner upgrade happens only through profile completion.
        Role role = Role.USER;
        
        log.info("Processing implicit OAuth flow (role locked to USER)");
        
        // Verify token with Supabase and get user info
        SupabaseUser supabaseUser;
        try {
            supabaseUser = supabaseClient.getUser(accessToken);
        } catch (SupabaseAuthException e) {
            log.error("Failed to verify access token with Supabase: {}", e.getMessage());
            throw new SupabaseAuthException("Invalid or expired access token", e);
        }
        
        if (supabaseUser == null) {
            log.error("Supabase returned null user for access token");
            throw new SupabaseAuthException("Authentication failed: no user data returned");
        }
        
        UUID supabaseId = supabaseUser.getId();
        String email = supabaseUser.getEmail();
        
        if (email == null || email.isBlank()) {
            log.error("Supabase user has no email address: {}", supabaseId);
            throw new SupabaseAuthException("Email address is required for registration");
        }
        
        log.info("Implicit OAuth token verified for email={}, supabaseId={}", email, supabaseId);
        
        // Synchronize user to local database (idempotent)
        User user = syncGoogleUserToLocalDatabase(supabaseUser, role);
        
        // Create/update mapping
        ensureUserMapping(supabaseId, user.getId());
        
        log.info("Implicit OAuth complete: userId={}, role={}, registrationStatus={}", 
                user.getId(), user.getRole(), user.getRegistrationStatus());
        
        return SupabaseAuthResult.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(3600) // Default 1 hour for implicit tokens
                .user(user)
                .supabaseUserId(supabaseId)
                .build();
    }

    /**
     * Synchronizes a Google OAuth user to the local database.
     * 
     * <p><b>Idempotent Operation:</b> This method can be safely called multiple times
     * for the same user without creating duplicates or corrupting data.
     * 
     * <p><b>Scenarios Handled:</b>
     * <ol>
     *   <li><b>User exists by auth_uid:</b> Return existing user (repeat login)</li>
     *   <li><b>User exists by email:</b> Link auth_uid to existing user (migration)</li>
     *   <li><b>New user:</b> Create with INCOMPLETE status (first-time OAuth)</li>
     * </ol>
     * 
     * @param supabaseUser User data from Supabase Auth
     * @param requestedRole Role requested during OAuth initiation
     * @return Synchronized User entity
     */
    @Transactional
    protected User syncGoogleUserToLocalDatabase(SupabaseUser supabaseUser, Role requestedRole) {
        UUID authUid = supabaseUser.getId();
        String email = supabaseUser.getEmail();
        
        log.debug("Syncing Google user to local database: email={}, authUid={}", email, authUid);
        
        // SECURITY: Require confirmed email from Supabase
        if (!supabaseUser.isEmailVerified()) {
            log.error("SECURITY: Rejecting Google OAuth user with unverified email: {}", email);
            throw new SupabaseAuthException("Email address must be verified to complete authentication");
        }
        
        // SECURITY: Verify Google provider evidence via identities
        String googleSubjectId = extractGoogleSubjectId(supabaseUser);
        if (googleSubjectId == null) {
            log.error("SECURITY: No Google identity found in Supabase user identities for email={}. Rejecting.", email);
            throw new SupabaseAuthException("Google provider identity is required for Google OAuth authentication");
        }
        
        // Check if user already exists by auth_uid (repeat login)
        Optional<User> existingByAuthUid = userRepository.findByAuthUid(authUid);
        if (existingByAuthUid.isPresent()) {
            User user = existingByAuthUid.get();
            log.info("Existing user found by auth_uid: userId={}, email={}", user.getId(), user.getEmail());
            
            // Persist google_id if not already set and we have it
            if (googleSubjectId != null && user.getGoogleId() == null) {
                user.setGoogleId(googleSubjectId);
                user = userRepository.save(user);
                log.info("Persisted google_id for existing user: userId={}", user.getId());
            }
            
            // Ensure mapping exists
            ensureUserMapping(authUid, user.getId());
            
            return user;
        }
        
        // Check if user exists by email (linking scenario)
        Optional<User> existingByEmail = userRepository.findByEmail(email.toLowerCase());
        if (existingByEmail.isPresent()) {
            User user = existingByEmail.get();
            
            // SECURITY: Do NOT auto-link LOCAL password accounts by email.
            // This prevents account takeover where an attacker creates a Google account
            // with the same email as a local password user.
            if (user.getAuthUid() == null && user.getAuthProvider() == AuthProvider.LOCAL) {
                log.error("SECURITY: Refusing to auto-link LOCAL password account by email. " +
                          "email={}, existingProvider={}, attemptedAuthUid={}",
                          email, user.getAuthProvider(), authUid);
                throw new SupabaseAuthException(
                    "An account with this email already exists. Please log in with your email and password, " +
                    "then link your Google account from your profile settings.");
            }
            
            // Safe linking: user already has a Supabase auth_uid but it doesn't match
            if (user.getAuthUid() != null && !user.getAuthUid().equals(authUid)) {
                log.error("Email {} is already linked to different Supabase account: existing={}, attempted={}",
                        email, user.getAuthUid(), authUid);
                throw new SupabaseAuthException("This email is already associated with another account");
            }
            
            // Safe linking: user has SUPABASE provider but null authUid (migration edge case)
            if (user.getAuthUid() == null && user.getAuthProvider() == AuthProvider.SUPABASE) {
                user.setAuthUid(authUid);
                if (googleSubjectId != null && user.getGoogleId() == null) {
                    user.setGoogleId(googleSubjectId);
                }
                user = userRepository.save(user);
                log.info("Linked auth_uid to existing SUPABASE user: userId={}, email={}", user.getId(), email);
            }
            
            return user;
        }
        
        // Create new user (first-time OAuth registration)
        log.info("Creating new user via Google OAuth: email={}, role={}", email, requestedRole);
        
        User newUser = new User();
        newUser.setEmail(email.toLowerCase());
        newUser.setPassword("SUPABASE_OAUTH"); // Placeholder - actual auth via Supabase
        newUser.setAuthUid(authUid);
        newUser.setAuthProvider(AuthProvider.SUPABASE);
        newUser.setRole(requestedRole);
        newUser.setEnabled(true);
        newUser.setLocked(false);
        newUser.setBanned(false);
        
        // Persist Google immutable subject ID if available
        if (googleSubjectId != null) {
            newUser.setGoogleId(googleSubjectId);
        }
        
        // Set registration status to INCOMPLETE - user needs to complete profile
        newUser.setRegistrationStatus(RegistrationStatus.INCOMPLETE);
        
        // Extract name from user metadata if available
        Map<String, Object> metadata = supabaseUser.getUserMetadata();
        if (metadata != null) {
            String fullName = (String) metadata.get("full_name");
            if (fullName != null && !fullName.isBlank()) {
                String[] nameParts = fullName.split("\\s+", 2);
                newUser.setFirstName(nameParts[0]);
                newUser.setLastName(nameParts.length > 1 ? nameParts[1] : User.GOOGLE_PLACEHOLDER_LAST_NAME);
            } else {
                // Fallback to email username
                String emailPrefix = email.substring(0, email.indexOf('@'));
                newUser.setFirstName(capitalize(emailPrefix));
                newUser.setLastName(User.GOOGLE_PLACEHOLDER_LAST_NAME);
            }
            
            // Set avatar URL if available
            String avatarUrl = (String) metadata.get("avatar_url");
            if (avatarUrl != null && !avatarUrl.isBlank()) {
                newUser.setAvatarUrl(avatarUrl);
            }
        } else {
            // No metadata - use email prefix as name
            String emailPrefix = email.substring(0, email.indexOf('@'));
            newUser.setFirstName(capitalize(emailPrefix));
            newUser.setLastName(User.GOOGLE_PLACEHOLDER_LAST_NAME);
        }
        
        User savedUser = userRepository.save(newUser);
        log.info("Created new Google OAuth user: userId={}, email={}, role={}", 
                savedUser.getId(), savedUser.getEmail(), savedUser.getRole());
        
        return savedUser;
    }

    // =====================================================
    // 🔐 GOOGLE OAUTH HELPER METHODS
    // =====================================================

    /**
     * Validates and consumes an OAuth state token.
     * 
     * <p>This method performs the following validations:
     * <ul>
     *   <li>State exists in pending states (CSRF protection)</li>
     *   <li>State has not expired (replay attack prevention)</li>
     *   <li>State is consumed (one-time use)</li>
     * </ul>
     * 
     * @param state State token from callback
     * @return OAuthStateData containing role and timestamp
     * @throws SupabaseAuthException if validation fails
     */
    private OAuthStateData validateAndConsumeState(String state) {
        String stateHash = hashState(state);
        
        OAuthStateData stateData = pendingOAuthStates.remove(stateHash);
        if (stateData == null) {
            log.warn("Invalid or expired OAuth state token");
            throw new SupabaseAuthException("Invalid or expired authentication session. Please try again.");
        }
        
        // Check expiration
        if (System.currentTimeMillis() - stateData.createdAt() > STATE_EXPIRATION_MS) {
            log.warn("Expired OAuth state token (created {}ms ago)", 
                    System.currentTimeMillis() - stateData.createdAt());
            throw new SupabaseAuthException("Authentication session has expired. Please try again.");
        }
        
        log.debug("OAuth state validated and consumed for role={}", stateData.role());
        return stateData;
    }

    /**
     * Resolves the OAuth redirect URI from configuration or provided value.
     * 
     * @param providedUri Optional redirect URI from request
     * @return Effective redirect URI
     * @throws SupabaseAuthException if no redirect URI is configured
     */
    private String resolveRedirectUri(String providedUri) {
        if (providedUri != null && !providedUri.isBlank()) {
            // SECURITY: Validate provided URI is in allowlist
            if (!isRedirectUriAllowed(providedUri)) {
                log.warn("SECURITY: Rejected non-allowlisted redirect URI: {}", providedUri);
                throw new SupabaseAuthException("Invalid redirect URI");
            }
            log.debug("Using provided redirect URI (allowlisted): {}", providedUri);
            return providedUri;
        }
        
        if (oauth2RedirectUri != null && !oauth2RedirectUri.isBlank()) {
            return oauth2RedirectUri;
        }
        
        // Fallback to supabase default
        log.debug("Using default Supabase redirect");
        return null;
    }
    
    /**
     * Validates redirect URI against configured allowlist.
     * Allowlist is configured via app.oauth2-redirect-allowed-uris (comma-separated).
     * Falls back to the single configured redirect URI if allowlist is not set.
     */
    private boolean isRedirectUriAllowed(String uri) {
        if (uri == null || uri.isBlank()) return false;
        
        // Build allowlist from config
        java.util.Set<String> allowed = new java.util.HashSet<>();
        
        // Add explicitly configured allowed URIs
        if (oauth2RedirectAllowedUrisRaw != null && !oauth2RedirectAllowedUrisRaw.isBlank()) {
            for (String u : oauth2RedirectAllowedUrisRaw.split(",")) {
                String trimmed = u.trim();
                if (!trimmed.isBlank()) allowed.add(trimmed);
            }
        }
        
        // Always include the primary configured redirect URI
        if (oauth2RedirectUri != null && !oauth2RedirectUri.isBlank()) {
            allowed.add(oauth2RedirectUri);
        }
        
        if (allowed.isEmpty()) {
            log.warn("No redirect URI allowlist configured — rejecting all provided URIs for safety");
            return false;
        }
        
        return allowed.contains(uri);
    }

    /**
     * Builds the Supabase Google OAuth authorization URL.
     * 
     * <p>IMPORTANT: We do NOT add a custom state parameter here because Supabase
     * manages its own internal state for CSRF protection. Adding a custom state
     * causes "invalid state" errors. Instead, we encode any custom data (like role)
     * as query parameters in the redirect_to URL.
     * 
     * @param role The user role to encode in the redirect URL
     * @param redirectUri Redirect URI after authentication
     * @return Complete authorization URL
     */
    private String buildSupabaseGoogleAuthUrl(Role role, String redirectUri) {
        StringBuilder url = new StringBuilder();
        url.append(supabaseUrl).append("/auth/v1/authorize?");
        url.append("provider=google");
        
        // Encode the role in the redirect_to URL so we can retrieve it after callback
        // Do NOT add a custom &state= parameter - Supabase handles state internally
        if (redirectUri != null) {
            String redirectWithRole = redirectUri;
            if (!redirectUri.contains("?")) {
                redirectWithRole = redirectUri + "?role=" + role.name();
            } else {
                redirectWithRole = redirectUri + "&role=" + role.name();
            }
            url.append("&redirect_to=").append(URLEncoder.encode(redirectWithRole, StandardCharsets.UTF_8));
        }
        
        // Request additional scopes for user profile data
        url.append("&scopes=").append(URLEncoder.encode("email profile", StandardCharsets.UTF_8));
        
        return url.toString();
    }

    /**
     * Creates SHA-256 hash of state token for secure storage.
     * 
     * @param state Plain state token
     * @return Hex-encoded SHA-256 hash
     */
    private String hashState(String state) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(state.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Cleans up expired OAuth state tokens.
     * Called periodically to prevent memory leaks.
     */
    private void cleanupExpiredStates() {
        long now = System.currentTimeMillis();
        pendingOAuthStates.entrySet().removeIf(entry -> 
                now - entry.getValue().createdAt() > STATE_EXPIRATION_MS);
    }

    /**
     * Ensures a mapping exists between Supabase UUID and Rentoza user ID.
     * Idempotent - safe to call multiple times.
     * 
     * @param supabaseId Supabase Auth UUID
     * @param rentozaUserId Rentoza user ID
     */
    private void ensureUserMapping(UUID supabaseId, Long rentozaUserId) {
        if (!mappingRepository.existsById(supabaseId)) {
            SupabaseUserMapping mapping = SupabaseUserMapping.create(supabaseId, rentozaUserId);
            mappingRepository.save(mapping);
            log.debug("Created user mapping: Supabase {} -> Rentoza {}", supabaseId, rentozaUserId);
        }
    }

    /**
     * Capitalizes the first letter of a string.
     */
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }

    /**
     * Extracts the Google immutable subject ID from Supabase user identities.
     * 
     * <p>Supabase returns an array of identity objects, each containing:
     * <ul>
     *   <li>provider: "google", "email", etc.</li>
     *   <li>id: The provider's unique subject identifier (Google's `sub` claim)</li>
     * </ul>
     * 
     * @param supabaseUser The Supabase user object
     * @return Google subject ID if found, null otherwise
     */
    private String extractGoogleSubjectId(SupabaseUser supabaseUser) {
        var identities = supabaseUser.getIdentities();
        if (identities == null || identities.isEmpty()) {
            return null;
        }
        
        for (var identity : identities) {
            if ("google".equals(identity.get("provider"))) {
                // Prefer provider_id (Supabase's canonical field for the provider's subject ID)
                Object providerId = identity.get("provider_id");
                if (providerId != null && !providerId.toString().isBlank()) {
                    return providerId.toString();
                }
                // Fallback: identity_data.sub (nested object with Google's claims)
                Object identityData = identity.get("identity_data");
                if (identityData instanceof Map<?, ?> dataMap) {
                    Object sub = dataMap.get("sub");
                    if (sub != null && !sub.toString().isBlank()) {
                        return sub.toString();
                    }
                }
                // Last resort: id field (may be Supabase identity row UUID — less reliable)
                Object id = identity.get("id");
                if (id != null && !id.toString().isBlank()) {
                    log.warn("Using identity.id as Google subject — may be Supabase row UUID, not Google sub");
                    return id.toString();
                }
            }
        }
        return null;
    }

    // =====================================================
    // �🔐 REGISTRATION
    // =====================================================

    /**
     * Register a new user with Supabase Auth and create Rentoza user.
     * 
     * <p>Flow:
     * <ol>
     *   <li>Register user in Supabase Auth</li>
     *   <li>Create Rentoza user record</li>
     *   <li>Create UUID→BIGINT mapping</li>
     * </ol>
     * 
     * @param email User email
     * @param password User password
     * @param firstName User first name
     * @param lastName User last name
     * @param role User role (USER, OWNER)
     * @return Authentication result with tokens
     * @throws SupabaseAuthException if registration fails
     */
    @Transactional
    public SupabaseAuthResult register(
            String email,
            String password,
            String firstName,
            String lastName,
            Role role
    ) {
        email = email.trim().toLowerCase(java.util.Locale.ROOT);

        // Defense-in-depth: reject ADMIN and OWNER roles in self-registration
        // OWNER must go through dedicated registerOwner() with compliance validation
        if (role == null || role == Role.ADMIN || role == Role.OWNER) {
            log.warn("SECURITY: Rejected non-USER role {} for Supabase register: {}", role, email);
            role = Role.USER;
        }

        return registerInternal(email, password, firstName, lastName, role);
    }

    /**
     * Register a new OWNER via Supabase Auth.
     *
     * <p>This method is distinct from {@link #register} because owner registration
     * requires compliance validation (JMBG/PIB, consent provenance) that must be
     * enforced by the calling controller. The role is hardcoded to OWNER here to
     * prevent misuse.
     *
     * <p>Only callable from EnhancedAuthController.registerOwner() which performs
     * all owner-specific validation before calling this method.
     *
     * @param email User email
     * @param password User password
     * @param firstName User first name
     * @param lastName User last name
     * @return Authentication result with tokens and basic Rentoza user (caller enriches with owner fields)
     * @throws SupabaseAuthException if registration fails
     */
    @Transactional
    public SupabaseAuthResult registerOwner(
            String email,
            String password,
            String firstName,
            String lastName
    ) {
        email = email.trim().toLowerCase(java.util.Locale.ROOT);
        return registerInternal(email, password, firstName, lastName, Role.OWNER);
    }

    /**
     * Common registration logic shared by {@link #register} and {@link #registerOwner}.
     */
    private SupabaseAuthResult registerInternal(
            String email,
            String password,
            String firstName,
            String lastName,
            Role role
    ) {
        email = email.trim().toLowerCase(java.util.Locale.ROOT);

        // Check if email already exists in Rentoza
        if (userRepository.findByEmail(email).isPresent()) {
            throw new SupabaseAuthException("Email already registered");
        }

        // Prepare metadata for Supabase
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("firstName", firstName);
        metadata.put("lastName", lastName);
        metadata.put("role", role.name());

        // Register with Supabase Auth
        SupabaseAuthResponse supabaseResponse = supabaseClient.signUp(email, password, metadata);
        
        // Handle case where Supabase doesn't return user data
        if (supabaseResponse.getUser() == null) {
            log.error("Supabase signup did not return user data for: {}", email);
            throw new SupabaseAuthException("Supabase registration failed: no user data returned. Check Supabase dashboard for user status.");
        }

        UUID supabaseId = supabaseResponse.getUser().getId();
        boolean emailConfirmationPending = supabaseResponse.isEmailConfirmationPending();
        
        log.info("User registered in Supabase: {} -> {} (emailConfirmationPending={})", 
                email, supabaseId, emailConfirmationPending);

        // Wrap local DB writes in try-catch — if they fail, compensate by
        // deleting the orphaned Supabase user so the email is not "taken".
        User user;
        try {
            // Create Rentoza user - we do this even if email confirmation is pending
            // The user will have limited access until email is confirmed
            user = new User();
            user.setEmail(email);
            user.setPassword("SUPABASE_AUTH"); // Placeholder - actual password in Supabase
            user.setFirstName(firstName);
            user.setLastName(lastName);
            user.setRole(role);
            user.setEnabled(!emailConfirmationPending); // Disabled until email confirmed if pending
            user.setAuthUid(supabaseId); // ✅ CRITICAL: Required for RLS policies
            user.setLocked(false);
            user.setBanned(false);
            
            user = userRepository.save(user);
            log.info("Rentoza user created: {} -> {} (enabled={})", email, user.getId(), user.isEnabled());

            // Create mapping
            SupabaseUserMapping mapping = SupabaseUserMapping.create(supabaseId, user.getId());
            mappingRepository.save(mapping);
            log.info("User mapping created: Supabase {} -> Rentoza {}", supabaseId, user.getId());
        } catch (Exception ex) {
            log.error("COMPENSATION: Local DB write failed after Supabase signup for {}. Deleting Supabase user {}.", email, supabaseId, ex);
            supabaseClient.deleteUser(supabaseId);
            throw new SupabaseAuthException("Registration failed: could not create local account. Please try again.", ex);
        }

        return SupabaseAuthResult.builder()
                .accessToken(supabaseResponse.getAccessToken()) // May be null if email confirmation pending
                .refreshToken(supabaseResponse.getRefreshToken()) // May be null if email confirmation pending
                .expiresIn(supabaseResponse.getExpiresIn())
                .user(user)
                .supabaseUserId(supabaseId)
                .emailConfirmationPending(emailConfirmationPending)
                .build();
    }

    // =====================================================
    // 🔑 LOGIN
    // =====================================================

    /**
     * Login user with email and password.
     * 
     * <p>Flow:
     * <ol>
     *   <li>Authenticate with Supabase Auth</li>
     *   <li>Look up Rentoza user via mapping</li>
     *   <li>Validate user status (not banned, etc.)</li>
     * </ol>
     * 
     * @param email User email
     * @param password User password
     * @return Authentication result with tokens
     * @throws SupabaseAuthException if login fails
     */
    @Transactional(readOnly = true)
    public SupabaseAuthResult login(String email, String password) {
        email = email.trim().toLowerCase(java.util.Locale.ROOT);

        // Authenticate with Supabase
        SupabaseAuthResponse supabaseResponse = supabaseClient.signInWithPassword(email, password);
        
        if (supabaseResponse.getUser() == null) {
            throw new SupabaseAuthException("Authentication failed");
        }

        UUID supabaseId = supabaseResponse.getUser().getId();

        // Look up Rentoza user via mapping
        Optional<SupabaseUserMapping> mappingOpt = mappingRepository.findById(supabaseId);
        if (mappingOpt.isEmpty()) {
            log.warn("Supabase user {} has no Rentoza mapping — auto-linking disabled for security", supabaseId);
            throw new SupabaseAuthException("User account not found. Please register.");
        }

        Long rentozaUserId = mappingOpt.get().getRentozaUserId();
        User user = userRepository.findById(rentozaUserId)
                .orElseThrow(() -> new SupabaseAuthException("User account not found"));

        // Validate user status
        if (user.isBanned()) {
            throw new SupabaseAuthException("Your account has been suspended");
        }

        return buildAuthResult(supabaseResponse, user, supabaseId);
    }

    // =====================================================
    // 🔄 TOKEN REFRESH
    // =====================================================

    /**
     * Refresh access token using refresh token.
     * 
     * @param refreshToken Supabase refresh token
     * @return New authentication result with fresh tokens
     * @throws SupabaseAuthException if refresh fails
     */
    @Transactional(readOnly = true)
    public SupabaseAuthResult refreshToken(String refreshToken) {
        SupabaseAuthResponse supabaseResponse = supabaseClient.refreshToken(refreshToken);
        
        if (supabaseResponse.getUser() == null) {
            throw new SupabaseAuthException("Token refresh failed");
        }

        UUID supabaseId = supabaseResponse.getUser().getId();

        // Look up Rentoza user
        Optional<SupabaseUserMapping> mappingOpt = mappingRepository.findById(supabaseId);
        if (mappingOpt.isEmpty()) {
            throw new SupabaseAuthException("User mapping not found");
        }

        User user = userRepository.findById(mappingOpt.get().getRentozaUserId())
                .orElseThrow(() -> new SupabaseAuthException("User not found"));

        if (user.isBanned()) {
            throw new SupabaseAuthException("Your account has been suspended");
        }

        return buildAuthResult(supabaseResponse, user, supabaseId);
    }

    // =====================================================
    // ✉️ EMAIL CONFIRMATION
    // =====================================================

    /**
     * Handle email confirmation callback.
     * 
     * <p>Flow:
     * <ol>
     *   <li>Validate the Supabase access token</li>
     *   <li>Extract user info from token</li>
     *   <li>Enable the Rentoza user account</li>
     *   <li>Return auth result with tokens</li>
     * </ol>
     * 
     * @param accessToken Supabase access token from email verification
     * @param refreshToken Optional refresh token
     * @return Authentication result with enabled user
     * @throws SupabaseAuthException if validation fails
     */
    @Transactional
    public SupabaseAuthResult confirmEmail(String accessToken, String refreshToken) {
        // Validate the access token and extract user info
        if (!supabaseJwtUtil.validateToken(accessToken)) {
            throw new SupabaseAuthException("Invalid or expired access token");
        }

        // Get Supabase user ID from token
        UUID supabaseId = supabaseJwtUtil.getSupabaseUserId(accessToken);
        String email = supabaseJwtUtil.getEmailFromToken(accessToken);
        
        log.info("Processing email confirmation for: {} (Supabase ID: {})", email, supabaseId);

        // Find the mapping and Rentoza user
        Optional<SupabaseUserMapping> mappingOpt = mappingRepository.findById(supabaseId);
        
        if (mappingOpt.isEmpty()) {
            throw new SupabaseAuthException("User mapping not found. Please register again.");
        }

        SupabaseUserMapping mapping = mappingOpt.get();
        Optional<User> userOpt = userRepository.findById(mapping.getRentozaUserId());
        
        if (userOpt.isEmpty()) {
            throw new SupabaseAuthException("User not found in database.");
        }

        User user = userOpt.get();
        
        // Enable the user account (was disabled during registration if email confirmation was required)
        if (!user.isEnabled()) {
            user.setEnabled(true);
            user = userRepository.save(user);
            log.info("User enabled after email confirmation: {}", email);
        }

        return SupabaseAuthResult.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(3600) // Standard 1 hour
                .user(user)
                .supabaseUserId(supabaseId)
                .emailConfirmationPending(false)
                .build();
    }

    // =====================================================
    // 🚪 LOGOUT
    // =====================================================

    /**
     * Logout user and revoke their Supabase tokens.
     * 
     * @param accessToken User's current access token
     */
    public void logout(String accessToken) {
        try {
            supabaseClient.signOut(accessToken);
            log.debug("User logged out from Supabase");
        } catch (Exception e) {
            // Logout failures are not critical
            log.warn("Supabase logout failed: {}", e.getMessage());
        }
    }

    // =====================================================
    // 🔗 USER LINKING (For OAuth2)
    // =====================================================

    /**
     * Link an existing Rentoza user to a Supabase Auth user.
     * Used when OAuth2 user exists in Rentoza but not mapped to Supabase.
     * 
     * @param supabaseId Supabase Auth UUID
     * @param rentozaUserId Rentoza user ID
     */
    @Transactional
    public void linkUsers(UUID supabaseId, Long rentozaUserId) {
        // Check if mapping already exists
        if (mappingRepository.existsById(supabaseId)) {
            log.debug("Mapping already exists for Supabase ID: {}", supabaseId);
            return;
        }

        if (mappingRepository.existsByRentozaUserId(rentozaUserId)) {
            log.warn("Rentoza user {} already has a Supabase mapping", rentozaUserId);
            return;
        }

        SupabaseUserMapping mapping = SupabaseUserMapping.create(supabaseId, rentozaUserId);
        mappingRepository.save(mapping);
        log.info("Linked Supabase {} to Rentoza {}", supabaseId, rentozaUserId);
    }

    /**
     * Get Supabase UUID for a Rentoza user.
     * 
     * @param rentozaUserId Rentoza user ID
     * @return Supabase UUID if mapped
     */
    public Optional<UUID> getSupabaseId(Long rentozaUserId) {
        return mappingRepository.findByRentozaUserId(rentozaUserId)
                .map(SupabaseUserMapping::getSupabaseId);
    }

    /**
     * Get Rentoza user ID for a Supabase user.
     * 
     * @param supabaseId Supabase UUID
     * @return Rentoza user ID if mapped
     */
    public Optional<Long> getRentozaUserId(UUID supabaseId) {
        return mappingRepository.findById(supabaseId)
                .map(SupabaseUserMapping::getRentozaUserId);
    }

    // =====================================================
    // 🔧 HELPER METHODS
    // =====================================================

    private SupabaseAuthResult buildAuthResult(
            SupabaseAuthResponse supabaseResponse,
            User user,
            UUID supabaseId
    ) {
        return SupabaseAuthResult.builder()
                .accessToken(supabaseResponse.getAccessToken())
                .refreshToken(supabaseResponse.getRefreshToken())
                .expiresIn(supabaseResponse.getExpiresIn())
                .user(user)
                .supabaseUserId(supabaseId)
                .build();
    }

    // =====================================================
    // 📦 RESULT CLASS
    // =====================================================

    /**
     * Result of Supabase authentication operations.
     * 
     * <p>When emailConfirmationPending is true:
     * <ul>
     *   <li>accessToken and refreshToken will be null</li>
     *   <li>User is created but disabled until email is confirmed</li>
     *   <li>Frontend should show "check your email" message</li>
     * </ul>
     */
    @lombok.Builder
    @lombok.Getter
    public static class SupabaseAuthResult {
        private final String accessToken;
        private final String refreshToken;
        private final long expiresIn;
        private final User user;
        private final UUID supabaseUserId;
        
        /** True if email confirmation is required before login */
        @lombok.Builder.Default
        private final boolean emailConfirmationPending = false;
    }

    /**
     * Result of Google OAuth initialization.
     * 
     * <p>Contains the authorization URL to redirect the user to and the state
     * token for CSRF validation during callback.
     * 
     * @param authorizationUrl Full URL to redirect user to for Google OAuth
     * @param state State token to validate during callback (stored by frontend)
     */
    public record GoogleAuthInitResult(String authorizationUrl, String state) {}

    /**
     * Internal state data for pending OAuth requests.
     * 
     * <p>Stores the role and creation timestamp for CSRF protection
     * and state expiration validation.
     * 
     * @param role User role to assign after successful authentication
     * @param createdAt Timestamp when state was created (for expiration)
     */
    private record OAuthStateData(Role role, long createdAt) {}
}

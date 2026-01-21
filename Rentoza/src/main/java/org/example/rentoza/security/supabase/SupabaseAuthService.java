package org.example.rentoza.security.supabase;

import org.example.rentoza.security.supabase.SupabaseAuthClient.SupabaseAuthException;
import org.example.rentoza.security.supabase.SupabaseAuthClient.SupabaseAuthResponse;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for Supabase authentication operations.
 * 
 * <p>Handles:
 * <ul>
 *   <li>User registration (Supabase + Rentoza + mapping)</li>
 *   <li>User login (validates Supabase, returns Rentoza user)</li>
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
 * @since Phase 2 - Supabase Auth Migration
 */
@Service
public class SupabaseAuthService {

    private static final Logger log = LoggerFactory.getLogger(SupabaseAuthService.class);

    private final SupabaseAuthClient supabaseClient;
    private final SupabaseUserMappingRepository mappingRepository;
    private final UserRepository userRepository;
    private final SupabaseJwtUtil supabaseJwtUtil;

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
    // 🔐 REGISTRATION
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

        // Create Rentoza user - we do this even if email confirmation is pending
        // The user will have limited access until email is confirmed
        User user = new User();
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
        // Authenticate with Supabase
        SupabaseAuthResponse supabaseResponse = supabaseClient.signInWithPassword(email, password);
        
        if (supabaseResponse.getUser() == null) {
            throw new SupabaseAuthException("Authentication failed");
        }

        UUID supabaseId = supabaseResponse.getUser().getId();

        // Look up Rentoza user via mapping
        Optional<SupabaseUserMapping> mappingOpt = mappingRepository.findById(supabaseId);
        if (mappingOpt.isEmpty()) {
            // User exists in Supabase but not in Rentoza - create mapping
            log.warn("Supabase user {} has no Rentoza mapping, checking by email", supabaseId);
            
            Optional<User> userOpt = userRepository.findByEmail(email);
            if (userOpt.isEmpty()) {
                throw new SupabaseAuthException("User account not found. Please register.");
            }
            
            // Create missing mapping
            User user = userOpt.get();
            SupabaseUserMapping mapping = SupabaseUserMapping.create(supabaseId, user.getId());
            mappingRepository.save(mapping);
            log.info("Created missing mapping for existing user: {} -> {}", supabaseId, user.getId());
            
            return buildAuthResult(supabaseResponse, user, supabaseId);
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
}

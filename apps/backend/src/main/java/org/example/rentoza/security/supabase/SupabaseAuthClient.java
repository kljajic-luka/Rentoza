package org.example.rentoza.security.supabase;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Supabase Auth API client.
 * 
 * <p>Handles communication with Supabase Auth REST API for:
 * <ul>
 *   <li>User registration (signup)</li>
 *   <li>User login (signin with email/password)</li>
 *   <li>Token refresh</li>
 *   <li>User logout (token revocation)</li>
 *   <li>OAuth2 token exchange</li>
 * </ul>
 * 
 * <p>API Documentation: https://supabase.com/docs/reference/javascript/auth-api
 * 
 * @since Phase 2 - Supabase Auth Migration
 */
@Component
public class SupabaseAuthClient {

    private static final Logger log = LoggerFactory.getLogger(SupabaseAuthClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String supabaseUrl;
    private final String anonKey;
    private final String serviceRoleKey;

    public SupabaseAuthClient(
            @Value("${supabase.url}") String supabaseUrl,
            @Value("${supabase.anon-key}") String anonKey,
            @Value("${supabase.service-role-key}") String serviceRoleKey,
            @Value("${supabase.http.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${supabase.http.read-timeout-ms:10000}") int readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restTemplate = new RestTemplate(factory);
        this.objectMapper = new ObjectMapper();
        this.supabaseUrl = supabaseUrl;
        this.anonKey = anonKey;
        this.serviceRoleKey = serviceRoleKey;
        log.info("SupabaseAuthClient initialized for: {} (connect={}ms, read={}ms)",
                supabaseUrl, connectTimeoutMs, readTimeoutMs);
    }

    // =====================================================
    // 🔐 AUTHENTICATION OPERATIONS
    // =====================================================

    /**
     * Register a new user with Supabase Auth.
     * 
     * @param email User's email
     * @param password User's password
     * @return SupabaseAuthResponse containing access token and user info
     * @throws SupabaseAuthException if registration fails
     */
    public SupabaseAuthResponse signUp(String email, String password) {
        return signUp(email, password, null);
    }

    /**
     * Register a new user with Supabase Auth and metadata.
     * 
     * @param email User's email
     * @param password User's password
     * @param metadata Additional user metadata (stored in raw_user_meta_data)
     * @return SupabaseAuthResponse containing access token and user info
     * @throws SupabaseAuthException if registration fails
     */
    public SupabaseAuthResponse signUp(String email, String password, Map<String, Object> metadata) {
        String url = supabaseUrl + "/auth/v1/signup";

        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", password);
        if (metadata != null && !metadata.isEmpty()) {
            body.put("data", metadata);
        }

        HttpHeaders headers = createHeaders(anonKey);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            return parseAuthResponse(response.getBody());
        } catch (HttpClientErrorException e) {
            log.error("Supabase signup failed: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new SupabaseAuthException("Registration failed: " + parseErrorMessage(e.getResponseBodyAsString()), e);
        }
    }

    /**
     * Sign in user with email and password.
     * 
     * @param email User's email
     * @param password User's password
     * @return SupabaseAuthResponse containing access token and user info
     * @throws SupabaseAuthException if login fails
     */
    public SupabaseAuthResponse signInWithPassword(String email, String password) {
        String url = supabaseUrl + "/auth/v1/token?grant_type=password";

        Map<String, String> body = new HashMap<>();
        body.put("email", email);
        body.put("password", password);

        HttpHeaders headers = createHeaders(anonKey);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            return parseAuthResponse(response.getBody());
        } catch (HttpClientErrorException e) {
            log.warn("Supabase login failed for {}: {}", email, e.getStatusCode());
            throw new SupabaseAuthException("Invalid credentials", e);
        }
    }

    /**
     * Refresh an access token using a refresh token.
     * 
     * @param refreshToken The refresh token
     * @return SupabaseAuthResponse containing new tokens
     * @throws SupabaseAuthException if refresh fails
     */
    public SupabaseAuthResponse refreshToken(String refreshToken) {
        String url = supabaseUrl + "/auth/v1/token?grant_type=refresh_token";

        Map<String, String> body = new HashMap<>();
        body.put("refresh_token", refreshToken);

        HttpHeaders headers = createHeaders(anonKey);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            return parseAuthResponse(response.getBody());
        } catch (HttpClientErrorException e) {
            log.warn("Supabase token refresh failed: {}", e.getStatusCode());
            throw new SupabaseAuthException("Token refresh failed: " + parseErrorMessage(e.getResponseBodyAsString()), e);
        }
    }

    /**
     * Sign out user and revoke their tokens.
     * 
     * @param accessToken User's access token
     */
    public void signOut(String accessToken) {
        String url = supabaseUrl + "/auth/v1/logout";

        HttpHeaders headers = createHeaders(anonKey);
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            restTemplate.postForEntity(url, request, Void.class);
            log.debug("User logged out from Supabase");
        } catch (HttpClientErrorException e) {
            // Logout failures are not critical - token may already be invalid
            log.warn("Supabase logout returned error: {}", e.getStatusCode());
        }
    }

    // =====================================================
    // � OAUTH2 TOKEN EXCHANGE
    // =====================================================

    /**
     * Exchange an OAuth2 authorization code for access and refresh tokens.
     * 
     * <p>This is used after the user completes the OAuth2 flow (e.g., Google OAuth)
     * and Supabase redirects back to our application with an authorization code.
     * 
     * <p><b>Security Note:</b> This exchange happens server-side, ensuring the
     * authorization code is never exposed to the client.
     * 
     * @param code The authorization code received from Supabase OAuth callback
     * @return SupabaseAuthResponse containing access token, refresh token, and user data
     * @throws SupabaseAuthException if the code exchange fails
     */
    public SupabaseAuthResponse exchangeCodeForToken(String code) {
        String url = supabaseUrl + "/auth/v1/token?grant_type=pkce";

        Map<String, String> body = new HashMap<>();
        body.put("auth_code", code);

        HttpHeaders headers = createHeaders(anonKey);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

        try {
            log.debug("Exchanging authorization code for tokens");
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            SupabaseAuthResponse authResponse = parseAuthResponse(response.getBody());
            log.info("Successfully exchanged authorization code for user: {}", 
                    authResponse.getUser() != null ? authResponse.getUser().getEmail() : "unknown");
            return authResponse;
        } catch (HttpClientErrorException e) {
            log.error("Authorization code exchange failed: {} - {}", 
                    e.getStatusCode(), e.getResponseBodyAsString());
            String errorMsg = parseErrorMessage(e.getResponseBodyAsString());
            throw new SupabaseAuthException("Failed to exchange authorization code: " + errorMsg, e);
        }
    }

    /**
     * Exchange an OAuth2 authorization code using the authorization_code grant type.
     * 
     * <p>Alternative method for non-PKCE flows. Use {@link #exchangeCodeForToken(String)}
     * for standard PKCE flow.
     * 
     * @param code The authorization code received from Supabase OAuth callback
     * @param redirectUri The redirect URI used in the original authorization request
     * @return SupabaseAuthResponse containing access token, refresh token, and user data
     * @throws SupabaseAuthException if the code exchange fails
     */
    public SupabaseAuthResponse exchangeCodeForTokenWithRedirect(String code, String redirectUri) {
        String url = supabaseUrl + "/auth/v1/token?grant_type=authorization_code";

        Map<String, String> body = new HashMap<>();
        body.put("code", code);
        if (redirectUri != null && !redirectUri.isBlank()) {
            body.put("redirect_uri", redirectUri);
        }

        HttpHeaders headers = createHeaders(anonKey);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

        try {
            log.debug("Exchanging authorization code for tokens (with redirect)");
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            return parseAuthResponse(response.getBody());
        } catch (HttpClientErrorException e) {
            log.error("Authorization code exchange failed: {} - {}", 
                    e.getStatusCode(), e.getResponseBodyAsString());
            String errorMsg = parseErrorMessage(e.getResponseBodyAsString());
            throw new SupabaseAuthException("Failed to exchange authorization code: " + errorMsg, e);
        }
    }
    /**
     * Get the current user using their access token.
     * 
     * <p>This is used for the implicit OAuth flow where the frontend receives
     * an access token directly. We verify the token with Supabase and retrieve
     * the user information.
     * 
     * @param accessToken Supabase access token (JWT)
     * @return SupabaseUser with user information
     * @throws SupabaseAuthException if token is invalid or expired
     */
    public SupabaseUser getUser(String accessToken) {
        String url = supabaseUrl + "/auth/v1/user";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("apikey", anonKey);

        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);

            if (response.getBody() == null) {
                log.warn("Empty response from Supabase /auth/v1/user");
                throw new SupabaseAuthException("Invalid response from authentication server");
            }

            JsonNode node = objectMapper.readTree(response.getBody());
            
            if (node.has("error")) {
                String error = node.get("error").asText("Unknown error");
                log.warn("Supabase user lookup error: {}", error);
                throw new SupabaseAuthException(error);
            }

            // Parse user from response
            SupabaseUser user = objectMapper.treeToValue(node, SupabaseUser.class);
            log.debug("Successfully retrieved user from access token: email={}", user.getEmail());
            
            return user;
        } catch (HttpClientErrorException e) {
            log.error("User lookup failed: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode().value() == 401) {
                throw new SupabaseAuthException("Invalid or expired access token", e);
            }
            throw new SupabaseAuthException("Failed to verify access token: " + e.getMessage(), e);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse user response from Supabase", e);
            throw new SupabaseAuthException("Invalid response format", e);
        }
    }
    // =====================================================
    // �👤 USER MANAGEMENT (Service Role)
    // =====================================================

    /**
     * Get user by their Supabase UUID (requires service role key).
     * 
     * @param userId Supabase Auth user UUID
     * @return User information or null if not found
     */
    public SupabaseUser getUserById(UUID userId) {
        String url = supabaseUrl + "/auth/v1/admin/users/" + userId;

        HttpHeaders headers = createHeaders(serviceRoleKey);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
            JsonNode node = objectMapper.readTree(response.getBody());
            return parseUser(node);
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("User not found in Supabase: {}", userId);
            return null;
        } catch (Exception e) {
            log.error("Error fetching user from Supabase: {}", e.getMessage());
            throw new SupabaseAuthException("Failed to fetch user", e);
        }
    }

    /**
     * Get user by email (requires service role key).
     * Uses admin API to look up user by email.
     * 
     * @param email User's email
     * @return User information or null if not found
     */
    public SupabaseUser getUserByEmail(String email) {
        String url = supabaseUrl + "/auth/v1/admin/users?filter=email.eq." + email;

        HttpHeaders headers = createHeaders(serviceRoleKey);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode users = root.has("users") ? root.get("users") : root;
            
            if (users.isArray() && users.size() > 0) {
                return parseUser(users.get(0));
            }
            return null;
        } catch (Exception e) {
            log.error("Error fetching user by email from Supabase: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Update user metadata in Supabase (requires service role key).
     * 
     * @param userId Supabase Auth user UUID
     * @param metadata Metadata to update
     */
    public void updateUserMetadata(UUID userId, Map<String, Object> metadata) {
        String url = supabaseUrl + "/auth/v1/admin/users/" + userId;

        Map<String, Object> body = new HashMap<>();
        body.put("user_metadata", metadata);

        HttpHeaders headers = createHeaders(serviceRoleKey);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            restTemplate.exchange(url, HttpMethod.PUT, request, Void.class);
            log.debug("Updated user metadata for: {}", userId);
        } catch (HttpClientErrorException e) {
            log.error("Failed to update user metadata: {}", e.getResponseBodyAsString());
            throw new SupabaseAuthException("Failed to update user metadata", e);
        }
    }

    /**
     * Delete a user from Supabase Auth (requires service role key).
     * 
     * <p>This method is also used for <b>compensation</b>: if local DB writes fail after
     * a successful Supabase signup, the orphaned Supabase user is deleted to keep the
     * two systems in sync. For that reason, failures are logged but not rethrown.
     * 
     * @param userId Supabase Auth user UUID
     */
    public void deleteUser(UUID userId) {
        String url = supabaseUrl + "/auth/v1/admin/users/" + userId;

        HttpHeaders headers = createHeaders(serviceRoleKey);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            restTemplate.exchange(url, HttpMethod.DELETE, request, Void.class);
            log.info("Compensated: deleted Supabase user {}", userId);
        } catch (Exception e) {
            log.error("COMPENSATION FAILED: Could not delete Supabase user {}. Manual cleanup required.", userId, e);
        }
    }

    // =====================================================
    // � PASSWORD MANAGEMENT
    // =====================================================

    /**
     * Update a user's password via Supabase Admin API (requires service role key).
     *
     * @param userId Supabase Auth user UUID
     * @param newPassword New plain-text password (Supabase hashes it)
     * @throws SupabaseAuthException if update fails
     */
    public void updateUserPassword(UUID userId, String newPassword) {
        String url = supabaseUrl + "/auth/v1/admin/users/" + userId;

        Map<String, Object> body = new HashMap<>();
        body.put("password", newPassword);

        HttpHeaders headers = createHeaders(serviceRoleKey);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            restTemplate.exchange(url, HttpMethod.PUT, request, Void.class);
            log.info("Password updated in Supabase for user: {}", userId);
        } catch (HttpClientErrorException e) {
            log.error("Failed to update password in Supabase: {}", e.getResponseBodyAsString());
            throw new SupabaseAuthException("Failed to update password", e);
        }
    }

    /**
     * Send password reset email via Supabase Auth.
     *
     * <p>Uses Supabase's built-in password recovery email with a custom redirect URL.
     * The redirect URL points to our frontend reset-password page.
     *
     * @param email User's email address
     * @param redirectTo Custom redirect URL (our frontend reset page with token)
     * @throws SupabaseAuthException if email sending fails
     */
    public void sendPasswordResetEmail(String email, String redirectTo) {
        String url = supabaseUrl + "/auth/v1/recover";

        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        if (redirectTo != null) {
            body.put("gotrue_meta_security", Map.of("captcha_token", ""));
        }

        HttpHeaders headers = createHeaders(anonKey);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            restTemplate.postForEntity(url, request, String.class);
            log.info("Password reset email sent via Supabase for: {}", email);
        } catch (HttpClientErrorException e) {
            log.error("Failed to send password reset email: {}", e.getResponseBodyAsString());
            throw new SupabaseAuthException("Failed to send reset email", e);
        }
    }

    // =====================================================
    // �🔧 HELPER METHODS
    // =====================================================

    private HttpHeaders createHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("apikey", apiKey);
        headers.set("Authorization", "Bearer " + apiKey);
        return headers;
    }

    private SupabaseAuthResponse parseAuthResponse(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            log.debug("Parsing Supabase auth response (length={})", json != null ? json.length() : 0);
            
            SupabaseAuthResponse response = new SupabaseAuthResponse();
            response.setAccessToken(node.path("access_token").asText(null));
            response.setRefreshToken(node.path("refresh_token").asText(null));
            response.setTokenType(node.path("token_type").asText("bearer"));
            response.setExpiresIn(node.path("expires_in").asLong(3600));
            response.setExpiresAt(node.path("expires_at").asLong(0));
            
            // Handle user object - can be at root level or nested
            if (node.has("user") && !node.get("user").isNull()) {
                response.setUser(parseUser(node.get("user")));
            }
            
            // For email confirmation flow: check if this is a "pending confirmation" response
            // Supabase returns user data at root level in some cases
            if (response.getUser() == null && node.has("id")) {
                response.setUser(parseUser(node));
            }
            
            // Determine if email confirmation is pending
            boolean emailConfirmationPending = response.getUser() != null 
                    && response.getAccessToken() == null
                    && response.getUser().getEmailConfirmedAt() == null;
            response.setEmailConfirmationPending(emailConfirmationPending);
            
            log.debug("Parsed auth response: user={}, hasAccessToken={}, hasRefreshToken={}, emailConfirmPending={}",
                    response.getUser() != null ? response.getUser().getId() : "null",
                    response.getAccessToken() != null,
                    response.getRefreshToken() != null,
                    response.isEmailConfirmationPending());
            
            return response;
        } catch (Exception e) {
            log.error("Error parsing Supabase auth response: {}", e.getMessage());
            throw new SupabaseAuthException("Failed to parse auth response", e);
        }
    }

    private SupabaseUser parseUser(JsonNode node) {
        SupabaseUser user = new SupabaseUser();
        user.setId(UUID.fromString(node.path("id").asText()));
        user.setEmail(node.path("email").asText(null));
        user.setPhone(node.path("phone").asText(null));
        user.setEmailConfirmedAt(node.path("email_confirmed_at").asText(null));
        user.setConfirmedAt(node.path("confirmed_at").asText(null));
        user.setPhoneConfirmedAt(node.path("phone_confirmed_at").asText(null));
        user.setCreatedAt(node.path("created_at").asText(null));
        user.setUpdatedAt(node.path("updated_at").asText(null));
        
        // Parse user metadata
        JsonNode metaNode = node.path("user_metadata");
        if (!metaNode.isMissingNode()) {
            Map<String, Object> metadata = new HashMap<>();
            metaNode.fields().forEachRemaining(entry -> 
                metadata.put(entry.getKey(), entry.getValue().asText())
            );
            user.setUserMetadata(metadata);
        }
        
        // Parse app metadata
        JsonNode appMetaNode = node.path("app_metadata");
        if (!appMetaNode.isMissingNode()) {
            Map<String, Object> appMetadata = new HashMap<>();
            appMetaNode.fields().forEachRemaining(entry -> 
                appMetadata.put(entry.getKey(), entry.getValue().asText())
            );
            user.setAppMetadata(appMetadata);
        }
        
        // Parse identities array — preserve nested objects (e.g. identity_data)
        JsonNode identitiesNode = node.path("identities");
        if (identitiesNode.isArray()) {
            java.util.List<Map<String, Object>> identities = new java.util.ArrayList<>();
            for (JsonNode identityNode : identitiesNode) {
                Map<String, Object> identity = new HashMap<>();
                identityNode.fields().forEachRemaining(entry -> {
                    JsonNode val = entry.getValue();
                    if (val.isObject()) {
                        // Preserve nested objects as Map (e.g. identity_data)
                        Map<String, Object> nested = new HashMap<>();
                        val.fields().forEachRemaining(nestedEntry ->
                            nested.put(nestedEntry.getKey(), nestedEntry.getValue().asText())
                        );
                        identity.put(entry.getKey(), nested);
                    } else {
                        identity.put(entry.getKey(), val.asText());
                    }
                });
                identities.add(identity);
            }
            user.setIdentities(identities);
        }
        
        return user;
    }

    private String parseErrorMessage(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.has("error_description")) {
                return node.get("error_description").asText();
            }
            if (node.has("msg")) {
                return node.get("msg").asText();
            }
            if (node.has("message")) {
                return node.get("message").asText();
            }
            return json;
        } catch (Exception e) {
            return json;
        }
    }

    // =====================================================
    // 📦 RESPONSE/MODEL CLASSES
    // =====================================================

    /**
     * Response from Supabase Auth API containing tokens and user info.
     */
    public static class SupabaseAuthResponse {
        @JsonProperty("access_token")
        private String accessToken;
        
        @JsonProperty("refresh_token")
        private String refreshToken;
        
        @JsonProperty("token_type")
        private String tokenType;
        
        @JsonProperty("expires_in")
        private long expiresIn;
        
        @JsonProperty("expires_at")
        private long expiresAt;
        
        private SupabaseUser user;

        // Getters and setters
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        
        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
        
        public String getTokenType() { return tokenType; }
        public void setTokenType(String tokenType) { this.tokenType = tokenType; }
        
        public long getExpiresIn() { return expiresIn; }
        public void setExpiresIn(long expiresIn) { this.expiresIn = expiresIn; }
        
        public long getExpiresAt() { return expiresAt; }
        public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }
        
        public SupabaseUser getUser() { return user; }
        public void setUser(SupabaseUser user) { this.user = user; }
        
        // Email confirmation pending flag - set when signup requires email verification
        private boolean emailConfirmationPending;
        
        public boolean isEmailConfirmationPending() { return emailConfirmationPending; }
        public void setEmailConfirmationPending(boolean pending) { this.emailConfirmationPending = pending; }
    }

    /**
     * Supabase Auth user representation.
     * Uses @JsonIgnoreProperties to handle unknown fields from Supabase API gracefully.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SupabaseUser {
        private UUID id;
        private String email;
        private String phone;
        private String emailConfirmedAt;
        private String confirmedAt;
        private String phoneConfirmedAt;
        private String createdAt;
        private String updatedAt;
        private Map<String, Object> userMetadata;
        private Map<String, Object> appMetadata;
        private java.util.List<Map<String, Object>> identities;

        // Getters and setters
        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        
        public String getEmailConfirmedAt() { return emailConfirmedAt; }
        public void setEmailConfirmedAt(String emailConfirmedAt) { this.emailConfirmedAt = emailConfirmedAt; }

        public String getConfirmedAt() { return confirmedAt; }
        public void setConfirmedAt(String confirmedAt) { this.confirmedAt = confirmedAt; }
        
        public String getPhoneConfirmedAt() { return phoneConfirmedAt; }
        public void setPhoneConfirmedAt(String phoneConfirmedAt) { this.phoneConfirmedAt = phoneConfirmedAt; }
        
        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
        
        public String getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
        
        public Map<String, Object> getUserMetadata() { return userMetadata; }
        public void setUserMetadata(Map<String, Object> userMetadata) { this.userMetadata = userMetadata; }
        
        public Map<String, Object> getAppMetadata() { return appMetadata; }
        public void setAppMetadata(Map<String, Object> appMetadata) { this.appMetadata = appMetadata; }
        
        public java.util.List<Map<String, Object>> getIdentities() { return identities; }
        public void setIdentities(java.util.List<Map<String, Object>> identities) { this.identities = identities; }
        
        public boolean isEmailVerified() {
            if (hasText(emailConfirmedAt) || hasText(confirmedAt)) {
                return true;
            }

            if (appMetadata != null && isTruthy(appMetadata.get("email_verified"))) {
                return true;
            }

            if (identities != null) {
                for (Map<String, Object> identity : identities) {
                    Object provider = identity.get("provider");
                    if (provider == null || !"google".equalsIgnoreCase(provider.toString())) {
                        continue;
                    }

                    if (isTruthy(identity.get("email_verified"))) {
                        return true;
                    }

                    Object identityData = identity.get("identity_data");
                    if (identityData instanceof Map<?, ?> dataMap) {
                        if (isTruthy(dataMap.get("email_verified")) || isTruthy(dataMap.get("emailVerified"))) {
                            return true;
                        }
                    }
                }
            }

            return false;
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }

        private static boolean isTruthy(Object value) {
            if (value == null) {
                return false;
            }
            if (value instanceof Boolean b) {
                return b;
            }
            return "true".equalsIgnoreCase(value.toString().trim());
        }
    }

    /**
     * Exception thrown when Supabase Auth operations fail.
     */
    public static class SupabaseAuthException extends RuntimeException {
        public SupabaseAuthException(String message) {
            super(message);
        }

        public SupabaseAuthException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

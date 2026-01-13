package org.example.rentoza.security.supabase;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

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
            @Value("${supabase.service-role-key}") String serviceRoleKey
    ) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.supabaseUrl = supabaseUrl;
        this.anonKey = anonKey;
        this.serviceRoleKey = serviceRoleKey;
        log.info("SupabaseAuthClient initialized for: {}", supabaseUrl);
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
    // 👤 USER MANAGEMENT (Service Role)
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
     * @param userId Supabase Auth user UUID
     */
    public void deleteUser(UUID userId) {
        String url = supabaseUrl + "/auth/v1/admin/users/" + userId;

        HttpHeaders headers = createHeaders(serviceRoleKey);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            restTemplate.exchange(url, HttpMethod.DELETE, request, Void.class);
            log.info("Deleted user from Supabase: {}", userId);
        } catch (HttpClientErrorException e) {
            log.error("Failed to delete user from Supabase: {}", e.getResponseBodyAsString());
            throw new SupabaseAuthException("Failed to delete user", e);
        }
    }

    // =====================================================
    // 🔧 HELPER METHODS
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
            log.debug("Parsing Supabase auth response: {}", json);
            
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
     */
    public static class SupabaseUser {
        private UUID id;
        private String email;
        private String phone;
        private String emailConfirmedAt;
        private String phoneConfirmedAt;
        private String createdAt;
        private String updatedAt;
        private Map<String, Object> userMetadata;

        // Getters and setters
        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        
        public String getEmailConfirmedAt() { return emailConfirmedAt; }
        public void setEmailConfirmedAt(String emailConfirmedAt) { this.emailConfirmedAt = emailConfirmedAt; }
        
        public String getPhoneConfirmedAt() { return phoneConfirmedAt; }
        public void setPhoneConfirmedAt(String phoneConfirmedAt) { this.phoneConfirmedAt = phoneConfirmedAt; }
        
        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
        
        public String getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
        
        public Map<String, Object> getUserMetadata() { return userMetadata; }
        public void setUserMetadata(Map<String, Object> userMetadata) { this.userMetadata = userMetadata; }
        
        public boolean isEmailVerified() {
            return emailConfirmedAt != null && !emailConfirmedAt.isEmpty();
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

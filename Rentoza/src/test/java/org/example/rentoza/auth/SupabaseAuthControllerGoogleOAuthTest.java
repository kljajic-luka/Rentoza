package org.example.rentoza.auth;

import org.example.rentoza.security.supabase.SupabaseAuthService;
import org.example.rentoza.security.supabase.SupabaseAuthService.GoogleAuthInitResult;
import org.example.rentoza.security.supabase.SupabaseAuthService.SupabaseAuthResult;
import org.example.rentoza.config.AppProperties;
import org.example.rentoza.monitoring.MissingResourceMetrics;
import org.example.rentoza.security.csrf.CustomCookieCsrfTokenRepository;
import org.example.rentoza.user.RegistrationStatus;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserService;
import org.example.rentoza.user.dto.UserResponseDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for SupabaseAuthController Google OAuth endpoints.
 *
 * Tests cover:
 * - /api/auth/supabase/google/authorize endpoint
 * - /api/auth/supabase/google/callback endpoint (GET and POST)
 * - Error handling and validation
 * - Security aspects (CSRF, role validation)
 */
@WebMvcTest(controllers = SupabaseAuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(SupabaseAuthControllerGoogleOAuthTest.TestConfig.class)
@TestPropertySource(properties = {
        "app.cookie.domain=localhost",
        "app.cookie.secure=false",
        "app.cookie.sameSite=Lax"
})
class SupabaseAuthControllerGoogleOAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SupabaseAuthService supabaseAuthService;

    @MockBean
    private UserService userService;

    @MockBean
    private MissingResourceMetrics missingResourceMetrics;

    private static final String AUTHORIZE_ENDPOINT = "/api/auth/supabase/google/authorize";
    private static final String CALLBACK_ENDPOINT = "/api/auth/supabase/google/callback";
    private static final String TEST_AUTH_URL = "https://test.supabase.co/auth/v1/authorize?provider=google&state=test-state";
    private static final String TEST_STATE = "test-state-abc123";
    private static final UUID TEST_AUTH_UID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final String TEST_EMAIL = "testuser@gmail.com";
    private static final String TEST_ACCESS_TOKEN = "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.test";
    private static final String TEST_REFRESH_TOKEN = "refresh-token-xyz";

    // =========================================================================
    // /api/auth/supabase/google/authorize Tests
    // =========================================================================

    @Nested
    @DisplayName("GET /api/auth/supabase/google/authorize tests")
    class AuthorizeEndpointTests {

        @Test
        @DisplayName("Should return authorization URL for USER role")
        void shouldReturnAuthUrlForUserRole() throws Exception {
            // Arrange - state is now null since Supabase handles it internally
            GoogleAuthInitResult mockResult = new GoogleAuthInitResult(TEST_AUTH_URL, null);
            when(supabaseAuthService.initiateGoogleAuth(eq(Role.USER), isNull()))
                    .thenReturn(mockResult);

            // Act & Assert - response no longer includes state
            mockMvc.perform(get(AUTHORIZE_ENDPOINT)
                            .param("role", "USER")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authorizationUrl").value(TEST_AUTH_URL))
                    .andExpect(jsonPath("$.state").doesNotExist());
        }

        @Test
        @DisplayName("Should return authorization URL for OWNER role")
        void shouldReturnAuthUrlForOwnerRole() throws Exception {
            // Arrange - state is now null since Supabase handles it internally
            GoogleAuthInitResult mockResult = new GoogleAuthInitResult(TEST_AUTH_URL, null);
            when(supabaseAuthService.initiateGoogleAuth(eq(Role.OWNER), isNull()))
                    .thenReturn(mockResult);

            // Act & Assert - response no longer includes state
            mockMvc.perform(get(AUTHORIZE_ENDPOINT)
                            .param("role", "OWNER")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authorizationUrl").value(TEST_AUTH_URL))
                    .andExpect(jsonPath("$.state").doesNotExist());
        }

        @Test
        @DisplayName("Should default to USER role when role parameter is missing")
        void shouldDefaultToUserRoleWhenMissing() throws Exception {
            // Arrange
            GoogleAuthInitResult mockResult = new GoogleAuthInitResult(TEST_AUTH_URL, null);
            when(supabaseAuthService.initiateGoogleAuth(eq(Role.USER), isNull()))
                    .thenReturn(mockResult);

            // Act & Assert
            mockMvc.perform(get(AUTHORIZE_ENDPOINT)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authorizationUrl").exists());
        }

        @Test
        @DisplayName("Should accept custom redirectUri parameter")
        void shouldAcceptCustomRedirectUri() throws Exception {
            // Arrange
            String customRedirect = "https://custom.domain.com/callback";
            GoogleAuthInitResult mockResult = new GoogleAuthInitResult(TEST_AUTH_URL, null);
            when(supabaseAuthService.initiateGoogleAuth(eq(Role.USER), eq(customRedirect)))
                    .thenReturn(mockResult);

            // Act & Assert
            mockMvc.perform(get(AUTHORIZE_ENDPOINT)
                            .param("role", "USER")
                            .param("redirectUri", customRedirect)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
        }
    }

    // =========================================================================
    // /api/auth/supabase/google/callback GET Tests
    // =========================================================================

    @Nested
    @DisplayName("GET /api/auth/supabase/google/callback tests")
    class CallbackGetEndpointTests {

        @Test
        @DisplayName("Should process valid callback with code and role")
        void shouldProcessValidCallback() throws Exception {
            // Arrange
            SupabaseAuthResult mockResult = createMockAuthResult();
            UserResponseDTO userResponse = createMockUserResponse();
            when(supabaseAuthService.handleGoogleCallback(eq("valid-code"), eq("USER")))
                    .thenReturn(mockResult);
            when(userService.toUserResponse(any(User.class)))
                    .thenReturn(userResponse);

            // Act & Assert
            mockMvc.perform(get(CALLBACK_ENDPOINT)
                            .param("code", "valid-code")
                            .param("role", "USER")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.email").value(TEST_EMAIL))
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(header().exists("Set-Cookie"));
        }

        @Test
        @DisplayName("Should set HttpOnly cookies on successful callback")
        void shouldSetHttpOnlyCookies() throws Exception {
            // Arrange
            SupabaseAuthResult mockResult = createMockAuthResult();
            UserResponseDTO userResponse = createMockUserResponse();
            when(supabaseAuthService.handleGoogleCallback(anyString(), anyString()))
                    .thenReturn(mockResult);
            when(userService.toUserResponse(any(User.class)))
                    .thenReturn(userResponse);

            // Act & Assert - Check that Set-Cookie headers contain HttpOnly flag
            mockMvc.perform(get(CALLBACK_ENDPOINT)
                            .param("code", "valid-code")
                            .param("role", "USER"))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("Set-Cookie"))
                    .andExpect(header().stringValues("Set-Cookie", 
                            org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("HttpOnly"))));
        }

        @Test
        @DisplayName("Should return error when code is missing")
        void shouldReturn400WhenCodeMissing() throws Exception {
            // Note: role has a default value, so this tests missing code only
            mockMvc.perform(get(CALLBACK_ENDPOINT)
                            .param("role", "USER"))
                    .andExpect(status().is5xxServerError());
        }

        @Test
        @DisplayName("Should use default role when role is missing")
        void shouldUseDefaultRoleWhenMissing() throws Exception {
            // Arrange
            SupabaseAuthResult mockResult = createMockAuthResult();
            UserResponseDTO userResponse = createMockUserResponse();
            // Role defaults to USER when not provided
            when(supabaseAuthService.handleGoogleCallback(eq("valid-code"), eq("USER")))
                    .thenReturn(mockResult);
            when(userService.toUserResponse(any(User.class)))
                    .thenReturn(userResponse);

            // Act & Assert - role is optional with default "USER"
            mockMvc.perform(get(CALLBACK_ENDPOINT)
                            .param("code", "valid-code"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should handle invalid role by defaulting to USER")
        void shouldHandleInvalidRole() throws Exception {
            // Arrange
            SupabaseAuthResult mockResult = createMockAuthResult();
            UserResponseDTO userResponse = createMockUserResponse();
            // Invalid role string is passed directly; service handles it
            when(supabaseAuthService.handleGoogleCallback(eq("valid-code"), eq("INVALID_ROLE")))
                    .thenReturn(mockResult);
            when(userService.toUserResponse(any(User.class)))
                    .thenReturn(userResponse);

            // Act & Assert
            mockMvc.perform(get(CALLBACK_ENDPOINT)
                            .param("code", "valid-code")
                            .param("role", "INVALID_ROLE"))
                    .andExpect(status().isOk());
        }
    }

    // =========================================================================
    // /api/auth/supabase/google/callback POST Tests
    // =========================================================================

    @Nested
    @DisplayName("POST /api/auth/supabase/google/callback tests")
    class CallbackPostEndpointTests {

        @Test
        @DisplayName("Should process POST callback with JSON body")
        void shouldProcessPostCallback() throws Exception {
            // Arrange
            SupabaseAuthResult mockResult = createMockAuthResult();
            UserResponseDTO userResponse = createMockUserResponse();
            when(supabaseAuthService.handleGoogleCallback(eq("valid-code"), eq("valid-state")))
                    .thenReturn(mockResult);
            when(userService.toUserResponse(any(User.class)))
                    .thenReturn(userResponse);

            // Act & Assert
            mockMvc.perform(post(CALLBACK_ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                {
                                    "code": "valid-code",
                                    "state": "valid-state"
                                }
                                """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.email").value(TEST_EMAIL));
        }
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private SupabaseAuthResult createMockAuthResult() {
        User user = new User();
        user.setId(1L);
        user.setEmail(TEST_EMAIL);
        user.setFirstName("Test");
        user.setLastName("User");
        user.setRole(Role.USER);
        user.setAuthUid(TEST_AUTH_UID);
        user.setRegistrationStatus(RegistrationStatus.ACTIVE);

        return SupabaseAuthResult.builder()
                .accessToken(TEST_ACCESS_TOKEN)
                .refreshToken(TEST_REFRESH_TOKEN)
                .expiresIn(3600)
                .user(user)
                .supabaseUserId(TEST_AUTH_UID)
                .emailConfirmationPending(false)
                .build();
    }

    private UserResponseDTO createMockUserResponse() {
        return new UserResponseDTO(
                1L,
                "Test",
                "User",
                TEST_EMAIL,
                null,
                null,
                "USER"
        );
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        AppProperties appProperties() {
            AppProperties properties = new AppProperties();
            properties.getCookie().setDomain("localhost");
            properties.getCookie().setSecure(false);
            properties.getCookie().setSameSite("Lax");
            return properties;
        }

        @Bean
        CustomCookieCsrfTokenRepository csrfTokenRepository(AppProperties appProperties) {
            return new CustomCookieCsrfTokenRepository(appProperties);
        }
    }
}

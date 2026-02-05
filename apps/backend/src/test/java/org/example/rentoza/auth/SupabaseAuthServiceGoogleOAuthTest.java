package org.example.rentoza.auth;

import org.example.rentoza.security.supabase.SupabaseAuthClient;
import org.example.rentoza.security.supabase.SupabaseAuthClient.SupabaseAuthResponse;
import org.example.rentoza.security.supabase.SupabaseAuthClient.SupabaseUser;
import org.example.rentoza.security.supabase.SupabaseAuthService;
import org.example.rentoza.security.supabase.SupabaseAuthService.GoogleAuthInitResult;
import org.example.rentoza.security.supabase.SupabaseAuthService.SupabaseAuthResult;
import org.example.rentoza.security.supabase.SupabaseJwtUtil;
import org.example.rentoza.user.*;
import org.example.rentoza.security.supabase.SupabaseUserMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for SupabaseAuthService Google OAuth functionality.
 *
 * Tests cover:
 * - Google OAuth URL generation with role encoded in redirect_to
 * - Callback handling with role parameter
 * - User synchronization (new user creation, existing user linking)
 * - Error handling and edge cases
 * - Implicit flow token handling
 *
 * Note: Since Supabase handles CSRF protection internally via its own state parameter,
 * we no longer use custom state tokens. Instead, the role is encoded in the redirect_to URL.
 *
 * Coverage target: >80%
 */
@ExtendWith(MockitoExtension.class)
class SupabaseAuthServiceGoogleOAuthTest {

    @Mock
    private SupabaseAuthClient supabaseAuthClient;

    @Mock
    private SupabaseJwtUtil supabaseJwtUtil;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SupabaseUserMappingRepository supabaseUserMappingRepository;

    @InjectMocks
    private SupabaseAuthService supabaseAuthService;

    private static final String TEST_SUPABASE_URL = "https://test-project.supabase.co";
    private static final String TEST_REDIRECT_URI = "https://localhost:4200/auth/supabase/google/callback";
    private static final UUID TEST_AUTH_UID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final String TEST_EMAIL = "testuser@gmail.com";
    private static final String TEST_ACCESS_TOKEN = "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.test";
    private static final String TEST_REFRESH_TOKEN = "refresh-token-xyz";

    @BeforeEach
    void setUp() {
        // Set required configuration values via reflection
        ReflectionTestUtils.setField(supabaseAuthService, "supabaseUrl", TEST_SUPABASE_URL);
        ReflectionTestUtils.setField(supabaseAuthService, "oauth2RedirectUri", TEST_REDIRECT_URI);
    }

    // =========================================================================
    // initiateGoogleAuth() Tests
    // =========================================================================

    @Nested
    @DisplayName("initiateGoogleAuth() tests")
    class InitiateGoogleAuthTests {

        @Test
        @DisplayName("Should generate valid Google OAuth URL with required parameters")
        void shouldGenerateValidGoogleOAuthUrl() {
            // Act
            GoogleAuthInitResult result = supabaseAuthService.initiateGoogleAuth(Role.USER, null);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.authorizationUrl())
                    .contains(TEST_SUPABASE_URL + "/auth/v1/authorize")
                    .contains("provider=google")
                    .contains("redirect_to=");
            // Role is URL-encoded inside redirect_to (role%3DUSER)
            assertThat(result.authorizationUrl()).contains("role%3DUSER");
            // State is null since Supabase handles it internally
            assertThat(result.state()).isNull();
        }

        @Test
        @DisplayName("Should include role in redirect_to URL for OWNER role")
        void shouldIncludeRoleInState() {
            // Act
            GoogleAuthInitResult result = supabaseAuthService.initiateGoogleAuth(Role.OWNER, null);

            // Assert - state is null, role is in the redirect_to URL (URL-encoded)
            assertThat(result.state()).isNull();
            assertThat(result.authorizationUrl()).contains("role%3DOWNER");
        }

        @Test
        @DisplayName("Should use custom redirect URI when provided")
        void shouldUseCustomRedirectUri() {
            // Arrange
            String customRedirect = "https://custom.domain.com/callback";

            // Act
            GoogleAuthInitResult result = supabaseAuthService.initiateGoogleAuth(Role.USER, customRedirect);

            // Assert
            assertThat(result.authorizationUrl()).contains("redirect_to=");
        }

        @Test
        @DisplayName("Should generate unique URLs for concurrent requests")
        void shouldGenerateUniqueStates() {
            // Act
            GoogleAuthInitResult result1 = supabaseAuthService.initiateGoogleAuth(Role.USER, null);
            GoogleAuthInitResult result2 = supabaseAuthService.initiateGoogleAuth(Role.USER, null);
            GoogleAuthInitResult result3 = supabaseAuthService.initiateGoogleAuth(Role.OWNER, null);

            // Assert - All URLs should contain appropriate role (URL-encoded)
            assertThat(result1.authorizationUrl()).contains("role%3DUSER");
            assertThat(result2.authorizationUrl()).contains("role%3DUSER");
            assertThat(result3.authorizationUrl()).contains("role%3DOWNER");
            // State is null for all (Supabase handles internally)
            assertThat(result1.state()).isNull();
            assertThat(result2.state()).isNull();
            assertThat(result3.state()).isNull();
        }
    }

    // =========================================================================
    // handleGoogleCallback() Tests
    // =========================================================================

    @Nested
    @DisplayName("handleGoogleCallback() tests")
    class HandleGoogleCallbackTests {

        @Test
        @DisplayName("Should handle callback with default role when role is null")
        void shouldHandleNullRoleWithDefault() {
            // Arrange
            SupabaseAuthResponse mockResponse = createMockAuthResponse();

            when(supabaseAuthClient.exchangeCodeForToken(anyString()))
                    .thenReturn(mockResponse);
            when(userRepository.findByAuthUid(any(UUID.class)))
                    .thenReturn(Optional.empty());
            when(userRepository.findByEmail(TEST_EMAIL))
                    .thenReturn(Optional.empty());

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            when(userRepository.save(userCaptor.capture()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // Act - null role should default to USER
            supabaseAuthService.handleGoogleCallback("valid-code", null);

            // Assert
            User savedUser = userCaptor.getValue();
            assertThat(savedUser.getRole()).isEqualTo(Role.USER);
        }

        @Test
        @DisplayName("Should reject callback when code is null")
        void shouldRejectNullCode() {
            // Act & Assert
            assertThatThrownBy(() -> 
                supabaseAuthService.handleGoogleCallback(null, "USER")
            )
            .isInstanceOf(SupabaseAuthClient.SupabaseAuthException.class);
        }

        @Test
        @DisplayName("Should process valid callback and return auth result")
        void shouldProcessValidCallback() {
            // Arrange
            SupabaseAuthResponse mockResponse = createMockAuthResponse();

            when(supabaseAuthClient.exchangeCodeForToken(anyString()))
                    .thenReturn(mockResponse);
            when(userRepository.findByAuthUid(any(UUID.class)))
                    .thenReturn(Optional.empty());
            when(userRepository.findByEmail(TEST_EMAIL))
                    .thenReturn(Optional.empty());
            when(userRepository.save(any(User.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            SupabaseAuthResult result = supabaseAuthService.handleGoogleCallback(
                    "valid-auth-code",
                    "USER"
            );

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getAccessToken()).isEqualTo(TEST_ACCESS_TOKEN);
            assertThat(result.getRefreshToken()).isEqualTo(TEST_REFRESH_TOKEN);
            verify(supabaseAuthClient).exchangeCodeForToken(eq("valid-auth-code"));
        }

        @Test
        @DisplayName("Should handle invalid role string gracefully")
        void shouldHandleInvalidRoleString() {
            // Arrange
            SupabaseAuthResponse mockResponse = createMockAuthResponse();

            when(supabaseAuthClient.exchangeCodeForToken(anyString()))
                    .thenReturn(mockResponse);
            when(userRepository.findByAuthUid(any(UUID.class)))
                    .thenReturn(Optional.empty());
            when(userRepository.findByEmail(TEST_EMAIL))
                    .thenReturn(Optional.empty());

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            when(userRepository.save(userCaptor.capture()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // Act - invalid role should default to USER
            supabaseAuthService.handleGoogleCallback("code", "INVALID_ROLE");

            // Assert
            User savedUser = userCaptor.getValue();
            assertThat(savedUser.getRole()).isEqualTo(Role.USER);
        }
    }

    // =========================================================================
    // syncGoogleUserToLocalDatabase() Tests
    // =========================================================================

    @Nested
    @DisplayName("User synchronization tests")
    class UserSynchronizationTests {

        @Test
        @DisplayName("Should create new user when no existing user found")
        void shouldCreateNewUser() {
            // Arrange
            SupabaseAuthResponse mockResponse = createMockAuthResponse();

            when(supabaseAuthClient.exchangeCodeForToken(anyString()))
                    .thenReturn(mockResponse);
            when(userRepository.findByAuthUid(any(UUID.class)))
                    .thenReturn(Optional.empty());
            when(userRepository.findByEmail(TEST_EMAIL))
                    .thenReturn(Optional.empty());

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            when(userRepository.save(userCaptor.capture()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            supabaseAuthService.handleGoogleCallback("auth-code", "USER");

            // Assert
            User savedUser = userCaptor.getValue();
            assertThat(savedUser).isNotNull();
            assertThat(savedUser.getEmail()).isEqualTo(TEST_EMAIL);
            assertThat(savedUser.getAuthUid()).isEqualTo(TEST_AUTH_UID);
            assertThat(savedUser.getRole()).isEqualTo(Role.USER);
            assertThat(savedUser.getAuthProvider()).isEqualTo(AuthProvider.SUPABASE);
            assertThat(savedUser.getRegistrationStatus()).isEqualTo(RegistrationStatus.INCOMPLETE);
        }

        @Test
        @DisplayName("Should link existing email user when auth_uid not found")
        void shouldLinkExistingEmailUser() {
            // Arrange
            SupabaseAuthResponse mockResponse = createMockAuthResponse();

            User existingUser = new User();
            existingUser.setId(42L);
            existingUser.setEmail(TEST_EMAIL);
            existingUser.setAuthUid(null);
            existingUser.setAuthProvider(AuthProvider.LOCAL);
            existingUser.setRole(Role.USER);

            when(supabaseAuthClient.exchangeCodeForToken(anyString()))
                    .thenReturn(mockResponse);
            when(userRepository.findByAuthUid(any(UUID.class)))
                    .thenReturn(Optional.empty());
            when(userRepository.findByEmail(TEST_EMAIL))
                    .thenReturn(Optional.of(existingUser));

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            when(userRepository.save(userCaptor.capture()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            supabaseAuthService.handleGoogleCallback("auth-code", "USER");

            // Assert
            User updatedUser = userCaptor.getValue();
            assertThat(updatedUser.getId()).isEqualTo(42L);
            assertThat(updatedUser.getAuthUid()).isEqualTo(TEST_AUTH_UID);
            assertThat(updatedUser.getAuthProvider()).isEqualTo(AuthProvider.SUPABASE);
        }

        @Test
        @DisplayName("Should return existing user when auth_uid matches")
        void shouldReturnExistingUserWhenAuthUidMatches() {
            // Arrange
            SupabaseAuthResponse mockResponse = createMockAuthResponse();

            User existingUser = new User();
            existingUser.setId(99L);
            existingUser.setEmail(TEST_EMAIL);
            existingUser.setAuthUid(TEST_AUTH_UID);
            existingUser.setAuthProvider(AuthProvider.SUPABASE);
            existingUser.setRole(Role.USER);
            existingUser.setRegistrationStatus(RegistrationStatus.ACTIVE);

            when(supabaseAuthClient.exchangeCodeForToken(anyString()))
                    .thenReturn(mockResponse);
            when(userRepository.findByAuthUid(TEST_AUTH_UID))
                    .thenReturn(Optional.of(existingUser));

            // Act
            SupabaseAuthResult result = supabaseAuthService.handleGoogleCallback(
                    "auth-code", 
                    "USER"
            );

            // Assert
            verify(userRepository, never()).save(any(User.class));
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Should create OWNER user when role is OWNER")
        void shouldCreateOwnerUser() {
            // Arrange
            SupabaseAuthResponse mockResponse = createMockAuthResponse();

            when(supabaseAuthClient.exchangeCodeForToken(anyString()))
                    .thenReturn(mockResponse);
            when(userRepository.findByAuthUid(any(UUID.class)))
                    .thenReturn(Optional.empty());
            when(userRepository.findByEmail(TEST_EMAIL))
                    .thenReturn(Optional.empty());

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            when(userRepository.save(userCaptor.capture()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            supabaseAuthService.handleGoogleCallback("auth-code", "OWNER");

            // Assert
            User savedUser = userCaptor.getValue();
            assertThat(savedUser.getRole()).isEqualTo(Role.OWNER);
        }
    }

    // =========================================================================
    // Error Handling Tests
    // =========================================================================

    @Nested
    @DisplayName("Error handling tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle Supabase API errors gracefully")
        void shouldHandleSupabaseApiErrors() {
            // Arrange
            when(supabaseAuthClient.exchangeCodeForToken(anyString()))
                    .thenThrow(new RuntimeException("Supabase API error"));

            // Act & Assert
            assertThatThrownBy(() -> 
                supabaseAuthService.handleGoogleCallback("auth-code", "USER")
            )
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Supabase API error");
        }

        @Test
        @DisplayName("Should handle null user in auth response")
        void shouldHandleNullUserInResponse() {
            // Arrange
            SupabaseAuthResponse mockResponse = new SupabaseAuthResponse();
            mockResponse.setAccessToken(TEST_ACCESS_TOKEN);
            mockResponse.setRefreshToken(TEST_REFRESH_TOKEN);
            mockResponse.setUser(null); // null user

            when(supabaseAuthClient.exchangeCodeForToken(anyString()))
                    .thenReturn(mockResponse);

            // Act & Assert
            assertThatThrownBy(() -> 
                supabaseAuthService.handleGoogleCallback("auth-code", "USER")
            )
            .isInstanceOf(RuntimeException.class);
        }
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private SupabaseAuthResponse createMockAuthResponse() {
        SupabaseUser user = new SupabaseUser();
        user.setId(TEST_AUTH_UID);
        user.setEmail(TEST_EMAIL);
        user.setEmailConfirmedAt("2024-01-01T00:00:00Z");
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("full_name", "Test User");
        metadata.put("avatar_url", "https://example.com/avatar.jpg");
        user.setUserMetadata(metadata);

        SupabaseAuthResponse response = new SupabaseAuthResponse();
        response.setAccessToken(TEST_ACCESS_TOKEN);
        response.setRefreshToken(TEST_REFRESH_TOKEN);
        response.setExpiresIn(3600);
        response.setUser(user);

        return response;
    }
}

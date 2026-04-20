package org.example.rentoza.auth;

import org.example.rentoza.security.supabase.SupabaseAuthClient;
import org.example.rentoza.security.supabase.SupabaseAuthClient.SupabaseAuthException;
import org.example.rentoza.security.supabase.SupabaseAuthClient.SupabaseAuthResponse;
import org.example.rentoza.security.supabase.SupabaseAuthClient.SupabaseUser;
import org.example.rentoza.security.supabase.SupabaseAuthService;
import org.example.rentoza.security.supabase.SupabaseJwtUtil;
import org.example.rentoza.security.supabase.SupabaseUserMapping;
import org.example.rentoza.security.supabase.SupabaseUserMappingRepository;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests verifying that email-based auto-linking has been removed from login().
 * 
 * <p>Phase 02-01: The login() method must NOT fall back to userRepository.findByEmail()
 * when no Supabase-to-Rentoza mapping exists. This prevents account takeover via
 * email-based identity assumption.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SupabaseAuthService Login Security — Auto-Linking Removal")
class SupabaseAuthServiceLoginSecurityTest {

    @Mock
    private SupabaseAuthClient supabaseClient;

    @Mock
    private SupabaseUserMappingRepository mappingRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SupabaseJwtUtil supabaseJwtUtil;

    @InjectMocks
    private SupabaseAuthService authService;

    private static final String TEST_EMAIL = "petar@example.com";
    private static final String TEST_PASSWORD = "Aa123456!";
    private static final UUID SUPABASE_ID = UUID.randomUUID();

    private SupabaseAuthResponse buildAuthResponse() {
        SupabaseUser user = new SupabaseUser();
        user.setId(SUPABASE_ID);
        user.setEmail(TEST_EMAIL);

        SupabaseAuthResponse response = new SupabaseAuthResponse();
        response.setAccessToken("access.jwt.token");
        response.setRefreshToken("refresh.jwt.token");
        response.setExpiresIn(3600);
        response.setUser(user);
        return response;
    }

    @Test
    @DisplayName("login throws when mapping is missing — findByEmail NEVER called (auto-linking removed)")
    void testLoginThrowsWhenMappingMissing() {
        // Arrange: Supabase auth succeeds but no mapping exists
        when(supabaseClient.signInWithPassword(TEST_EMAIL, TEST_PASSWORD))
                .thenReturn(buildAuthResponse());
        when(mappingRepository.findById(SUPABASE_ID))
                .thenReturn(Optional.empty());

        // Act & Assert: should throw, not auto-link
        assertThatThrownBy(() -> authService.login(TEST_EMAIL, TEST_PASSWORD))
                .isInstanceOf(SupabaseAuthException.class)
                .hasMessageContaining("User account not found");

        // CRITICAL: verify findByEmail was NEVER called — auto-linking is dead
        verify(userRepository, never()).findByEmail(anyString());
        // Also verify no mapping was ever created
        verify(mappingRepository, never()).save(any(SupabaseUserMapping.class));
    }

    @Test
    @DisplayName("login succeeds with existing mapping — happy path")
    void testLoginSucceedsWithExistingMapping() {
        // Arrange: Supabase auth and mapping both exist
        SupabaseAuthResponse authResponse = buildAuthResponse();
        when(supabaseClient.signInWithPassword(TEST_EMAIL, TEST_PASSWORD))
                .thenReturn(authResponse);

        SupabaseUserMapping mapping = SupabaseUserMapping.builder()
                .supabaseId(SUPABASE_ID)
                .rentozaUserId(42L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(mappingRepository.findById(SUPABASE_ID))
                .thenReturn(Optional.of(mapping));

        User rentozaUser = new User();
        rentozaUser.setId(42L);
        rentozaUser.setEmail(TEST_EMAIL);
        rentozaUser.setRole(Role.USER);
        rentozaUser.setFirstName("Petar");
        rentozaUser.setLastName("Petrovic");
        when(userRepository.findById(42L))
                .thenReturn(Optional.of(rentozaUser));

        // Act
        var result = authService.login(TEST_EMAIL, TEST_PASSWORD);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getUser().getId()).isEqualTo(42L);
        assertThat(result.getAccessToken()).isEqualTo("access.jwt.token");

        // findByEmail should still not be called even on success
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    @DisplayName("login normalizes email before authenticating")
    void testLoginNormalizesEmail() {
        // Arrange
        when(supabaseClient.signInWithPassword(TEST_EMAIL, TEST_PASSWORD))
                .thenReturn(buildAuthResponse());
        when(mappingRepository.findById(SUPABASE_ID))
                .thenReturn(Optional.empty());

        // Act: login with mixed-case email with spaces
        assertThatThrownBy(() -> authService.login("  Petar@Example.COM  ", TEST_PASSWORD))
                .isInstanceOf(SupabaseAuthException.class);

        // Verify the normalized email was used for Supabase auth
        verify(supabaseClient).signInWithPassword(eq("petar@example.com"), eq(TEST_PASSWORD));
    }
}

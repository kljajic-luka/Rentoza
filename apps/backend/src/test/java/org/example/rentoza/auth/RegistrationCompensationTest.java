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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SupabaseAuthService registration atomicity (compensation),
 * email normalization, and case-insensitive duplicate detection.
 */
@ExtendWith(MockitoExtension.class)
class RegistrationCompensationTest {

    @Mock
    private SupabaseAuthClient supabaseClient;

    @Mock
    private SupabaseUserMappingRepository mappingRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SupabaseJwtUtil supabaseJwtUtil;

    @InjectMocks
    private SupabaseAuthService supabaseAuthService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(supabaseAuthService, "supabaseUrl", "https://test.supabase.co");
    }

    // =========================================================================
    // Compensation: Supabase user deleted on local DB failure
    // =========================================================================

    @Test
    @DisplayName("Compensation: local DB save failure triggers Supabase user deletion")
    void register_localDbFailure_deletesSupabaseUser() {
        UUID supabaseId = UUID.randomUUID();
        SupabaseAuthResponse supabaseResponse = createAuthResponse(supabaseId, "comp@example.com");

        when(userRepository.findByEmail("comp@example.com")).thenReturn(Optional.empty());
        when(supabaseClient.signUp(eq("comp@example.com"), anyString(), anyMap()))
                .thenReturn(supabaseResponse);
        when(userRepository.save(any(User.class)))
                .thenThrow(new RuntimeException("DB connection lost"));

        assertThatThrownBy(() ->
                supabaseAuthService.register("comp@example.com", "StrongP@ss1", "Test", "User", Role.USER)
        ).isInstanceOf(SupabaseAuthException.class)
                .hasMessageContaining("Registration failed");

        verify(supabaseClient).deleteUser(supabaseId);
    }

    // =========================================================================
    // Email Normalization
    // =========================================================================

    @Test
    @DisplayName("Email normalized to lowercase on register")
    void register_emailNormalized_toLowercase() {
        UUID supabaseId = UUID.randomUUID();
        SupabaseAuthResponse supabaseResponse = createAuthResponse(supabaseId, "user@email.com");
        User savedUser = createUser(1L, "user@email.com");

        when(userRepository.findByEmail("user@email.com")).thenReturn(Optional.empty());
        when(supabaseClient.signUp(eq("user@email.com"), anyString(), anyMap()))
                .thenReturn(supabaseResponse);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        supabaseAuthService.register("  User@Email.COM  ", "StrongP@ss1", "Test", "User", Role.USER);

        // Verify email lookup used normalized form
        verify(userRepository).findByEmail("user@email.com");
        // Verify Supabase signup used normalized form
        verify(supabaseClient).signUp(eq("user@email.com"), anyString(), anyMap());
        // Verify saved user has normalized email
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("user@email.com");
    }

    @Test
    @DisplayName("Case-insensitive duplicate email rejected")
    void register_caseInsensitiveDuplicate_rejected() {
        when(userRepository.findByEmail("user@email.com"))
                .thenReturn(Optional.of(createUser(99L, "user@email.com")));

        assertThatThrownBy(() ->
                supabaseAuthService.register("USER@email.com", "StrongP@ss1", "Test", "User", Role.USER)
        ).isInstanceOf(SupabaseAuthException.class)
                .hasMessageContaining("already registered");

        verify(supabaseClient, never()).signUp(anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Login normalizes email before Supabase call")
    void login_emailNormalized_toLowercase() {
        UUID supabaseId = UUID.randomUUID();
        SupabaseAuthResponse supabaseResponse = createAuthResponse(supabaseId, "login@example.com");
        User user = createUser(1L, "login@example.com");

        when(supabaseClient.signInWithPassword(eq("login@example.com"), eq("StrongP@ss1")))
                .thenReturn(supabaseResponse);
        when(mappingRepository.findById(supabaseId))
                .thenReturn(Optional.of(SupabaseUserMapping.create(supabaseId, 1L)));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        supabaseAuthService.login("  Login@Example.COM  ", "StrongP@ss1");

        verify(supabaseClient).signInWithPassword("login@example.com", "StrongP@ss1");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private SupabaseAuthResponse createAuthResponse(UUID supabaseId, String email) {
        SupabaseUser supabaseUser = new SupabaseUser();
        supabaseUser.setId(supabaseId);
        supabaseUser.setEmail(email);

        SupabaseAuthResponse response = new SupabaseAuthResponse();
        response.setUser(supabaseUser);
        response.setAccessToken("test-access-token");
        response.setRefreshToken("test-refresh-token");
        response.setExpiresIn(3600);
        return response;
    }

    private User createUser(Long id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setFirstName("Test");
        user.setLastName("User");
        user.setPassword("SUPABASE_AUTH");
        user.setRole(Role.USER);
        user.setAuthUid(UUID.randomUUID());
        return user;
    }
}

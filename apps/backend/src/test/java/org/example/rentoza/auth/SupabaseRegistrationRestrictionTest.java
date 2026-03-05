package org.example.rentoza.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.rentoza.config.AppProperties;
import org.example.rentoza.monitoring.MissingResourceMetrics;
import org.example.rentoza.security.csrf.CustomCookieCsrfTokenRepository;
import org.example.rentoza.security.password.PasswordPolicyService;
import org.example.rentoza.security.password.PasswordResetService;
import org.example.rentoza.security.supabase.SupabaseAuthService;
import org.example.rentoza.security.supabase.SupabaseAuthService.SupabaseAuthResult;
import org.example.rentoza.security.supabase.SupabaseJwtUtil;
import org.example.rentoza.security.token.TokenDenylistService;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.example.rentoza.user.UserService;
import org.example.rentoza.user.dto.UserResponseDTO;
import org.junit.jupiter.api.DisplayName;
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

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests that Supabase /register rejects OWNER role and forces others to USER,
 * and that the legacy /api/auth/register returns 410 GONE.
 */
@WebMvcTest(controllers = {SupabaseAuthController.class})
@AutoConfigureMockMvc(addFilters = false)
@Import(SupabaseRegistrationRestrictionTest.TestConfig.class)
@TestPropertySource(properties = {
        "app.cookie.domain=localhost",
        "app.cookie.secure=false",
        "app.cookie.sameSite=Lax",
        "jwt.expiration=3600000"
})
class SupabaseRegistrationRestrictionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // --- SupabaseAuthController dependencies ---
    @MockBean
    private SupabaseAuthService supabaseAuthService;

    @MockBean
    private UserService userService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private PasswordResetService passwordResetService;

    @MockBean
    private PasswordPolicyService passwordPolicyService;

    @MockBean
    private TokenDenylistService tokenDenylistService;

    @MockBean
    private SupabaseJwtUtil supabaseJwtUtil;

    // --- @ControllerAdvice dependency ---
    @MockBean
    private MissingResourceMetrics missingResourceMetrics;

    // =========================================================================
    // Role Restriction Tests on /api/auth/supabase/register
    // =========================================================================

    @Test
    @DisplayName("POST /supabase/register with role=OWNER → 400 INVALID_ROLE")
    void register_ownerRole_returns400InvalidRole() throws Exception {
        when(passwordPolicyService.validatePasswordStrength(anyString()))
                .thenReturn(java.util.List.of());

        mockMvc.perform(post("/api/auth/supabase/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "owner@example.com",
                                  "password": "StrongP@ss1",
                                  "firstName": "Vlasnik",
                                  "lastName": "Test",
                                  "role": "OWNER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_ROLE"));
    }

    @Test
    @DisplayName("POST /supabase/register with role=ADMIN → forced to USER, 200")
    void register_adminRole_forcedToUser_returns200() throws Exception {
        User user = createUser(1L, "admin@example.com", Role.USER);
        UserResponseDTO userResponse = mock(UserResponseDTO.class);

        SupabaseAuthResult result = SupabaseAuthResult.builder()
                .user(user)
                .emailConfirmationPending(true)
                .build();

        when(passwordPolicyService.validatePasswordStrength(anyString()))
                .thenReturn(java.util.List.of());
        when(supabaseAuthService.register(
                eq("admin@example.com"), anyString(), anyString(), anyString(), eq(Role.USER)
        )).thenReturn(result);
        when(userService.toUserResponse(any(User.class))).thenReturn(userResponse);

        mockMvc.perform(post("/api/auth/supabase/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "admin@example.com",
                                  "password": "StrongP@ss1",
                                  "firstName": "Admin",
                                  "lastName": "Test",
                                  "role": "ADMIN"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailConfirmationPending").value(true));

        verify(supabaseAuthService).register(
                eq("admin@example.com"), anyString(), anyString(), anyString(), eq(Role.USER));
    }

    @Test
    @DisplayName("POST /supabase/register with role=USER → 200")
    void register_userRole_returns200() throws Exception {
        User user = createUser(2L, "user@example.com", Role.USER);
        UserResponseDTO userResponse = mock(UserResponseDTO.class);

        SupabaseAuthResult result = SupabaseAuthResult.builder()
                .user(user)
                .emailConfirmationPending(true)
                .build();

        when(passwordPolicyService.validatePasswordStrength(anyString()))
                .thenReturn(java.util.List.of());
        when(supabaseAuthService.register(
                eq("user@example.com"), anyString(), anyString(), anyString(), eq(Role.USER)
        )).thenReturn(result);
        when(userService.toUserResponse(any(User.class))).thenReturn(userResponse);

        mockMvc.perform(post("/api/auth/supabase/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "user@example.com",
                                  "password": "StrongP@ss1",
                                  "firstName": "User",
                                  "lastName": "Test",
                                  "role": "USER"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailConfirmationPending").value(true));
    }

    @Test
    @DisplayName("POST /supabase/register with no role → defaults to USER, 200")
    void register_noRole_defaultsToUser_returns200() throws Exception {
        User user = createUser(3L, "norole@example.com", Role.USER);
        UserResponseDTO userResponse = mock(UserResponseDTO.class);

        SupabaseAuthResult result = SupabaseAuthResult.builder()
                .user(user)
                .emailConfirmationPending(true)
                .build();

        when(passwordPolicyService.validatePasswordStrength(anyString()))
                .thenReturn(java.util.List.of());
        when(supabaseAuthService.register(
                eq("norole@example.com"), anyString(), anyString(), anyString(), eq(Role.USER)
        )).thenReturn(result);
        when(userService.toUserResponse(any(User.class))).thenReturn(userResponse);

        mockMvc.perform(post("/api/auth/supabase/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "norole@example.com",
                                  "password": "StrongP@ss1",
                                  "firstName": "NoRole",
                                  "lastName": "Test"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailConfirmationPending").value(true));
    }

    // =========================================================================
    // Legacy Endpoint Deprecation
    // =========================================================================

    @Test
    @DisplayName("POST /api/auth/register → 404 (endpoint removed, no longer exists)")
    void legacyRegister_returns404NotFound() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private User createUser(Long id, String email, Role role) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setFirstName("Test");
        user.setLastName("User");
        user.setPassword("SUPABASE_AUTH");
        user.setRole(role);
        user.setAuthUid(UUID.randomUUID());
        return user;
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

package org.example.rentoza.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.rentoza.config.AppProperties;
import org.example.rentoza.monitoring.MissingResourceMetrics;
import org.example.rentoza.security.csrf.CustomCookieCsrfTokenRepository;
import org.example.rentoza.security.password.PasswordPolicyService;
import org.example.rentoza.security.password.PasswordResetService;
import org.example.rentoza.security.supabase.SupabaseAuthClient.SupabaseAuthException;
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
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SupabaseAuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(SupabaseAuthControllerGateTest.TestConfig.class)
@TestPropertySource(properties = {
        "app.cookie.domain=localhost",
        "app.cookie.secure=false",
        "app.cookie.sameSite=Lax"
})
class SupabaseAuthControllerGateTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    @MockBean
    private MissingResourceMetrics missingResourceMetrics;

    @Test
    @DisplayName("Register duplicate and pending-confirmation responses are identical in status and payload")
    void registerDuplicate_hasSameShapeAsPendingConfirmation() throws Exception {
        User user = createUser(101L, "newuser@example.com");
        UserResponseDTO userResponse = mock(UserResponseDTO.class);

        SupabaseAuthResult pendingResult = SupabaseAuthResult.builder()
                .user(user)
                .emailConfirmationPending(true)
                .build();

        when(passwordPolicyService.validatePasswordStrength(anyString())).thenReturn(java.util.List.of());
        when(supabaseAuthService.register(anyString(), anyString(), anyString(), anyString(), any(Role.class)))
                .thenReturn(pendingResult)
                .thenThrow(new SupabaseAuthException("Email already registered"));
        when(userService.toUserResponse(any(User.class))).thenReturn(userResponse);

        String payload = """
                {
                  "email": "newuser@example.com",
                  "password": "Aa123456!",
                  "firstName": "Petar",
                  "lastName": "Petrovic",
                  "role": "USER"
                }
                """;

        MvcResult first = mockMvc.perform(post("/api/auth/supabase/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/auth/supabase/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode secondJson = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(firstJson).isEqualTo(secondJson);
        assertThat(firstJson.has("user")).isFalse();
        assertThat(firstJson.path("success").asBoolean()).isTrue();
        assertThat(firstJson.path("emailConfirmationPending").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Register rejects XSS payload in first/last name")
    void registerRejectsXssNamePayload() throws Exception {
        when(passwordPolicyService.validatePasswordStrength(anyString())).thenReturn(java.util.List.of());

        mockMvc.perform(post("/api/auth/supabase/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "safe@example.com",
                                  "password": "Aa123456!",
                                  "firstName": "<script>alert(1)</script>",
                                  "lastName": "Petrovic",
                                  "role": "USER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_INPUT"));

        verifyNoInteractions(supabaseAuthService);
    }

    @Test
    @DisplayName("Login success returns 200")
    void loginSuccess_returns200() throws Exception {
        User user = createUser(201L, "login@example.com");
        UserResponseDTO userResponse = mock(UserResponseDTO.class);

        when(userRepository.findByEmail("login@example.com")).thenReturn(Optional.of(user));
        when(supabaseAuthService.login(eq("login@example.com"), eq("Aa123456!")))
                .thenReturn(SupabaseAuthResult.builder()
                        .accessToken("access.jwt")
                        .refreshToken("refresh.jwt")
                        .user(user)
                        .build());
        when(userService.toUserResponse(any(User.class))).thenReturn(userResponse);

        mockMvc.perform(post("/api/auth/supabase/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"login@example.com","password":"Aa123456!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true));
    }

    @Test
    @DisplayName("Login failure returns 401 and increments failed attempts")
    void loginFailure_incrementsFailedAttempts() throws Exception {
        User user = createUser(202L, "fail@example.com");
        when(userRepository.findByEmail("fail@example.com")).thenReturn(Optional.of(user));
        when(supabaseAuthService.login(eq("fail@example.com"), eq("badpass")))
                .thenThrow(new SupabaseAuthException("Invalid credentials"));

        mockMvc.perform(post("/api/auth/supabase/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"fail@example.com","password":"badpass"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));

        verify(userRepository, atLeastOnce()).save(argThat(saved ->
                saved.getEmail().equals("fail@example.com") && saved.getFailedLoginAttempts() >= 1));
    }

    @Test
    @DisplayName("Login on locked account returns 429")
    void loginLockedAccount_returns429() throws Exception {
        User user = createUser(203L, "locked@example.com");
        user.setLockedUntil(Instant.now().plusSeconds(600));
        when(userRepository.findByEmail("locked@example.com")).thenReturn(Optional.of(user));

        mockMvc.perform(post("/api/auth/supabase/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"locked@example.com","password":"Aa123456!"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("ACCOUNT_LOCKED"));

        verifyNoInteractions(supabaseAuthService);
    }

    @Test
    @DisplayName("Forgot password returns generic success even on internal exception")
    void forgotPassword_alwaysGenericSuccess() throws Exception {
        doThrow(new RuntimeException("SMTP down"))
                .when(passwordResetService).requestPasswordReset(anyString(), anyString());

        mockMvc.perform(post("/api/auth/supabase/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"unknown@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("If an account exists with this email, a password reset link has been sent."));
    }

    @Test
    @DisplayName("Reset password success returns 200")
    void resetPassword_success() throws Exception {
        when(passwordResetService.resetPassword(eq("token123"), eq("Aa123456!"))).thenReturn(true);

        mockMvc.perform(post("/api/auth/supabase/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"token123","newPassword":"Aa123456!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("Logout denylist+revoke works with cookie token")
    void logout_cookieToken_denylistsAndRevokes() throws Exception {
        when(supabaseJwtUtil.getExpirationDateFromToken("cookie.jwt"))
                .thenReturn(new Date(System.currentTimeMillis() + 3600_000));

        mockMvc.perform(post("/api/auth/supabase/logout")
                        .cookie(new jakarta.servlet.http.Cookie("access_token", "cookie.jwt")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("logged_out"));

        verify(tokenDenylistService).denyToken(eq("cookie.jwt"), any(Instant.class), eq("anonymous"));
        verify(supabaseAuthService).logout("cookie.jwt");
    }

    @Test
    @DisplayName("Logout denylist+revoke works with Authorization bearer fallback")
    void logout_bearerToken_denylistsAndRevokes() throws Exception {
        when(supabaseJwtUtil.getExpirationDateFromToken("bearer.jwt"))
                .thenReturn(new Date(System.currentTimeMillis() + 3600_000));

        mockMvc.perform(post("/api/auth/supabase/logout")
                        .header("Authorization", "Bearer bearer.jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("logged_out"));

        verify(tokenDenylistService).denyToken(eq("bearer.jwt"), any(Instant.class), eq("anonymous"));
        verify(supabaseAuthService).logout("bearer.jwt");
    }

    private User createUser(Long id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setFirstName("Petar");
        user.setLastName("Petrovic");
        user.setPassword("SUPABASE_AUTH");
        user.setRole(Role.USER);
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

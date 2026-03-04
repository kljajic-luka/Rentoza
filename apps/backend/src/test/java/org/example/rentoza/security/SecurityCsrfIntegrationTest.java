package org.example.rentoza.security;

import org.example.rentoza.config.AppProperties;
import org.example.rentoza.deprecated.jwt.JwtAuthenticationEntryPoint;
import org.example.rentoza.deprecated.jwt.JwtUtil;
import org.example.rentoza.monitoring.MissingResourceMetrics;
import org.example.rentoza.security.ratelimit.RateLimitService;
import org.example.rentoza.security.supabase.SupabaseJwtUtil;
import org.example.rentoza.security.supabase.SupabaseUserMappingRepository;
import org.example.rentoza.security.token.TokenDenylistService;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CSRF integration tests against the real SecurityConfig filter chain.
 *
 * Tests the actual CSRF filter behavior on real endpoint paths from SecurityConfig:
 * 1. Non-exempt auth endpoints (login, register) → CSRF enforced (403 without token)
 * 2. Exempt endpoints (logout, forgot-password, webhooks) → CSRF skipped (not 403)
 * 3. General endpoints → CSRF enforced via a stub controller
 *
 * For paths without a stub controller, the test asserts CSRF filter behavior:
 * - 403 = CSRF filter rejected the request (token missing on non-exempt path)
 * - NOT 403 = CSRF filter passed (exempt path or valid token) — downstream 404/405 is OK
 */
@WebMvcTest(controllers = SecurityCsrfIntegrationTest.CsrfProbeController.class)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, AppProperties.class})
@TestPropertySource(properties = {
        "app.cookie.domain=localhost",
        "app.cookie.secure=false",
        "app.cookie.same-site=Lax"
})
class SecurityCsrfIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // SecurityConfig constructor dependencies
    @MockBean private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    // @Bean method parameter dependencies
    @MockBean private RateLimitService rateLimitService;
    @MockBean private JwtUtil jwtUtil;
    @MockBean private InternalServiceJwtUtil internalServiceJwtUtil;
    @MockBean private SupabaseJwtUtil supabaseJwtUtil;
    @MockBean private UserRepository userRepository;
    @MockBean private SupabaseUserMappingRepository supabaseUserMappingRepository;
    @MockBean private TokenDenylistService tokenDenylistService;
    @MockBean private MissingResourceMetrics missingResourceMetrics;

    @BeforeEach
    void setUp() {
        // Allow all requests through the rate limiter (we're testing CSRF, not rate limits)
        when(rateLimitService.allowRequest(anyString(), anyInt(), anyInt())).thenReturn(true);
        when(rateLimitService.allowRequest(anyString(), anyInt(), anyInt(), any())).thenReturn(true);
    }

    // =========================================================================
    // Real auth endpoints — NOT in CSRF exemption list → 403 without CSRF token
    // =========================================================================

    @Nested
    @DisplayName("Auth endpoints requiring CSRF")
    class AuthEndpointsRequiringCsrf {

        @Test
        @DisplayName("/api/auth/supabase/login POST without CSRF → 403")
        void supabaseLogin_withoutCsrf_isForbidden() throws Exception {
            mockMvc.perform(post("/api/auth/supabase/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@b.com\",\"password\":\"x\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("/api/auth/supabase/login POST with CSRF → passes CSRF filter (not 403)")
        void supabaseLogin_withCsrf_passesFilter() throws Exception {
            MvcResult result = mockMvc.perform(post("/api/auth/supabase/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@b.com\",\"password\":\"x\"}")
                            .with(csrf()))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).isNotEqualTo(403);
        }

        @Test
        @DisplayName("/api/auth/supabase/register POST without CSRF → 403")
        void supabaseRegister_withoutCsrf_isForbidden() throws Exception {
            mockMvc.perform(post("/api/auth/supabase/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@b.com\",\"password\":\"x\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("/api/auth/supabase/register POST with CSRF → passes CSRF filter")
        void supabaseRegister_withCsrf_passesFilter() throws Exception {
            MvcResult result = mockMvc.perform(post("/api/auth/supabase/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@b.com\",\"password\":\"x\"}")
                            .with(csrf()))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).isNotEqualTo(403);
        }
    }

    // =========================================================================
    // Exempted endpoints (SecurityConfig.ignoringRequestMatchers) → NOT 403
    // =========================================================================

    @Nested
    @DisplayName("CSRF-exempt endpoints")
    class CsrfExemptEndpoints {

        @Test
        @DisplayName("/api/auth/logout is CSRF-exempt → not 403 without token")
        void logout_withoutCsrf_notForbidden() throws Exception {
            MvcResult result = mockMvc.perform(post("/api/auth/logout")
                            .with(user("alice").roles("USER")))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).isNotEqualTo(403);
        }

        @Test
        @DisplayName("/api/auth/supabase/forgot-password is CSRF-exempt")
        void forgotPassword_withoutCsrf_notForbidden() throws Exception {
            MvcResult result = mockMvc.perform(post("/api/auth/supabase/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@b.com\"}"))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).isNotEqualTo(403);
        }

        @Test
        @DisplayName("/api/auth/supabase/reset-password is CSRF-exempt")
        void resetPassword_withoutCsrf_notForbidden() throws Exception {
            MvcResult result = mockMvc.perform(post("/api/auth/supabase/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"t\",\"password\":\"p\"}"))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).isNotEqualTo(403);
        }

        @Test
        @DisplayName("/api/webhooks/payment is CSRF-exempt")
        void paymentWebhook_withoutCsrf_notForbidden() throws Exception {
            MvcResult result = mockMvc.perform(post("/api/webhooks/payment")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).isNotEqualTo(403);
        }
    }

    // =========================================================================
    // Stub controller — verifies full round-trip on non-conflicting path
    // =========================================================================

    @Nested
    @DisplayName("General CSRF behavior (stub controller)")
    class GeneralCsrfBehavior {

        @Test
        @DisplayName("GET does not require CSRF")
        void getRequest_noCsrf_isOk() throws Exception {
            mockMvc.perform(get("/api/csrf-test/ping")
                            .with(user("alice").roles("USER")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("POST without CSRF → 403")
        void post_withoutCsrf_isForbidden() throws Exception {
            mockMvc.perform(post("/api/csrf-test/mutate")
                            .with(user("alice").roles("USER")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST with CSRF → 200")
        void post_withCsrf_isOk() throws Exception {
            mockMvc.perform(post("/api/csrf-test/mutate")
                            .with(user("alice").roles("USER"))
                            .with(csrf()))
                    .andExpect(status().isOk());
        }
    }

    // =========================================================================
    // Stub controller on a unique path (no conflict with real controllers)
    // =========================================================================

    @RestController
    @RequestMapping("/api/csrf-test")
    static class CsrfProbeController {

        @GetMapping("/ping")
        public String ping() { return "ok"; }

        @PostMapping("/mutate")
        public String mutate() { return "ok"; }
    }
}

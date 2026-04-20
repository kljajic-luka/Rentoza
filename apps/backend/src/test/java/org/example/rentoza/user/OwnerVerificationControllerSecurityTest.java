package org.example.rentoza.user;

import org.example.rentoza.monitoring.MissingResourceMetrics;
import org.example.rentoza.security.CurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security tests for OwnerVerificationController role-gating.
 * 
 * <p>Phase 02-01: Verifies that submit endpoints require ROLE_OWNER,
 * while status endpoint remains accessible to any authenticated user.
 */
@WebMvcTest(controllers = OwnerVerificationController.class)
@Import(OwnerVerificationControllerSecurityTest.SecurityTestConfig.class)
@DisplayName("OwnerVerificationController Security — Role Gating")
class OwnerVerificationControllerSecurityTest {

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SecurityTestConfig {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults())
                    .build();
        }
    }

    @jakarta.annotation.Resource
    private MockMvc mockMvc;

    @MockBean
    private OwnerVerificationService verificationService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private CurrentUser currentUser;

    @MockBean
    private MissingResourceMetrics missingResourceMetrics;

    private static final String INDIVIDUAL_PAYLOAD = """
            {
              "jmbg": "0101990710008",
              "bankAccountNumber": "160-1234567890123-45"
            }
            """;

    private static final String LEGAL_ENTITY_PAYLOAD = """
            {
              "pib": "101134702",
              "bankAccountNumber": "160-1234567890123-45"
            }
            """;

    // ==================== INDIVIDUAL VERIFICATION ====================

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("POST /individual with ROLE_USER → 403 Forbidden")
    void submitIndividual_asUser_returns403() throws Exception {
        mockMvc.perform(post("/api/users/me/owner-verification/individual")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INDIVIDUAL_PAYLOAD))
                .andExpect(status().isForbidden());

        verifyNoInteractions(verificationService);
    }

    @Test
    @WithMockUser(roles = "OWNER")
    @DisplayName("POST /individual with ROLE_OWNER → not 403 (passes auth gate)")
    void submitIndividual_asOwner_passesAuthGate() throws Exception {
        when(currentUser.id()).thenReturn(1L);

        User owner = new User();
        owner.setId(1L);
        owner.setRole(Role.OWNER);
        owner.setOwnerType(OwnerType.INDIVIDUAL);
        owner.setIsIdentityVerified(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));

        mockMvc.perform(post("/api/users/me/owner-verification/individual")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INDIVIDUAL_PAYLOAD))
                .andExpect(status().isOk());
    }

    // ==================== LEGAL ENTITY VERIFICATION ====================

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("POST /legal-entity with ROLE_USER → 403 Forbidden")
    void submitLegalEntity_asUser_returns403() throws Exception {
        mockMvc.perform(post("/api/users/me/owner-verification/legal-entity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LEGAL_ENTITY_PAYLOAD))
                .andExpect(status().isForbidden());

        verifyNoInteractions(verificationService);
    }

    @Test
    @WithMockUser(roles = "OWNER")
    @DisplayName("POST /legal-entity with ROLE_OWNER → not 403 (passes auth gate)")
    void submitLegalEntity_asOwner_passesAuthGate() throws Exception {
        when(currentUser.id()).thenReturn(2L);

        User owner = new User();
        owner.setId(2L);
        owner.setRole(Role.OWNER);
        owner.setOwnerType(OwnerType.LEGAL_ENTITY);
        owner.setIsIdentityVerified(false);
        when(userRepository.findById(2L)).thenReturn(Optional.of(owner));

        mockMvc.perform(post("/api/users/me/owner-verification/legal-entity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LEGAL_ENTITY_PAYLOAD))
                .andExpect(status().isOk());
    }

    // ==================== STATUS ENDPOINT ====================

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("GET /owner-verification with ROLE_USER → 200 (status accessible to all authenticated)")
    void getStatus_asUser_returns200() throws Exception {
        when(currentUser.id()).thenReturn(3L);

        User user = new User();
        user.setId(3L);
        user.setRole(Role.USER);
        user.setIsIdentityVerified(false);
        when(userRepository.findById(3L)).thenReturn(Optional.of(user));

        mockMvc.perform(get("/api/users/me/owner-verification"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /individual without authentication → 401 Unauthorized")
    void submitIndividual_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/users/me/owner-verification/individual")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INDIVIDUAL_PAYLOAD))
                .andExpect(status().isUnauthorized());
    }
}

package org.example.rentoza.car;

import org.example.rentoza.monitoring.MissingResourceMetrics;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CarDocumentController.class)
@Import(CarDocumentControllerSecurityTest.SecurityTestConfig.class)
@DisplayName("CarDocumentController Security")
class CarDocumentControllerSecurityTest {

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
    private CarDocumentService documentService;

    @MockBean
    private CurrentUser currentUser;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private MissingResourceMetrics missingResourceMetrics;

    @Test
    @WithMockUser(roles = "OWNER")
    @DisplayName("Owner can read their car document metadata")
    void ownerCanReadDocumentMetadata() throws Exception {
        User owner = userWithRole(10L, Role.OWNER);
        when(currentUser.id()).thenReturn(10L);
        when(userRepository.findById(10L)).thenReturn(Optional.of(owner));
        when(documentService.getDocumentsForCar(eq(55L), any(User.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/cars/55/documents"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Admin can read compliance status")
    void adminCanReadComplianceStatus() throws Exception {
        User admin = userWithRole(1L, Role.ADMIN);
        when(currentUser.id()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(documentService.getDocumentsForCar(eq(55L), any(User.class))).thenReturn(List.of());
        when(documentService.hasAllRequiredDocumentsVerified(eq(55L), any(User.class))).thenReturn(false);

        mockMvc.perform(get("/api/cars/55/documents/status"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("Unrelated authenticated user is forbidden from document metadata")
    void unrelatedUserCannotReadDocumentMetadata() throws Exception {
        User user = userWithRole(99L, Role.USER);
        when(currentUser.id()).thenReturn(99L);
        when(userRepository.findById(99L)).thenReturn(Optional.of(user));
        when(documentService.getDocumentsForCar(eq(55L), any(User.class)))
                .thenThrow(new AccessDeniedException("Unauthorized to access car document metadata"));

        mockMvc.perform(get("/api/cars/55/documents"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.error.path").value("/api/cars/55/documents"));
    }

    @Test
    @DisplayName("Unauthenticated request is rejected")
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/cars/55/documents"))
                .andExpect(status().isUnauthorized());
    }

    private User userWithRole(Long id, Role role) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setEmail(role.name().toLowerCase() + "@test.com");
        return user;
    }
}
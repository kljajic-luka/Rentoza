package org.example.rentoza.admin.controller;

import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.admin.service.AdminAuditService;
import org.example.rentoza.car.CarDocument;
import org.example.rentoza.car.CarDocumentService;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminDocumentController.class)
@Import(AdminDocumentControllerSecurityTest.SecurityTestConfig.class)
class AdminDocumentControllerSecurityTest {

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
    private AdminAuditService auditService;

    @MockBean
    private CurrentUser currentUser;

    @MockBean
    private UserRepository userRepository;

    @Test
    @WithMockUser(roles = "USER")
    void downloadDocument_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/documents/1/download"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(documentService);
        verifyNoInteractions(auditService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void downloadDocument_okForAdmin() throws Exception {
        User admin = new User();
        admin.setId(1L);

        CarDocument document = CarDocument.builder()
                .id(1L)
                .mimeType("image/jpeg")
                .originalFilename("id-front.jpg")
                .build();

        when(currentUser.id()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(documentService.getDocumentById(1L)).thenReturn(document);
        when(documentService.getDocumentContent(document)).thenReturn(new byte[] {1, 2, 3});

        mockMvc.perform(get("/api/admin/documents/1/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("inline")))
                .andExpect(content().bytes(new byte[] {1, 2, 3}));

        verify(auditService).logAction(
                same(admin),
                eq(AdminAction.DOCUMENT_VIEWED),
                eq(ResourceType.DOCUMENT),
                eq(1L),
                isNull(),
                isNull(),
                anyString()
        );
    }
}

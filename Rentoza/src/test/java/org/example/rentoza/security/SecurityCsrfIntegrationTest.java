//package org.example.rentoza.security;
//
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.boot.test.context.TestConfiguration;
//import org.springframework.test.context.DynamicPropertyRegistry;
//import org.springframework.test.context.DynamicPropertySource;
//import org.springframework.test.web.servlet.MockMvc;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.PostMapping;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RestController;
//
//import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
//import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
//import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
//import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
//
//@SpringBootTest
//@AutoConfigureMockMvc
//class SecurityCsrfIntegrationTest {
//
//    @Autowired
//    private MockMvc mockMvc;
//
//    @DynamicPropertySource
//    static void overrideDatasource(DynamicPropertyRegistry registry) {
//        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:csrf-test;DB_CLOSE_DELAY=-1;MODE=MySQL");
//        registry.add("spring.datasource.username", () -> "sa");
//        registry.add("spring.datasource.password", () -> "");
//        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.H2Dialect");
//        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
//    }
//
//    @Test
//    @DisplayName("GET requests remain accessible without CSRF header")
//    void getEndpointsDoNotRequireCsrf() throws Exception {
//        mockMvc.perform(get("/api/test/ping")
//                        .with(user("alice").roles("USER")))
//                .andExpect(status().isOk());
//    }
//
//    @Test
//    @DisplayName("State-changing POST is rejected without CSRF token")
//    void postWithoutCsrfFails() throws Exception {
//        mockMvc.perform(post("/api/test/state-change")
//                        .with(user("alice").roles("USER")))
//                .andExpect(status().isForbidden());
//    }
//
//    @Test
//    @DisplayName("State-changing POST succeeds when CSRF token is present")
//    void postWithCsrfSucceeds() throws Exception {
//        mockMvc.perform(post("/api/test/state-change")
//                        .with(user("alice").roles("USER"))
//                        .with(csrf()))
//                .andExpect(status().isOk());
//    }
//
//    @TestConfiguration
//    static class TestControllerConfig {
//
//        @RestController
//        @RequestMapping("/api/test")
//        static class CsrfProbeController {
//
//            @GetMapping("/ping")
//            public String ping() {
//                return "ok";
//            }
//
//            @PostMapping("/state-change")
//            public String mutate() {
//                return "mutated";
//            }
//        }
//    }
//}

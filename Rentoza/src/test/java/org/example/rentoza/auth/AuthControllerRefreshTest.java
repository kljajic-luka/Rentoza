package org.example.rentoza.auth;

import jakarta.servlet.http.Cookie;
import org.example.rentoza.config.AppProperties;
import org.example.rentoza.deprecated.jwt.JwtUtil;
import org.example.rentoza.deprecated.auth.RefreshTokenServiceEnhanced;
import org.example.rentoza.deprecated.auth.RefreshTokenResult;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserService;
import org.example.rentoza.user.dto.UserResponseDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.example.rentoza.security.csrf.CustomCookieCsrfTokenRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AuthControllerRefreshTest.TestAppPropertiesConfig.class)
class AuthControllerRefreshTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @MockBean
    private PasswordEncoder passwordEncoder;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private RefreshTokenServiceEnhanced refreshTokenService;

    @Test
    @DisplayName("/auth/refresh returns access token and hydrated user payload")
    void refreshReturnsAccessTokenAndUser() throws Exception {
        User user = new User();
        user.setId(7L);
        user.setEmail("cookie@example.com");
        user.setFirstName("Cookie");
        user.setLastName("Monster");
        user.setRole(Role.USER);

        when(refreshTokenService.rotate(eq("stored-refresh"), any(), any()))
                .thenReturn(new RefreshTokenResult(true, user.getEmail(), "rotated"));
        when(userService.getUserByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(userService.toUserResponse(user)).thenReturn(new UserResponseDTO(
            user.getId(),
            user.getFirstName(),
            user.getLastName(),
            user.getEmail(),
            null,
            null,
            user.getRole().name()
        ));
        when(jwtUtil.generateToken(eq(user.getEmail()), eq(user.getRole().name()), eq(user.getId())))
                .thenReturn("signed-access");

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("rentoza_refresh", "stored-refresh"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("signed-access"))
                .andExpect(jsonPath("$.user.email").value("cookie@example.com"))
                .andExpect(jsonPath("$.user.firstName").value("Cookie"))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andExpect(cookie().exists("rentoza_refresh"))
                .andExpect(cookie().exists("access_token"));
    }

    @TestConfiguration
    static class TestAppPropertiesConfig {
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

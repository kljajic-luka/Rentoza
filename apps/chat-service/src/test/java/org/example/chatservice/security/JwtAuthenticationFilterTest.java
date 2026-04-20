package org.example.chatservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String USER_TOKEN = "eyJhbGciOiJFUzI1NiJ9.payload.signature";
    private static final String SERVICE_TOKEN = "internal-service-token";

    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private InternalServiceJwtUtil internalServiceJwtUtil;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        filter = new JwtAuthenticationFilter(jwtTokenProvider, internalServiceJwtUtil);
    }

    @Test
    @DisplayName("User bearer token failures do not fall through to internal service validation")
    void userBearerTokenFailure_shouldNotInvokeInternalServiceValidation() throws Exception {
        when(request.getHeader("X-Internal-Service-Token")).thenReturn(null);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + USER_TOKEN);
        when(jwtTokenProvider.validateToken(USER_TOKEN)).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(internalServiceJwtUtil, never()).validateServiceToken(anyString());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Internal service header authenticates without inspecting user bearer token")
    void internalServiceHeader_shouldAuthenticateService() throws Exception {
        when(request.getHeader("X-Internal-Service-Token")).thenReturn(SERVICE_TOKEN);
        when(internalServiceJwtUtil.validateServiceToken(SERVICE_TOKEN)).thenReturn(true);
        when(internalServiceJwtUtil.getServiceNameFromToken(SERVICE_TOKEN)).thenReturn("backend");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo("backend");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_INTERNAL_SERVICE");
        verify(jwtTokenProvider, never()).validateToken(anyString());
        verify(filterChain).doFilter(request, response);
    }
}

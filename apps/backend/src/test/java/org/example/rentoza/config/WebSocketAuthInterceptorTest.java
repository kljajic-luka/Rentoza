package org.example.rentoza.config;

import org.example.rentoza.security.supabase.SupabaseJwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.server.ServletServerHttpResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@ExtendWith(MockitoExtension.class)
class WebSocketAuthInterceptorTest {

    @Mock
    private SupabaseJwtUtil supabaseJwtUtil;

    @Test
    @DisplayName("beforeHandshake stores authenticated principal from Supabase token")
    void beforeHandshakeStoresPrincipal() throws Exception {
        WebSocketAuthInterceptor interceptor = new WebSocketAuthInterceptor(supabaseJwtUtil);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader("Authorization", "Bearer token-123");
        Map<String, Object> attributes = new HashMap<>();
        UUID userId = UUID.randomUUID();

        when(supabaseJwtUtil.validateToken("token-123")).thenReturn(true);
        when(supabaseJwtUtil.getEmailFromToken("token-123")).thenReturn("user@example.com");
        when(supabaseJwtUtil.getSupabaseUserId("token-123")).thenReturn(userId);

        boolean allowed = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
            new ServletServerHttpResponse(new MockHttpServletResponse()),
                null,
                attributes);

        assertThat(allowed).isTrue();
        assertThat(attributes.get("userId")).isEqualTo(userId.toString());
        assertThat(attributes.get("PRINCIPAL")).isEqualTo(new StompPrincipal(userId.toString()));
    }

    @Test
    @DisplayName("beforeHandshake rejects invalid or expired Supabase token")
    void beforeHandshakeRejectsInvalidToken() throws Exception {
        WebSocketAuthInterceptor interceptor = new WebSocketAuthInterceptor(supabaseJwtUtil);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader("Authorization", "Bearer expired-token");
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        Map<String, Object> attributes = new HashMap<>();

        when(supabaseJwtUtil.validateToken("expired-token")).thenReturn(false);

        boolean allowed = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(servletResponse),
                null,
                attributes);

        assertThat(allowed).isFalse();
        assertThat(servletResponse.getStatus()).isEqualTo(UNAUTHORIZED.value());
        assertThat(attributes).doesNotContainKeys("userId", "PRINCIPAL", "authenticated");
    }
}
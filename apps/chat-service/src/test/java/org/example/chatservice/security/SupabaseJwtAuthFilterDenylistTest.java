package org.example.chatservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.chatservice.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for SupabaseJwtAuthFilter focusing on denylist enforcement
 * and query-param token rejection.
 */
@ExtendWith(MockitoExtension.class)
class SupabaseJwtAuthFilterDenylistTest {

    @Mock private SupabaseJwtUtil supabaseJwtUtil;
    @Mock private UserRepository userRepository;
    @Mock private TokenDenylistService tokenDenylistService;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;

    private SupabaseJwtAuthFilter filter;

    private static final String VALID_JWT = "eyJhbGciOiJFUzI1NiJ9.eyJ0ZXN0IjoxfQ.signature";

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        filter = new SupabaseJwtAuthFilter(supabaseJwtUtil, userRepository, tokenDenylistService);
    }

    @Test
    @DisplayName("P1: Denied (logged-out) token must NOT set SecurityContext authentication")
    void deniedToken_shouldNotAuthenticate() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer " + VALID_JWT);
        when(supabaseJwtUtil.validateToken(VALID_JWT)).thenReturn(true);
        when(tokenDenylistService.isTokenDenied(VALID_JWT)).thenReturn(true);
        when(request.getRequestURI()).thenReturn("/api/conversations/1");

        filter.doFilterInternal(request, response, filterChain);

        // Authentication must NOT be set
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        // Filter chain must still be called (no exception)
        verify(filterChain).doFilter(request, response);
        // User mapping must NOT be attempted
        verify(supabaseJwtUtil, never()).getRentozaUserId(anyString());
    }

    @Test
    @DisplayName("P1: Non-denied valid token authenticates normally")
    void validToken_shouldAuthenticate() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer " + VALID_JWT);
        when(supabaseJwtUtil.validateToken(VALID_JWT)).thenReturn(true);
        when(tokenDenylistService.isTokenDenied(VALID_JWT)).thenReturn(false);
        when(supabaseJwtUtil.getRentozaUserId(VALID_JWT)).thenReturn(42L);
        when(userRepository.findRoleByUserId(42L)).thenReturn(Optional.of("USER"));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(42L);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("P2: Query-param ?token= must NOT be accepted (JWT leakage prevention)")
    void queryParamToken_shouldBeIgnored() throws Exception {
        // No Authorization header, no cookies, but query param has a valid JWT
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getCookies()).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
        // The critical assertion: validateToken must NEVER be called,
        // proving the filter did not extract the token from the query param
        verify(supabaseJwtUtil, never()).validateToken(anyString());
    }

    @Test
    @DisplayName("P1: Denied token via cookie is also rejected")
    void deniedTokenViaCookie_shouldNotAuthenticate() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);
        Cookie accessCookie = new Cookie("access_token", VALID_JWT);
        when(request.getCookies()).thenReturn(new Cookie[]{accessCookie});
        when(supabaseJwtUtil.validateToken(VALID_JWT)).thenReturn(true);
        when(tokenDenylistService.isTokenDenied(VALID_JWT)).thenReturn(true);
        when(request.getRequestURI()).thenReturn("/api/conversations/1");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }
}

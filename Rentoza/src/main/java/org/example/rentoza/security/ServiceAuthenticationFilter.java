package org.example.rentoza.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Filter to authenticate internal service-to-service requests.
 * Checks for X-Internal-Service-Token header and validates it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ServiceAuthenticationFilter extends OncePerRequestFilter {

    private static final String INTERNAL_SERVICE_TOKEN_HEADER = "X-Internal-Service-Token";
    private static final String INTERNAL_SERVICE_AUTHORITY = "INTERNAL_SERVICE";
    private static final List<String> OAUTH2_ENDPOINT_PREFIXES = List.of(
            "/login/oauth2",
            "/oauth2",
            "/login"
    );

    private final InternalServiceJwtUtil internalServiceJwtUtil;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String requestUri = request.getRequestURI();
        String serviceToken = request.getHeader(INTERNAL_SERVICE_TOKEN_HEADER);

        // Skip if no internal service token header is present
        if (serviceToken == null || serviceToken.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // Validate the internal service token
            if (internalServiceJwtUtil.validateServiceToken(serviceToken)) {
                String serviceName = internalServiceJwtUtil.getServiceNameFromToken(serviceToken);
                
                log.info("✅ Internal service request authenticated: {} -> {}", serviceName, requestUri);

                // Create authentication with INTERNAL_SERVICE authority
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                serviceName,
                                null,
                                Collections.singletonList(new SimpleGrantedAuthority(INTERNAL_SERVICE_AUTHORITY))
                        );

                // Set authentication in security context
                SecurityContextHolder.getContext().setAuthentication(authentication);
                
                log.debug("Set INTERNAL_SERVICE authority for request from: {}", serviceName);
            } else {
                log.warn("❌ Invalid internal service token for request: {}", requestUri);
            }
        } catch (Exception e) {
            log.error("Error processing internal service token: {}", e.getMessage(), e);
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return OAUTH2_ENDPOINT_PREFIXES.stream().anyMatch(path::startsWith);
    }
}

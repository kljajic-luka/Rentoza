package org.example.chatservice.config;

import lombok.RequiredArgsConstructor;
import org.example.chatservice.security.JwtAuthenticationFilter;
import org.example.chatservice.security.SupabaseJwtAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Security Configuration for Dual Authentication
 * 
 * <h3>Authentication Strategy:</h3>
 * <ul>
 *   <li><strong>User Auth</strong>: Supabase ES256 JWT (SupabaseJwtAuthFilter)</li>
 *   <li><strong>Service Auth</strong>: Internal HS256 JWT (JwtAuthenticationFilter)</li>
 * </ul>
 * 
 * <h3>Filter Chain Order:</h3>
 * <ol>
 *   <li>SupabaseJwtAuthFilter (ES256 validation with JWKS)</li>
 *   <li>JwtAuthenticationFilter (HS256 validation for internal services)</li>
 *   <li>UsernamePasswordAuthenticationFilter (Spring Security default)</li>
 * </ol>
 * 
 * <p>Each filter handles its own token type and sets authentication if valid.</p>
 * 
 * @author Rentoza Development Team
 * @since 2.0.0 (Supabase Migration)
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final SupabaseJwtAuthFilter supabaseJwtAuthFilter;      // Supabase ES256 filter
    private final JwtAuthenticationFilter jwtAuthenticationFilter;  // Internal service HS256 filter

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/api/health").permitAll()
                        // Defense-in-depth: declarative admin authorization at URL-pattern level
                        // Supplements @PreAuthorize annotations on individual controller methods
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Internal service endpoints require INTERNAL_SERVICE role
                        .requestMatchers("/api/internal/**").hasRole("INTERNAL_SERVICE")
                        .anyRequest().authenticated()
                )
                // Dual authentication filter chain
                // 1. Try Supabase ES256 validation first
                .addFilterBefore(supabaseJwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // 2. Try internal service HS256 validation if ES256 fails
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Cache-Control",
                "X-XSRF-TOKEN", "X-Idempotency-Key"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

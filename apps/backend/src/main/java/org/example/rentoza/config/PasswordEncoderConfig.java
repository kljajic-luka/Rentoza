package org.example.rentoza.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Dedicated configuration for PasswordEncoder bean.
 *
 * Separated from SecurityConfig to prevent circular dependency issues.
 * This configuration has no dependencies, allowing it to initialize first
 * and be safely injected into other beans like UserService and CustomOAuth2UserService.
 */
@Configuration
public class PasswordEncoderConfig {

    /**
     * Provides BCryptPasswordEncoder with strength factor 12.
     * Used for encoding passwords in both local and OAuth2 user creation.
     *
     * @return BCryptPasswordEncoder instance
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}

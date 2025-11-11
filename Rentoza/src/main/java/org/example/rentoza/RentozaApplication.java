package org.example.rentoza;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Main Spring Boot application class for Rentoza car rental platform.
 * 
 * <p>Security Enhancements:
 * - @EnableMethodSecurity(prePostEnabled = true) enables @PreAuthorize/@PostAuthorize annotations
 * - Provides method-level access control across all services and controllers
 * - Integrates with custom JwtUserPrincipal and CurrentUser for RLS enforcement
 */
@SpringBootApplication
@EnableScheduling
@EnableMethodSecurity(prePostEnabled = true)
public class RentozaApplication {
    public static void main(String[] args) {
        SpringApplication.run(RentozaApplication.class, args);

    }

    @Bean
    public WebMvcConfigurer webMvcConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler("/uploads/**")
                        .addResourceLocations("file:uploads/");
            }
        };
    }
}

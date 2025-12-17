package org.example.rentoza;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Main Spring Boot application class for Rentoza car rental platform.
 * 
 * <p>Security Enhancements:
 * - @EnableMethodSecurity(prePostEnabled = true) enables @PreAuthorize/@PostAuthorize annotations
 * - Provides method-level access control across all services and controllers
 * - Integrates with custom JwtUserPrincipal and CurrentUser for RLS enforcement
 * 
 * <p>Resource Handling:
 * - Static resource configuration moved to {@link org.example.rentoza.config.ResourceHandlerConfig}
 * - Missing resource handling via {@link org.example.rentoza.config.StaticResourceExceptionHandler}
 */
@SpringBootApplication
@EnableScheduling
@EnableMethodSecurity(prePostEnabled = true)
public class RentozaApplication {
    public static void main(String[] args) {
        SpringApplication.run(RentozaApplication.class, args);
    }
}

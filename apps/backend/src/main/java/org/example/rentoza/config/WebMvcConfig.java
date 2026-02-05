package org.example.rentoza.config;

import lombok.RequiredArgsConstructor;
import org.example.rentoza.admin.AdminAuditInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration for interceptors.
 * 
 * <p>Registers custom interceptors:
 * <ul>
 *   <li>{@link AdminAuditInterceptor} - Audit logging for admin API calls</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {
    
    private final AdminAuditInterceptor adminAuditInterceptor;
    
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Register admin audit interceptor for all /api/admin/** paths
        registry.addInterceptor(adminAuditInterceptor)
                .addPathPatterns("/api/admin/**");
    }
}

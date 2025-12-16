package org.example.rentoza.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.providers.HateoasHalProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * OpenAPI/Swagger configuration for Rentoza API documentation.
 *
 * <p>This configuration:
 * <ul>
 *   <li>Configures API metadata (title, version, description)</li>
 *   <li>Sets up JWT Bearer authentication scheme</li>
 *   <li>Provides a workaround for Spring Boot 3.5.x + springdoc HATEOAS incompatibility</li>
 * </ul>
 *
 * <p>Access Swagger UI at: /swagger-ui.html
 * <p>Access OpenAPI spec at: /v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    /**
     * Custom OpenAPI specification with Rentoza branding and security scheme.
     */
    @Bean
    public OpenAPI rentozaOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Rentoza API")
                        .version("1.0.0")
                        .description("Enterprise Car Rental Platform API - Serbia")
                        .contact(new Contact()
                                .name("Rentoza Team")
                                .email("support@rentoza.rs"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://rentoza.rs/terms")))
                .addSecurityItem(new SecurityRequirement().addList("Bearer Authentication"))
                .schemaRequirement("Bearer Authentication", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Enter JWT token obtained from /api/auth/login"));
    }

    /**
     * Workaround for Spring Boot 3.5.x incompatibility with springdoc HATEOAS.
     *
     * <p>Spring Boot 3.5.x removed {@code HateoasProperties.getUseHalAsDefaultJsonMediaType()},
     * but springdoc 2.8.3 still calls it. This bean provides a stub implementation
     * that disables HAL detection to avoid the NoSuchMethodError.
     *
     * <p>This can be removed when springdoc releases a version compatible with Spring Boot 3.5.x.
     *
     * @see <a href="https://github.com/springdoc/springdoc-openapi/issues">springdoc issues</a>
     */
    @Bean
    @Primary
    public HateoasHalProvider hateoasHalProvider() {
        // Anonymous subclass that overrides isHalEnabled to avoid the broken method call
        return new HateoasHalProvider(null, null) {
            @Override
            public boolean isHalEnabled() {
                // Disable HAL detection to avoid calling the removed method
                return false;
            }
        };
    }
}

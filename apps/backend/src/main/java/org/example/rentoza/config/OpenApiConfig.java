package org.example.rentoza.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.providers.HateoasHalProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;

/**
 * OpenAPI/Swagger configuration for Rentoza API documentation.
 *
 * <p>This configuration provides:
 * <ul>
 *   <li>API metadata (title, version, description)</li>
 *   <li>JWT Bearer and Cookie authentication schemes</li>
 *   <li>API grouping by domain (Public, Bookings, Cars, Admin)</li>
 *   <li>Common response definitions for error handling</li>
 *   <li>Rate limiting documentation</li>
 *   <li>GDPR compliance information</li>
 * </ul>
 *
 * <p>Access Swagger UI at: /swagger-ui.html
 * <p>Access OpenAPI spec at: /v3/api-docs
 * <p>Access grouped specs at: /v3/api-docs/{group-name}
 */
@Configuration
public class OpenApiConfig {

    @Value("${app.version:1.0.0}")
    private String appVersion;

    @Value("${app.api.url:https://api.rentoza.rs}")
    private String apiUrl;

    /**
     * Custom OpenAPI specification with Rentoza branding and security schemes.
     */
    @Bean
    public OpenAPI rentozaOpenAPI() {
        return new OpenAPI()
                .info(apiInfo())
                .servers(servers())
                .tags(apiTags())
                .externalDocs(new ExternalDocumentation()
                        .description("Rentoza Developer Documentation")
                        .url("https://docs.rentoza.rs"))
                .components(apiComponents())
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .addSecurityItem(new SecurityRequirement().addList("cookieAuth"));
    }

    private Info apiInfo() {
        return new Info()
                .title("Rentoza P2P Car Rental API")
                .version(appVersion)
                .description("""
                    ## Rentoza - Peer-to-Peer Car Rental Platform API
                    
                    Welcome to the Rentoza API documentation. This API enables:
                    - **Car Owners** to list and manage their vehicles
                    - **Renters** to search, book, and rent vehicles
                    - **Admins** to manage users, verify documents, and resolve disputes
                    
                    ### Authentication
                    All protected endpoints require JWT authentication. Obtain a token via `/api/auth/login`.
                    
                    ### Rate Limiting
                    - **Standard endpoints**: 100 requests/minute
                    - **Search endpoints**: 30 requests/minute
                    - **Auth endpoints**: 10 requests/minute
                    
                    ### Error Codes
                    | Code | Meaning |
                    |------|---------|
                    | 400 | Bad Request - Invalid input |
                    | 401 | Unauthorized - Missing/invalid token |
                    | 403 | Forbidden - Insufficient permissions |
                    | 404 | Not Found - Resource doesn't exist |
                    | 409 | Conflict - Resource already exists |
                    | 429 | Too Many Requests - Rate limit exceeded |
                    | 500 | Internal Server Error |
                    
                    ### GDPR Compliance
                    This API is GDPR-compliant. Users can:
                    - Export their data via `/api/users/me/data-export`
                    - Delete their account via `/api/users/me/delete`
                    - Manage consent via `/api/users/me/consent`
                    """)
                .contact(new Contact()
                        .name("Rentoza API Support")
                        .email("rentozzza@gmail.com")
                        .url("https://rentoza.rs/support"))
                .license(new License()
                        .name("Proprietary - All Rights Reserved")
                        .url("https://rentoza.rs/terms"))
                .termsOfService("https://rentoza.rs/terms-of-service");
    }

    private List<Server> servers() {
        return List.of(
                new Server()
                        .url("https://api.rentoza.rs")
                        .description("Production Server"),
                new Server()
                        .url("https://staging-api.rentoza.rs")
                        .description("Staging Server"),
                new Server()
                        .url("http://localhost:8080")
                        .description("Local Development")
        );
    }

    private List<Tag> apiTags() {
        return List.of(
                new Tag().name("Authentication")
                        .description("User registration, login, token refresh, and logout"),
                new Tag().name("Users")
                        .description("User profile management and verification"),
                new Tag().name("Cars")
                        .description("Vehicle listing, search, and management"),
                new Tag().name("Bookings")
                        .description("Booking creation, management, and cancellation"),
                new Tag().name("Check-in/Check-out")
                        .description("Trip check-in and check-out processes"),
                new Tag().name("Reviews")
                        .description("Review and rating management"),
                new Tag().name("Payments")
                        .description("Payment processing and refunds"),
                new Tag().name("Admin")
                        .description("Administrative operations (requires ADMIN role)"),
                new Tag().name("Disputes")
                        .description("Damage claims and dispute resolution"),
                new Tag().name("GDPR")
                        .description("GDPR compliance endpoints - data export, deletion, consent")
        );
    }

    private Components apiComponents() {
        return new Components()
                .addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("JWT token obtained from `/api/auth/login`. " +
                                "Include in Authorization header as: `Bearer {token}`"))
                .addSecuritySchemes("cookieAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name("access_token")
                        .description("HttpOnly cookie containing JWT access token"))
                .addResponses("BadRequest", new ApiResponse()
                        .description("Bad Request - Invalid input parameters"))
                .addResponses("Unauthorized", new ApiResponse()
                        .description("Unauthorized - Authentication required"))
                .addResponses("Forbidden", new ApiResponse()
                        .description("Forbidden - Insufficient permissions"))
                .addResponses("NotFound", new ApiResponse()
                        .description("Not Found - Resource does not exist"))
                .addResponses("RateLimited", new ApiResponse()
                        .description("Too Many Requests - Rate limit exceeded. " +
                                "Check X-RateLimit-Retry-After header"))
                .addSchemas("ErrorResponse", new Schema<Map<String, Object>>()
                        .type("object")
                        .description("Standard error response")
                        .addProperty("error", new Schema<String>().type("string")
                                .description("Error message"))
                        .addProperty("code", new Schema<String>().type("string")
                                .description("Error code"))
                        .addProperty("timestamp", new Schema<String>().type("string")
                                .format("date-time")
                                .description("Error timestamp"))
                        .addProperty("path", new Schema<String>().type("string")
                                .description("Request path")));
    }

    // ==================== API Grouping ====================

    /**
     * Public API group - endpoints that don't require authentication.
     */
    @Bean
    public GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder()
                .group("1-public")
                .displayName("Public API")
                .pathsToMatch(
                        "/api/auth/**",
                        "/api/cars/search/**",
                        "/api/cars/{id}",
                        "/api/stats/**",
                        "/api/locations/**"
                )
                .addOpenApiCustomizer(publicApiCustomizer())
                .build();
    }

    /**
     * Bookings API group - booking-related endpoints.
     */
    @Bean
    public GroupedOpenApi bookingsApi() {
        return GroupedOpenApi.builder()
                .group("2-bookings")
                .displayName("Bookings API")
                .pathsToMatch(
                        "/api/bookings/**",
                        "/api/checkin/**",
                        "/api/checkout/**",
                        "/api/trip-extensions/**"
                )
                .build();
    }

    /**
     * Cars API group - vehicle management endpoints.
     */
    @Bean
    public GroupedOpenApi carsApi() {
        return GroupedOpenApi.builder()
                .group("3-cars")
                .displayName("Cars API")
                .pathsToMatch("/api/cars/**", "/api/availability/**")
                .build();
    }

    /**
     * Users API group - user profile and GDPR endpoints.
     */
    @Bean
    public GroupedOpenApi usersApi() {
        return GroupedOpenApi.builder()
                .group("4-users")
                .displayName("Users & GDPR API")
                .pathsToMatch(
                        "/api/users/**",
                        "/api/reviews/**",
                        "/api/favorites/**"
                )
                .build();
    }

    /**
     * Admin API group - administrative endpoints.
     */
    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
                .group("5-admin")
                .displayName("Admin API")
                .pathsToMatch("/api/admin/**")
                .build();
    }

    private OpenApiCustomizer publicApiCustomizer() {
        return openApi -> openApi.getInfo()
                .description(openApi.getInfo().getDescription() +
                        "\n\n### Public Endpoints\nThese endpoints do not require authentication.");
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

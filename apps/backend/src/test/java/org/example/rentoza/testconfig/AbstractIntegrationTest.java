package org.example.rentoza.testconfig;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Abstract base class for integration tests using Testcontainers with PostgreSQL.
 * 
 * <p>This provides production-like database testing instead of H2 in-memory,
 * ensuring that all PostgreSQL-specific features (JSONB, arrays, exclusion constraints)
 * work correctly before deployment.
 * 
 * <h2>Usage</h2>
 * <pre>
 * class MyIntegrationTest extends AbstractIntegrationTest {
 *     // Your test methods here
 * }
 * </pre>
 * 
 * <h2>Features</h2>
 * <ul>
 *   <li>PostgreSQL 14 container (matches production)</li>
 *   <li>Automatic container lifecycle management</li>
 *   <li>Dynamic property injection for datasource</li>
 *   <li>Shared container across test classes for performance</li>
 * </ul>
 * 
 * @see <a href="https://www.testcontainers.org/">Testcontainers Documentation</a>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("testcontainers")
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractIntegrationTest {

    /**
     * Shared PostgreSQL container for all integration tests.
     * Using static container with reuse enabled for faster test execution.
     */
    @Container
    protected static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14-alpine")
            .withDatabaseName("rentoza_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);

    /**
     * Dynamically configures Spring datasource properties from Testcontainers.
     * This method is called before Spring context initialization.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }
}

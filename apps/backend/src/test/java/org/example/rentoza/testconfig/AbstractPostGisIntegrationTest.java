package org.example.rentoza.testconfig;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Abstract base class for integration tests requiring PostGIS spatial features.
 *
 * <p>Uses postgis/postgis Docker image instead of plain postgres, providing:
 * <ul>
 *   <li>Full PostGIS extension support (ST_DWithin, ST_Distance, geography types)</li>
 *   <li>GiST spatial indexes</li>
 *   <li>Same configuration pattern as {@link AbstractIntegrationTest}</li>
 * </ul>
 *
 * <p><b>Tiger Schema Migration:</b>
 * Production (Supabase) installs PostGIS in the {@code tiger} schema.
 * Repository queries (e.g., {@code CarRepository.findNearby}) reference
 * {@code tiger.ST_DWithin}, {@code tiger.geography}, etc.
 * This base class moves PostGIS from the default {@code public} schema
 * to {@code tiger} and sets the search_path so both qualified
 * ({@code tiger.ST_DWithin}) and unqualified ({@code ST_DWithin}) calls work.
 *
 * <p>After Hibernate creates the schema (create-drop), geography columns
 * must be created manually since they are not part of the JPA entity model.
 * Subclasses should handle this in {@code @BeforeAll} or {@code @BeforeEach}.
 *
 * @see AbstractIntegrationTest
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("testcontainers")
@Testcontainers
public abstract class AbstractPostGisIntegrationTest {

    @Container
    protected static final PostgreSQLContainer<?> postgis = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:14-3.3-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("rentoza_postgis_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgis::getJdbcUrl);
        registry.add("spring.datasource.username", postgis::getUsername);
        registry.add("spring.datasource.password", postgis::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");

        // Ensure every connection includes tiger in search_path
        // so both tiger.ST_DWithin(...) and ST_DWithin(...) resolve correctly
        registry.add("spring.datasource.hikari.connection-init-sql",
                () -> "SET search_path TO public, tiger");

        migratePostGisToTigerSchema();
    }

    /**
     * Migrate PostGIS from the default public schema to the tiger schema,
     * matching production Supabase configuration.
     *
     * <p>The postgis Docker image auto-installs PostGIS in public.
     * This method creates the tiger schema and moves the extension there.
     * Idempotent: safe to call on reused containers.
     */
    private static void migratePostGisToTigerSchema() {
        try (Connection conn = DriverManager.getConnection(
                postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS tiger");
            // Move PostGIS from public to tiger (idempotent if already in tiger)
            stmt.execute("DO $$ BEGIN "
                    + "IF EXISTS (SELECT 1 FROM pg_extension e "
                    + "JOIN pg_namespace n ON e.extnamespace = n.oid "
                    + "WHERE e.extname = 'postgis' AND n.nspname = 'public') THEN "
                    + "EXECUTE 'ALTER EXTENSION postgis SET SCHEMA tiger'; "
                    + "END IF; "
                    + "END $$");
            // Set default search_path for new connections
            stmt.execute("ALTER DATABASE " + postgis.getDatabaseName()
                    + " SET search_path TO public, tiger");
        } catch (Exception e) {
            throw new RuntimeException(
                    "PostGIS tiger-schema migration failed. "
                    + "Ensure the postgis/postgis Docker image is running and accessible. "
                    + "If the container was reused, this may indicate a schema conflict: " + e.getMessage(),
                    e);
        }
    }
}

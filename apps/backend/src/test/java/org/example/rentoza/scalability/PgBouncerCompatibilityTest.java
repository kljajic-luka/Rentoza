package org.example.rentoza.scalability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G8: Verifies PgBouncer-compatible connection settings in production.
 *
 * <p>PgBouncer in transaction-pooling mode requires specific Hibernate and
 * HikariCP settings to avoid prepared statement leaks and connection
 * state issues. These tests verify those settings are present.
 */
class PgBouncerCompatibilityTest {

    @Test
    @DisplayName("G8: Prepared statement caching disabled for PgBouncer transaction mode")
    void prodProperties_disablePreparedStatementCaching() throws Exception {
        String prodProps = readProdProperties();

        assertThat(prodProps)
                .as("prepareThreshold must be 0 (disable server-side prepared statements)")
                .contains("spring.datasource.hikari.data-source-properties.prepareThreshold=0");

        assertThat(prodProps)
                .as("preparedStatementCacheQueries must be 0")
                .contains("spring.datasource.hikari.data-source-properties.preparedStatementCacheQueries=0");

        assertThat(prodProps)
                .as("preparedStatementCacheSizeMiB must be 0")
                .contains("spring.datasource.hikari.data-source-properties.preparedStatementCacheSizeMiB=0");
    }

    @Test
    @DisplayName("G8: Hibernate connection handling set to DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION")
    void prodProperties_delayedConnectionHandling() throws Exception {
        String prodProps = readProdProperties();

        assertThat(prodProps)
                .as("Connection handling must release after transaction for PgBouncer")
                .contains("hibernate.connection.handling_mode=DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION");
    }

    @Test
    @DisplayName("G8: HikariCP keepalive and validation timeout configured")
    void prodProperties_keepaliveAndValidation() throws Exception {
        String prodProps = readProdProperties();

        assertThat(prodProps)
                .as("keepalive-time must be configured for PgBouncer idle eviction")
                .contains("spring.datasource.hikari.keepalive-time=");

        assertThat(prodProps)
                .as("validation-timeout must be configured")
                .contains("spring.datasource.hikari.validation-timeout=");
    }

    private String readProdProperties() throws Exception {
        java.nio.file.Path path = java.nio.file.Path.of(
                System.getProperty("user.dir"),
                "src/main/resources/application-prod.properties");
        if (!java.nio.file.Files.exists(path)) {
            path = java.nio.file.Path.of("src/main/resources/application-prod.properties");
        }
        assertThat(path).as("application-prod.properties must exist").exists();
        return java.nio.file.Files.readString(path);
    }
}

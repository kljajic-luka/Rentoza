package org.example.rentoza.scalability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G7: Verifies read-replica datasource routing configuration scaffold.
 *
 * <p>Ensures production properties define the necessary config keys for
 * read/write datasource routing when a read replica is available.
 */
class ReadReplicaDatasourceRoutingTest {

    @Test
    @DisplayName("G7: Production properties define read-replica URL, username, password")
    void prodProperties_defineReadReplicaConfig() throws Exception {
        String prodProps = readProdProperties();

        assertThat(prodProps)
                .as("Must define read-replica URL")
                .contains("app.datasource.read-replica.url=${READ_REPLICA_URL:");

        assertThat(prodProps)
                .as("Must define read-replica username (falls back to primary)")
                .contains("app.datasource.read-replica.username=${READ_REPLICA_USERNAME:${DB_USERNAME}}");

        assertThat(prodProps)
                .as("Must define read-replica password (falls back to primary)")
                .contains("app.datasource.read-replica.password=${READ_REPLICA_PASSWORD:${DB_PASSWORD}}");
    }

    @Test
    @DisplayName("G7: Read-replica is disabled by default (opt-in)")
    void readReplica_defaultsToDisabled() throws Exception {
        String prodProps = readProdProperties();

        assertThat(prodProps)
                .as("Read-replica must default to disabled")
                .contains("app.datasource.read-replica.enabled=${READ_REPLICA_ENABLED:false}");
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

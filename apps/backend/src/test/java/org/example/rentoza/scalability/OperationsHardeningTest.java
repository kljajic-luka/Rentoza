package org.example.rentoza.scalability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G10: Operations hardening verification — validates production-critical
 * configuration invariants that must hold across all deployments.
 *
 * <h2>Cloud Run Operational Checklist</h2>
 * <ol>
 *   <li>Health endpoint is public (load balancer probes)</li>
 *   <li>Secrets are injected via env vars, never hardcoded</li>
 *   <li>Graceful shutdown is enabled</li>
 *   <li>Connection pools are sized for Cloud Run (max 5 per instance)</li>
 *   <li>Scheduled jobs use distributed locking</li>
 *   <li>Error details are never exposed to clients</li>
 *   <li>RabbitMQ and Redis are opt-in (fail-closed defaults)</li>
 * </ol>
 *
 * <h2>Autoscaling Guidelines (Cloud Run)</h2>
 * <ul>
 *   <li>min-instances: 1 (avoid cold starts for payment webhooks)</li>
 *   <li>max-instances: 10 (protect downstream DB connection pool)</li>
 *   <li>concurrency: 80 (Spring MVC thread pool default)</li>
 *   <li>cpu-throttling: false (background schedulers need CPU when idle)</li>
 * </ul>
 *
 * <h2>Failure-Mode Matrix</h2>
 * <table>
 *   <tr><th>Component Down</th><th>Impact</th><th>Recovery</th></tr>
 *   <tr><td>PostgreSQL</td><td>Full outage</td><td>Auto-reconnect via HikariCP</td></tr>
 *   <tr><td>Redis</td><td>Rate limits + scheduler locks degrade to in-memory</td><td>Auto-reconnect</td></tr>
 *   <tr><td>RabbitMQ</td><td>Async processing paused (photo, notif)</td><td>Consumer auto-reconnect</td></tr>
 *   <tr><td>Supabase Storage</td><td>Photo uploads fail</td><td>Retry with circuit breaker</td></tr>
 * </table>
 *
 * <h2>Rollback Plan</h2>
 * <ol>
 *   <li>Revert Cloud Run to previous revision: {@code gcloud run services update-traffic --to-revisions=PREV_REV=100}</li>
 *   <li>DB schema is backward-compatible (Hibernate ddl-auto=none, Flyway migrations are additive)</li>
 *   <li>Redis cache: evict all keys with {@code FLUSHDB} if schema changed</li>
 * </ol>
 */
class OperationsHardeningTest {

    @Nested
    @DisplayName("Production Security Invariants")
    class SecurityInvariants {

        @Test
        @DisplayName("G10: Secrets are never hardcoded in production properties")
        void noHardcodedSecretsInProd() throws Exception {
            String prodProps = readProdProperties();

            // JWT secrets must use env-var placeholders
            assertThat(prodProps).as("JWT_SECRET must be env-injected")
                    .contains("jwt.secret=${JWT_SECRET}");
            assertThat(prodProps).as("INTERNAL_SERVICE_JWT_SECRET must be env-injected")
                    .contains("internal.service.jwt.secret=${INTERNAL_SERVICE_JWT_SECRET}");

            // Database credentials must use env-var placeholders
            assertThat(prodProps).as("DB_PASSWORD must be env-injected")
                    .contains("spring.datasource.password=${DB_PASSWORD}");

            // No mock defaults in prod for payment — must fail fast
            assertThat(prodProps).as("PAYMENT_PROVIDER must not default to MOCK in prod")
                    .contains("app.payment.provider=${PAYMENT_PROVIDER}")
                    .doesNotContain("app.payment.provider=${PAYMENT_PROVIDER:MOCK}");
        }

        @Test
        @DisplayName("G10: Error details are suppressed in production")
        void errorDetailsAreNeverExposed() throws Exception {
            String prodProps = readProdProperties();

            assertThat(prodProps).as("Must suppress error messages")
                    .contains("server.error.include-message=never");
            assertThat(prodProps).as("Must suppress stack traces")
                    .contains("server.error.include-stacktrace=never");
            assertThat(prodProps).as("Must suppress exception details")
                    .contains("server.error.include-exception=false");
        }
    }

    @Nested
    @DisplayName("Infrastructure Resilience")
    class InfrastructureResilience {

        @Test
        @DisplayName("G10: Graceful shutdown is configured")
        void gracefulShutdownConfigured() throws Exception {
            String mainProps = readMainProperties();

            assertThat(mainProps).as("Graceful shutdown must be enabled")
                    .contains("server.shutdown=graceful");
            assertThat(mainProps).as("Shutdown timeout must be configured")
                    .contains("spring.lifecycle.timeout-per-shutdown-phase=");
        }

        @Test
        @DisplayName("G10: Redis defaults to enabled in prod (distributed locks)")
        void redisDefaultsToEnabledInProd() throws Exception {
            String prodProps = readProdProperties();

            assertThat(prodProps).as("Redis must default to enabled in production")
                    .contains("app.redis.enabled=${REDIS_ENABLED:true}");
        }

        @Test
        @DisplayName("G10: RabbitMQ defaults to disabled (fail-closed)")
        void rabbitMQDefaultsToDisabled() throws Exception {
            String prodProps = readProdProperties();

            assertThat(prodProps).as("RabbitMQ must default to disabled (opt-in)")
                    .contains("app.rabbitmq.enabled=${RABBITMQ_ENABLED:false}");
        }

        @Test
        @DisplayName("G10: Actuator health endpoints are configured for monitoring")
        void actuatorHealthConfigured() throws Exception {
            String prodProps = readProdProperties();

            assertThat(prodProps).as("Health endpoint must be exposed")
                    .contains("management.endpoints.web.exposure.include=health");
            assertThat(prodProps).as("Prometheus metrics must be exposed")
                    .contains("prometheus");
            assertThat(prodProps).as("DB health check must be enabled")
                    .contains("management.health.db.enabled=true");
        }
    }

    @Nested
    @DisplayName("Cloud Run Deployment Readiness")
    class CloudRunReadiness {

        @Test
        @DisplayName("G10: cloudrun-env.yaml.template covers all required env vars")
        void cloudRunTemplateCompleteness() throws Exception {
            String template = readCloudRunTemplate();

            // Core secrets
            assertThat(template).as("Must include DB credentials").contains("DB_URL");
            assertThat(template).as("Must include JWT secret").contains("JWT_SECRET");
            assertThat(template).as("Must include Supabase config").contains("SUPABASE_URL");

            // Infrastructure toggles
            assertThat(template).as("Must include Redis toggle").contains("REDIS_ENABLED");
            assertThat(template).as("Must include RabbitMQ toggle").contains("RABBITMQ_ENABLED");

            // Cookie security
            assertThat(template).as("Must set secure cookies").contains("COOKIE_SECURE");
            assertThat(template).as("Must set SameSite").contains("COOKIE_SAME_SITE");

            // Rate limiting
            assertThat(template).as("Must enable rate limiting").contains("APP_RATE_LIMIT_ENABLED");
        }

        @Test
        @DisplayName("G10: HikariCP pool sized for Cloud Run (max 5)")
        void connectionPoolSizedForCloudRun() throws Exception {
            String template = readCloudRunTemplate();

            assertThat(template).as("HikariCP max pool must be <=5 for Cloud Run")
                    .contains("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE")
                    .contains("\"5\"");
        }

        @Test
        @DisplayName("G10: Hibernate ddl-auto is 'none' in production")
        void hibernateDdlAutoIsNone() throws Exception {
            String prodProps = readProdProperties();

            assertThat(prodProps).as("ddl-auto must be 'none' for Supabase-managed schema")
                    .contains("spring.jpa.hibernate.ddl-auto=none");
        }
    }

    // ==================== Helpers ====================

    private String readProdProperties() throws Exception {
        return readFile("src/main/resources/application-prod.properties");
    }

    private String readMainProperties() throws Exception {
        return readFile("src/main/resources/application.properties");
    }

    private String readCloudRunTemplate() throws Exception {
        return readFile("cloudrun-env.yaml.template");
    }

    private String readFile(String relativePath) throws Exception {
        java.nio.file.Path path = java.nio.file.Path.of(
                System.getProperty("user.dir"), relativePath);
        if (!java.nio.file.Files.exists(path)) {
            path = java.nio.file.Path.of(relativePath);
        }
        assertThat(path).as("File %s must exist", relativePath).exists();
        return java.nio.file.Files.readString(path);
    }
}

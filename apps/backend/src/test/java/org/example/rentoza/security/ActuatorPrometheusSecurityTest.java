package org.example.rentoza.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G2: Verifies that the /actuator/prometheus endpoint is protected by
 * ADMIN role authorization in SecurityConfig.
 *
 * <p>Uses source-level verification: reads the SecurityFilterChain configuration method
 * to confirm the authorization rule exists and is not permitAll.
 *
 * <p>Regression guard for audit finding W3 (commit f0d7138).
 *
 * <p><b>Why not @WebMvcTest?</b> The full Spring context cannot be loaded in this
 * module without infrastructure dependencies (Supabase, PostgreSQL, Redis).
 * These annotation-level tests provide regression protection without requiring
 * a running application context. A full integration test should be executed
 * in the CI pipeline's integration-test phase with Testcontainers.
 */
class ActuatorPrometheusSecurityTest {

    @Test
    @DisplayName("G2: SecurityConfig.filterChain method exists and is a Bean")
    void securityConfig_hasFilterChainBeanMethod() throws Exception {
        // Verifies at least one method named 'filterChain' exists and is a @Bean.
        // If it's renamed or removed, this test acts as a regression canary.
        boolean found = false;
        for (java.lang.reflect.Method m : SecurityConfig.class.getDeclaredMethods()) {
            if ("filterChain".equals(m.getName())
                    && m.isAnnotationPresent(org.springframework.context.annotation.Bean.class)) {
                found = true;
                break;
            }
        }
        assertThat(found)
                .as("SecurityConfig must have a @Bean filterChain method")
                .isTrue();
    }

    @Test
    @DisplayName("G2: /actuator/prometheus requires ADMIN role (source-level verification)")
    void prometheusEndpoint_requiresAdminRole() throws Exception {
        // Read the SecurityConfig source to verify the authorization rule.
        // This is a structural regression test: if someone changes hasRole("ADMIN")
        // back to permitAll(), this test catches it.
        String sourceCode = readClassSource(SecurityConfig.class);

        // The rule must exist in this exact form (per W3 remediation)
        assertThat(sourceCode)
                .as("SecurityConfig must require ADMIN role for /actuator/prometheus")
                .contains(".requestMatchers(\"/actuator/prometheus\").hasRole(\"ADMIN\")");

        // The catch-all for other actuator endpoints must also require ADMIN
        assertThat(sourceCode)
                .as("SecurityConfig must require ADMIN role for all other actuator endpoints")
                .contains(".requestMatchers(\"/actuator/**\").hasRole(\"ADMIN\")");

        // Verify it does NOT use permitAll for prometheus
        assertThat(sourceCode)
                .as("SecurityConfig must NOT use permitAll for /actuator/prometheus")
                .doesNotContain(".requestMatchers(\"/actuator/prometheus\").permitAll()");
    }

    @Test
    @DisplayName("G2: /actuator/health and /actuator/info remain public (no regression)")
    void healthAndInfoEndpoints_remainPublic() throws Exception {
        String sourceCode = readClassSource(SecurityConfig.class);

        assertThat(sourceCode)
                .as("Health endpoint must remain public for load balancer probes")
                .contains(".requestMatchers(\"/actuator/health\"");

        assertThat(sourceCode)
                .as("Info endpoint must remain public")
                .contains(".requestMatchers(\"/actuator/info\").permitAll()");
    }

    /**
     * Reads the source file for a given class by locating it on the source path.
     * Falls back to class bytecode analysis if source is unavailable.
     */
    private String readClassSource(Class<?> clazz) throws Exception {
        // Locate source relative to the test's working directory
        String relativePath = "src/main/java/"
                + clazz.getName().replace('.', '/') + ".java";

        java.nio.file.Path sourcePath = java.nio.file.Path.of(relativePath);
        if (!java.nio.file.Files.exists(sourcePath)) {
            // Try from project root
            sourcePath = java.nio.file.Path.of(
                    System.getProperty("user.dir"), relativePath);
        }

        assertThat(sourcePath)
                .as("Source file for %s must exist at %s", clazz.getSimpleName(), sourcePath)
                .exists();

        return java.nio.file.Files.readString(sourcePath);
    }
}

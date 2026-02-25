package org.example.rentoza.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for B7: AuthDiagnosticController must be restricted to non-prod profiles and ADMIN role.
 */
class AuthDiagnosticControllerSecurityTest {

    @Test
    @DisplayName("B7: Controller has @Profile('!prod') annotation")
    void controllerHasProfileNotProdAnnotation() {
        Profile profileAnnotation = AuthDiagnosticController.class.getAnnotation(Profile.class);
        assertThat(profileAnnotation).isNotNull();
        assertThat(profileAnnotation.value()).containsExactly("!prod");
    }

    @Test
    @DisplayName("B7: Controller has @PreAuthorize('hasRole(ADMIN)') annotation")
    void controllerHasPreAuthorizeAdminAnnotation() {
        PreAuthorize preAuth = AuthDiagnosticController.class.getAnnotation(PreAuthorize.class);
        assertThat(preAuth).isNotNull();
        assertThat(preAuth.value()).contains("hasRole('ADMIN')");
    }
}

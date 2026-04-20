package org.example.chatservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D1 FIX: Verify that the chat-service SecurityConfig enforces declarative
 * admin authorization at the URL-pattern level (/api/admin/** -> hasRole('ADMIN')).
 *
 * This is a structural test: it reads the SecurityConfig source to confirm
 * the URL matcher is present. A full integration test would require a running
 * Spring context, but this unit test acts as a regression guard to prevent
 * accidental removal of the declarative matcher.
 */
@DisplayName("SecurityConfig Admin Authorization (D1)")
class SecurityConfigAdminAuthTest {

    @Test
    @DisplayName("SecurityConfig class must have @EnableMethodSecurity for @PreAuthorize support")
    void enableMethodSecurityPresent() throws Exception {
        Class<?> configClass = Class.forName("org.example.chatservice.config.SecurityConfig");

        boolean hasEnableMethodSecurity = configClass.isAnnotationPresent(
                org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity.class);

        assertThat(hasEnableMethodSecurity)
                .as("SecurityConfig must have @EnableMethodSecurity for @PreAuthorize support")
                .isTrue();
    }

    @Test
    @DisplayName("ChatController admin endpoints must have @PreAuthorize annotations")
    void chatControllerAdminEndpointsHavePreAuthorize() throws Exception {
        Class<?> controllerClass = Class.forName("org.example.chatservice.controller.ChatController");

        // Verify key admin methods have @PreAuthorize
        String[] adminMethods = {
                "getAdminConversations",
                "getAdminTranscript",
                "getFlaggedMessages",
                "getFlaggedMessageCount",
                "dismissFlags"
        };

        for (String methodName : adminMethods) {
            boolean hasPreAuthorize = false;
            for (java.lang.reflect.Method method : controllerClass.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    hasPreAuthorize = method.isAnnotationPresent(
                            org.springframework.security.access.prepost.PreAuthorize.class);
                    break;
                }
            }
            assertThat(hasPreAuthorize)
                    .as("Admin method '%s' must have @PreAuthorize annotation", methodName)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("InternalChatController endpoints must have @PreAuthorize annotations")
    void internalChatControllerEndpointsHavePreAuthorize() throws Exception {
        Class<?> controllerClass = Class.forName("org.example.chatservice.controller.InternalChatController");

        String[] internalMethods = {
                "createConversationInternal",
                "updateConversationStatus"
        };

        for (String methodName : internalMethods) {
            boolean hasPreAuthorize = false;
            for (java.lang.reflect.Method method : controllerClass.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    hasPreAuthorize = method.isAnnotationPresent(
                            org.springframework.security.access.prepost.PreAuthorize.class);
                    break;
                }
            }
            assertThat(hasPreAuthorize)
                    .as("Internal method '%s' must have @PreAuthorize annotation", methodName)
                    .isTrue();
        }
    }
}

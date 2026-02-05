package org.example.rentoza.admin.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminOwnerVerificationSecurityTest {

    @Test
    void controllerIsAdminProtectedViaPreAuthorize() {
        PreAuthorize preAuthorize = AdminOwnerVerificationController.class.getAnnotation(PreAuthorize.class);
        assertNotNull(preAuthorize, "Expected @PreAuthorize on AdminOwnerVerificationController");

        // Keep this tolerant of minor formatting differences.
        String value = preAuthorize.value();
        assertTrue(
                value.contains("hasRole('ADMIN')") || value.contains("hasRole(\"ADMIN\")") || value.contains("hasRole('ROLE_ADMIN')"),
                "Expected @PreAuthorize to enforce ADMIN role, got: " + value
        );
    }

    @Test
    void controllerIsUnderAdminApiNamespace() {
        RequestMapping requestMapping = AdminOwnerVerificationController.class.getAnnotation(RequestMapping.class);
        assertNotNull(requestMapping, "Expected @RequestMapping on AdminOwnerVerificationController");

        String[] paths = requestMapping.value().length > 0 ? requestMapping.value() : requestMapping.path();
        assertTrue(paths.length > 0, "Expected @RequestMapping to declare a base path");
        assertTrue(paths[0].startsWith("/api/admin/"), "Expected controller base path under /api/admin, got: " + paths[0]);
    }
}

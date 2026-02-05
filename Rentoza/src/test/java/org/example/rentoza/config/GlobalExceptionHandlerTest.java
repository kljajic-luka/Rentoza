package org.example.rentoza.config;

import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for GlobalExceptionHandler.
 * 
 * BUG-006: Verifies optimistic lock exceptions return HTTP 409 Conflict
 * with proper retry semantics instead of 500 Internal Server Error.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ========== BUG-006: Optimistic Locking Tests ==========

    @Test
    @DisplayName("OptimisticLockingFailureException returns 409 with STALE_DATA code")
    void optimisticLockingFailureException_returns409WithStaleDataCode() {
        // Given
        OptimisticLockingFailureException ex = new OptimisticLockingFailureException("Concurrent modification");

        // When
        ResponseEntity<Map<String, Object>> response = handler.handleOptimisticLockException(ex);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("STALE_DATA");
        assertThat(response.getBody().get("retryable")).isEqualTo(true);
        assertThat(response.getHeaders().get("Retry-After")).isNotNull();
    }

    @Test
    @DisplayName("ObjectOptimisticLockingFailureException returns 409 with STALE_DATA code")
    void objectOptimisticLockingFailureException_returns409WithStaleDataCode() {
        // Given
        ObjectOptimisticLockingFailureException ex = 
            new ObjectOptimisticLockingFailureException("Booking", 123L);

        // When
        ResponseEntity<Map<String, Object>> response = handler.handleOptimisticLockException(ex);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("STALE_DATA");
        assertThat(response.getBody().get("message")).asString().contains("Molimo osvežite stranicu");
        assertThat(response.getBody().get("messageEn")).asString().contains("refresh and try again");
    }

    @Test
    @DisplayName("JPA OptimisticLockException returns 409 with STALE_DATA code")
    void jpaOptimisticLockException_returns409WithStaleDataCode() {
        // Given
        OptimisticLockException ex = new OptimisticLockException("JPA concurrent modification");

        // When
        ResponseEntity<Map<String, Object>> response = handler.handleOptimisticLockException(ex);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("STALE_DATA");
        assertThat(response.getBody().get("retryable")).isEqualTo(true);
    }

    @Test
    @DisplayName("Response body includes both Serbian and English messages")
    void optimisticLockResponse_includesBothLanguages() {
        // Given
        OptimisticLockingFailureException ex = new OptimisticLockingFailureException("Test");

        // When
        ResponseEntity<Map<String, Object>> response = handler.handleOptimisticLockException(ex);

        // Then
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("message")).asString().isNotEmpty();
        assertThat(response.getBody().get("messageEn")).asString().isNotEmpty();
    }

    // ========== Existing Exception Handling Tests ==========

    @Test
    @DisplayName("DeadlockLoserDataAccessException returns 409 with DB_DEADLOCK code")
    void deadlockLoserException_returns409WithDeadlockCode() {
        // Given
        DeadlockLoserDataAccessException ex = 
            new DeadlockLoserDataAccessException("Deadlock detected", null);

        // When
        ResponseEntity<Map<String, Object>> response = handler.handleDatabaseDeadlock(ex);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("DB_DEADLOCK");
        assertThat(response.getHeaders().get("Retry-After")).isNotNull();
    }

    @Test
    @DisplayName("CannotAcquireLockException returns 409 with DB_DEADLOCK code")
    void cannotAcquireLockException_returns409WithDeadlockCode() {
        // Given
        CannotAcquireLockException ex = 
            new CannotAcquireLockException("Lock acquisition failed");

        // When
        ResponseEntity<Map<String, Object>> response = handler.handleDatabaseDeadlock(ex);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("DB_DEADLOCK");
    }

    @Test
    @DisplayName("General exception returns 500 with correlation ID")
    void generalException_returns500WithCorrelationId() {
        // Given
        RuntimeException ex = new RuntimeException("Unexpected error");

        // When
        ResponseEntity<Map<String, Object>> response = handler.handleGeneralError(ex);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("correlationId")).asString().startsWith("ERR-");
        // Security: Should NOT contain internal exception details
        assertThat(response.getBody().get("message")).asString()
            .doesNotContain("Unexpected error");
    }

    @Test
    @DisplayName("AccessDeniedException returns 403 Forbidden")
    void accessDeniedException_returns403() {
        // Given
        org.springframework.security.access.AccessDeniedException ex = 
            new org.springframework.security.access.AccessDeniedException("Not authorized");

        // When
        ResponseEntity<Map<String, Object>> response = handler.handleAccessDenied(ex);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("error")).isEqualTo("Forbidden");
    }
}

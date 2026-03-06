package org.example.rentoza.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.example.rentoza.exception.ApiErrorResponse;
import org.example.rentoza.security.supabase.SupabaseAuthClient.SupabaseAuthException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.core.MethodParameter;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for GlobalExceptionHandler.
 * 
 * BUG-006: Verifies optimistic lock exceptions return HTTP 409 Conflict
 * with proper retry semantics instead of 500 Internal Server Error.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(path);
        return request;
    }

    @SuppressWarnings("unused")
    private static class TestController {
        @GetMapping("/test")
        void test(@RequestParam String id) {
        }
    }

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
        MockHttpServletRequest request = request("/api/cars/55/documents");

        // When
        ResponseEntity<ApiErrorResponse> response = handler.handleAccessDenied(ex, request);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError().getCode()).isEqualTo("FORBIDDEN");
        assertThat(response.getBody().getError().getMessage()).isEqualTo("Access Denied");
        assertThat(response.getBody().getError().getPath()).isEqualTo("/api/cars/55/documents");
    }

    @Test
    @DisplayName("Malformed body returns validation envelope")
    void httpMessageNotReadable_returnsValidationEnvelope() {
        ResponseEntity<ApiErrorResponse> response = handler.handleHttpMessageNotReadable(
                new HttpMessageNotReadableException("bad json"),
                request("/api/cars/availability-search"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError().getCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().getError().getPath()).isEqualTo("/api/cars/availability-search");
    }

    @Test
    @DisplayName("Missing request parameter returns validation envelope with details")
    void missingRequestParameter_returnsValidationEnvelope() {
        ResponseEntity<ApiErrorResponse> response = handler.handleMissingRequestParameter(
                new MissingServletRequestParameterException("startTime", "String"),
                request("/api/cars/availability-search"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError().getCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().getError().getDetails()).containsEntry("parameter", "startTime");
    }

    @Test
    @DisplayName("Bean validation returns validation envelope with field map")
    void methodArgumentNotValid_returnsValidationEnvelope() throws Exception {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "location", "must not be blank"));

        Method method = TestController.class.getDeclaredMethod("test", String.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<ApiErrorResponse> response = handler.handleValidationExceptions(
                ex,
                request("/api/cars/availability-search"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError().getCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().getError().getDetails()).containsEntry("location", "must not be blank");
    }

    @Test
    @DisplayName("Type mismatch returns validation envelope with parameter details")
    void methodArgumentTypeMismatch_returnsValidationEnvelope() throws Exception {
        Method method = TestController.class.getDeclaredMethod("test", String.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc",
                Long.class,
                "id",
                parameter,
                new IllegalArgumentException("bad type"));

        ResponseEntity<ApiErrorResponse> response = handler.handleMethodArgumentTypeMismatch(
                ex,
                request("/api/cars/abc"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError().getCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().getError().getDetails()).containsEntry("parameter", "id");
        assertThat(response.getBody().getError().getDetails()).containsEntry("value", "abc");
    }

    // ========== Phase 3: ConstraintViolationException Tests ==========

    @Test
    @DisplayName("ConstraintViolationException returns 400 with VALIDATION_FAILED code")
    void constraintViolationException_returns400WithValidationFailedCode() {
        // Given
        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("registerUser.dto.email");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("Invalid email format");

        Set<ConstraintViolation<?>> violations = new HashSet<>();
        violations.add(violation);
        ConstraintViolationException ex = new ConstraintViolationException("Validation failed", violations);

        // When
        ResponseEntity<Map<String, Object>> response = handler.handleConstraintViolation(ex);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().get("error")).isEqualTo("Validation Error");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> violationList = (List<Map<String, String>>) response.getBody().get("violations");
        assertThat(violationList).hasSize(1);
        assertThat(violationList.get(0).get("field")).isEqualTo("email");
        assertThat(violationList.get(0).get("message")).isEqualTo("Invalid email format");
    }

    // ========== Phase 3: SQLState-Aware DataIntegrity Tests ==========

    @Test
    @DisplayName("Unique violation with registration constraint returns 409 DUPLICATE_REGISTRATION")
    void uniqueViolation_registrationConstraint_returns409DuplicateRegistration() {
        // Given - simulate SQLException with SQLState 23505 and registration table message
        SQLException sqlEx = new SQLException(
                "duplicate key value violates unique constraint \"supabase_user_mapping_pkey\"",
                "23505");

        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "could not execute statement", sqlEx);

        // When
        ResponseEntity<Map<String, Object>> response = handler.handleDataIntegrityViolation(ex);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        // Falls back to message-based classification since plain SQLException has no constraint name
        assertThat(response.getBody().get("code")).isEqualTo("DUPLICATE_REGISTRATION");
    }

    @Test
    @DisplayName("Unique violation with non-registration constraint returns 409 DATA_CONFLICT")
    void uniqueViolation_nonRegistrationConstraint_returns409DataConflict() {
        // Given - simulate SQLException with SQLState 23505 and unrelated message
        SQLException sqlEx = new SQLException(
                "duplicate key value violates unique constraint \"some_other_unique_idx\"",
                "23505");

        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "could not execute statement", sqlEx);

        // When
        ResponseEntity<Map<String, Object>> response = handler.handleDataIntegrityViolation(ex);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("DATA_CONFLICT");
    }

    @Test
    @DisplayName("Non-unique integrity violation returns sanitized 500")
    void nonUniqueIntegrityViolation_returns500() {
        // Given - not 23505 (e.g. foreign key violation 23503)
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "foreign key constraint violation");

        // When
        ResponseEntity<Map<String, Object>> response = handler.handleDataIntegrityViolation(ex);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("correlationId")).asString().startsWith("DB-");
        // Security: no internal details
        assertThat(response.getBody().get("message")).asString()
                .doesNotContain("foreign key");
    }

    @Test
    @DisplayName("Existing specialized handlers (USER_OVERLAP, CAR_UNAVAILABLE, idempotency, document) remain unchanged")
    void existingSpecializedHandlers_remainUnchanged() {
        // USER_OVERLAP
        DataIntegrityViolationException userOverlap = new DataIntegrityViolationException(
                "ERROR: USER_OVERLAP: overlapping bookings");
        assertThat(handler.handleDataIntegrityViolation(userOverlap).getBody().get("code"))
                .isEqualTo("USER_OVERLAP");

        // CAR_UNAVAILABLE
        DataIntegrityViolationException carUnavailable = new DataIntegrityViolationException(
                "ERROR: CAR_UNAVAILABLE: car booked");
        assertThat(handler.handleDataIntegrityViolation(carUnavailable).getBody().get("code"))
                .isEqualTo("CAR_UNAVAILABLE");

        // Idempotency key
        DataIntegrityViolationException idempotency = new DataIntegrityViolationException(
                "duplicate key value violates unique constraint idempotency_key");
        assertThat(handler.handleDataIntegrityViolation(idempotency).getBody().get("code"))
                .isEqualTo("DUPLICATE_BOOKING");

        // Document hash
        DataIntegrityViolationException docHash = new DataIntegrityViolationException(
                "violates unique constraint idx_renter_documents_hash_unique");
        assertThat(handler.handleDataIntegrityViolation(docHash).getBody().get("code"))
                .isEqualTo("DUPLICATE_DOCUMENT");
    }

    // ========== SupabaseAuthException Handler Tests ==========

    @Test
    @DisplayName("SupabaseAuthException with 'already registered' returns 409 DUPLICATE_REGISTRATION")
    void supabaseAuthException_alreadyRegistered_returns409() {
        SupabaseAuthException ex = new SupabaseAuthException("Email already registered");

        ResponseEntity<Map<String, Object>> response = handler.handleSupabaseAuthException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("DUPLICATE_REGISTRATION");
    }

    @Test
    @DisplayName("SupabaseAuthException with 'temporarily unavailable' returns 503 with Retry-After")
    void supabaseAuthException_unavailable_returns503() {
        SupabaseAuthException ex = new SupabaseAuthException(
                "Authentication service temporarily unavailable. Please try again later.");

        ResponseEntity<Map<String, Object>> response = handler.handleSupabaseAuthException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("AUTH_SERVICE_UNAVAILABLE");
        assertThat(response.getBody().get("retryable")).isEqualTo(true);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("30");
    }

    @Test
    @DisplayName("SupabaseAuthException with 'Invalid credentials' returns 401")
    void supabaseAuthException_invalidCredentials_returns401() {
        SupabaseAuthException ex = new SupabaseAuthException("Invalid credentials");

        ResponseEntity<Map<String, Object>> response = handler.handleSupabaseAuthException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("message")).isEqualTo("Invalid email or password");
    }

    @Test
    @DisplayName("SupabaseAuthException with 'Registration failed' returns 502 AUTH_UPSTREAM_ERROR")
    void supabaseAuthException_registrationFailed_returns502() {
        SupabaseAuthException ex = new SupabaseAuthException(
                "Registration failed: could not create local account. Please try again.");

        ResponseEntity<Map<String, Object>> response = handler.handleSupabaseAuthException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("AUTH_UPSTREAM_ERROR");
        assertThat(response.getBody().get("correlationId")).asString().startsWith("AUTH-");
    }

    @Test
    @DisplayName("SupabaseAuthException with unknown message returns sanitized 500")
    void supabaseAuthException_unknown_returns500() {
        SupabaseAuthException ex = new SupabaseAuthException("Something entirely unexpected");

        ResponseEntity<Map<String, Object>> response = handler.handleSupabaseAuthException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("correlationId")).asString().startsWith("AUTH-");
        // Security: internal message NOT leaked
        assertThat(response.getBody().get("message").toString())
                .doesNotContain("Something entirely unexpected");
    }

    // ========== SQLState Null Fallback Tests ==========

    @Test
    @DisplayName("Unique constraint violation with null SQLState but message match returns 409")
    void uniqueConstraintViolation_nullSqlState_messageMatch_returns409() {
        // Simulate a JDBC wrapper that loses SQLState but preserves message
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "ERROR: duplicate key value violates unique constraint \"users_email_key\"");

        ResponseEntity<Map<String, Object>> response = handler.handleDataIntegrityViolation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("DUPLICATE_REGISTRATION");
    }
}

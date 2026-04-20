package org.example.rentoza.booking;

import jakarta.persistence.QueryHint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.QueryHints;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.*;

/**
 * R4: Structural test verifying that findByIdWithLock has a lock timeout hint.
 *
 * <p>This test uses reflection to verify the annotation is present without
 * requiring a database. The actual timeout behavior is best verified with
 * a PostgreSQL integration test using concurrent transactions.
 */
@DisplayName("R4: Lock Timeout Annotation Verification")
class BookingRepositoryLockTimeoutTest {

    @Test
    @DisplayName("R4: findByIdWithLock has @QueryHints with lock timeout")
    void findByIdWithLockShouldHaveLockTimeout() throws Exception {
        Method method = BookingRepository.class.getMethod("findByIdWithLock", Long.class);

        QueryHints queryHints = method.getAnnotation(QueryHints.class);
        assertThat(queryHints)
                .as("findByIdWithLock must have @QueryHints annotation")
                .isNotNull();

        QueryHint[] hints = queryHints.value();
        assertThat(hints).hasSizeGreaterThan(0);

        boolean hasLockTimeout = false;
        for (QueryHint hint : hints) {
            if ("jakarta.persistence.lock.timeout".equals(hint.name())) {
                hasLockTimeout = true;
                int timeoutMs = Integer.parseInt(hint.value());
                assertThat(timeoutMs)
                        .as("Lock timeout should be between 1s and 30s")
                        .isBetween(1000, 30000);
            }
        }

        assertThat(hasLockTimeout)
                .as("findByIdWithLock must have jakarta.persistence.lock.timeout hint")
                .isTrue();
    }
}

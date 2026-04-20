package org.example.rentoza.user.gdpr;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for OPS-GAP-7 (scheduler observability) applied to GDPR deletion scheduler.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Success metrics are incremented on successful deletion</li>
 *   <li>Failure metrics are incremented on failed deletion</li>
 *   <li>Pending deletions gauge reflects current backlog</li>
 *   <li>Mixed success/failure batches report correct counts</li>
 *   <li>Scheduler handles complete batch failure gracefully</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OPS-GAP-7: Retention Scheduler Alerting & Metrics")
class RetentionSchedulerAlertingTest {

    @Mock private UserRepository userRepository;
    @Mock private GdprService gdprService;
    @Mock private SchedulerIdempotencyService lockService;

    private MeterRegistry meterRegistry;
    private GdprDeletionScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new GdprDeletionScheduler(userRepository, gdprService, lockService, meterRegistry);
    }

    @Nested
    @DisplayName("Metric Emission")
    class MetricEmission {

        @Test
        @DisplayName("Success counter increments for each successful deletion")
        void successCounterIncrements() {
            when(lockService.tryAcquireLock(any(), any())).thenReturn(true);
            when(userRepository.findByDeletionScheduledAtBeforeAndDeletedFalse(any()))
                    .thenReturn(List.of(
                            createPendingUser(1L),
                            createPendingUser(2L),
                            createPendingUser(3L)
                    ));

            scheduler.executePendingDeletions();

            assertThat(meterRegistry.counter("gdpr.deletion.executed").count()).isEqualTo(3.0);
            assertThat(meterRegistry.counter("gdpr.deletion.failed").count()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Failure counter increments for each failed deletion")
        void failureCounterIncrements() {
            when(lockService.tryAcquireLock(any(), any())).thenReturn(true);
            when(userRepository.findByDeletionScheduledAtBeforeAndDeletedFalse(any()))
                    .thenReturn(List.of(createPendingUser(1L), createPendingUser(2L)));
            doThrow(new RuntimeException("DB error")).when(gdprService).permanentlyDeleteUser(1L);
            doThrow(new RuntimeException("DB error")).when(gdprService).permanentlyDeleteUser(2L);

            scheduler.executePendingDeletions();

            assertThat(meterRegistry.counter("gdpr.deletion.executed").count()).isEqualTo(0.0);
            assertThat(meterRegistry.counter("gdpr.deletion.failed").count()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("Mixed batch reports correct success and failure counts")
        void mixedBatchCorrectCounts() {
            when(lockService.tryAcquireLock(any(), any())).thenReturn(true);
            when(userRepository.findByDeletionScheduledAtBeforeAndDeletedFalse(any()))
                    .thenReturn(List.of(
                            createPendingUser(1L),
                            createPendingUser(2L),
                            createPendingUser(3L)
                    ));
            doAnswer(invocation -> {
                Long id = invocation.getArgument(0);
                if (id.equals(2L)) {
                    throw new RuntimeException("failed");
                }
                return null;
            }).when(gdprService).permanentlyDeleteUser(any());

            scheduler.executePendingDeletions();

            assertThat(meterRegistry.counter("gdpr.deletion.executed").count()).isEqualTo(2.0);
            assertThat(meterRegistry.counter("gdpr.deletion.failed").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Pending deletions gauge shows backlog size")
        void pendingGaugeShowsBacklog() {
            when(lockService.tryAcquireLock(any(), any())).thenReturn(true);
            when(userRepository.findByDeletionScheduledAtBeforeAndDeletedFalse(any()))
                    .thenReturn(List.of(
                            createPendingUser(1L),
                            createPendingUser(2L),
                            createPendingUser(3L),
                            createPendingUser(4L),
                            createPendingUser(5L)
                    ));

            scheduler.executePendingDeletions();

            assertThat(meterRegistry.get("gdpr.deletion.pending").gauge().value()).isEqualTo(5.0);
        }
    }

    @Nested
    @DisplayName("Error Isolation")
    class ErrorIsolation {

        @Test
        @DisplayName("Individual user failure does not abort batch")
        void individualFailureDoesNotAbortBatch() {
            when(lockService.tryAcquireLock(any(), any())).thenReturn(true);
            when(userRepository.findByDeletionScheduledAtBeforeAndDeletedFalse(any()))
                    .thenReturn(List.of(
                            createPendingUser(1L),
                            createPendingUser(2L),
                            createPendingUser(3L)
                    ));
            doAnswer(invocation -> {
                Long id = invocation.getArgument(0);
                if (id.equals(1L)) {
                    throw new RuntimeException("fail");
                }
                return null;
            }).when(gdprService).permanentlyDeleteUser(any());

            scheduler.executePendingDeletions();

            // User 2 and 3 should still be attempted
            verify(gdprService).permanentlyDeleteUser(1L);
            verify(gdprService).permanentlyDeleteUser(2L);
            verify(gdprService).permanentlyDeleteUser(3L);
        }

        @Test
        @DisplayName("Zero pending users causes no errors")
        void zeroPendingNoErrors() {
            when(lockService.tryAcquireLock(any(), any())).thenReturn(true);
            when(userRepository.findByDeletionScheduledAtBeforeAndDeletedFalse(any()))
                    .thenReturn(List.of());

            scheduler.executePendingDeletions();

            verify(gdprService, never()).permanentlyDeleteUser(any());
            assertThat(meterRegistry.counter("gdpr.deletion.executed").count()).isEqualTo(0.0);
            assertThat(meterRegistry.counter("gdpr.deletion.failed").count()).isEqualTo(0.0);
        }
    }

    private User createPendingUser(Long id) {
        User user = new User();
        user.setId(id);
        user.setDeletionScheduledAt(LocalDateTime.now().minusDays(1));
        user.setDeleted(false);
        return user;
    }
}

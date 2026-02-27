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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for GAP-1 remediation: GDPR deletion executor scheduler.
 *
 * <p>Verifies that the scheduler:
 * <ul>
 *   <li>Acquires distributed lock before processing</li>
 *   <li>Queries users with expired deletion grace period</li>
 *   <li>Calls permanentlyDeleteUser for each result</li>
 *   <li>Emits metrics on success and failure</li>
 *   <li>Handles errors per-user without aborting the batch</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GAP-1: GDPR Deletion Scheduler")
class GdprDeletionSchedulerIntegrationTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private GdprService gdprService;

    @Mock
    private SchedulerIdempotencyService lockService;

    private MeterRegistry meterRegistry;
    private GdprDeletionScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new GdprDeletionScheduler(userRepository, gdprService, lockService, meterRegistry);
    }

    @Nested
    @DisplayName("Lock Acquisition")
    class LockAcquisition {

        @Test
        @DisplayName("GAP-1: Skips execution when lock is held by another instance")
        void skipsWhenLockHeld() {
            when(lockService.tryAcquireLock(any(), any())).thenReturn(false);

            scheduler.executePendingDeletions();

            verify(userRepository, never()).findByDeletionScheduledAtBeforeAndDeletedFalse(any());
            verify(gdprService, never()).permanentlyDeleteUser(any());
        }

        @Test
        @DisplayName("GAP-1: Proceeds when lock is acquired")
        void proceedsWhenLockAcquired() {
            when(lockService.tryAcquireLock(any(), any())).thenReturn(true);
            when(userRepository.findByDeletionScheduledAtBeforeAndDeletedFalse(any()))
                    .thenReturn(Collections.emptyList());

            scheduler.executePendingDeletions();

            verify(userRepository).findByDeletionScheduledAtBeforeAndDeletedFalse(any());
        }
    }

    @Nested
    @DisplayName("Deletion Execution")
    class DeletionExecution {

        @BeforeEach
        void lockAvailable() {
            when(lockService.tryAcquireLock(any(), any())).thenReturn(true);
        }

        @Test
        @DisplayName("GAP-1: No-op when no users pending deletion")
        void noOpWhenNoUsersPending() {
            when(userRepository.findByDeletionScheduledAtBeforeAndDeletedFalse(any()))
                    .thenReturn(Collections.emptyList());

            scheduler.executePendingDeletions();

            verify(gdprService, never()).permanentlyDeleteUser(any());
        }

        @Test
        @DisplayName("GAP-1: Deletes all users with expired grace period")
        void deletesAllExpiredUsers() {
            User user1 = createUserWithPendingDeletion(1L, LocalDateTime.now().minusDays(1));
            User user2 = createUserWithPendingDeletion(2L, LocalDateTime.now().minusDays(5));
            when(userRepository.findByDeletionScheduledAtBeforeAndDeletedFalse(any()))
                    .thenReturn(List.of(user1, user2));

            scheduler.executePendingDeletions();

            verify(gdprService).permanentlyDeleteUser(1L);
            verify(gdprService).permanentlyDeleteUser(2L);

            assertThat(meterRegistry.counter("gdpr.deletion.executed").count()).isEqualTo(2.0);
            assertThat(meterRegistry.counter("gdpr.deletion.failed").count()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("GAP-1: Continues batch when individual deletion fails")
        void continuesOnIndividualFailure() {
            User user1 = createUserWithPendingDeletion(1L, LocalDateTime.now().minusDays(1));
            User user2 = createUserWithPendingDeletion(2L, LocalDateTime.now().minusDays(2));
            User user3 = createUserWithPendingDeletion(3L, LocalDateTime.now().minusDays(3));
            when(userRepository.findByDeletionScheduledAtBeforeAndDeletedFalse(any()))
                    .thenReturn(List.of(user1, user2, user3));
            doAnswer(invocation -> {
                Long id = invocation.getArgument(0);
                if (id.equals(2L)) {
                    throw new RuntimeException("DB error");
                }
                return null;
            }).when(gdprService).permanentlyDeleteUser(any());

            scheduler.executePendingDeletions();

            verify(gdprService).permanentlyDeleteUser(1L);
            verify(gdprService).permanentlyDeleteUser(2L);
            verify(gdprService).permanentlyDeleteUser(3L);

            assertThat(meterRegistry.counter("gdpr.deletion.executed").count()).isEqualTo(2.0);
            assertThat(meterRegistry.counter("gdpr.deletion.failed").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("GAP-1: Queries only non-deleted users before current time")
        void queriesCorrectTimestamp() {
            when(userRepository.findByDeletionScheduledAtBeforeAndDeletedFalse(any()))
                    .thenReturn(Collections.emptyList());

            LocalDateTime before = LocalDateTime.now();
            scheduler.executePendingDeletions();

            ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(userRepository).findByDeletionScheduledAtBeforeAndDeletedFalse(captor.capture());

            assertThat(captor.getValue()).isAfterOrEqualTo(before);
            assertThat(captor.getValue()).isBeforeOrEqualTo(LocalDateTime.now());
        }
    }

    @Nested
    @DisplayName("Metrics and Observability")
    class MetricsAndObservability {

        @Test
        @DisplayName("GAP-1: Pending deletions gauge is updated")
        void pendingDeletionsGaugeUpdated() {
            when(lockService.tryAcquireLock(any(), any())).thenReturn(true);
            User user = createUserWithPendingDeletion(1L, LocalDateTime.now().minusDays(1));
            when(userRepository.findByDeletionScheduledAtBeforeAndDeletedFalse(any()))
                    .thenReturn(List.of(user));

            scheduler.executePendingDeletions();

            assertThat(meterRegistry.get("gdpr.deletion.pending").gauge().value()).isEqualTo(1.0);
        }
    }

    private User createUserWithPendingDeletion(Long id, LocalDateTime scheduledAt) {
        User user = new User();
        user.setId(id);
        user.setDeletionScheduledAt(scheduledAt);
        user.setDeleted(false);
        return user;
    }
}

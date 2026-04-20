package org.example.rentoza.payment;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.admin.service.AdminAlertService;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.cancellation.CancellationRecordRepository;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.scheduler.SchedulerLockStore;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentLifecycleScheduler reconciliation placeholder")
class PaymentLifecycleSchedulerReconciliationTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingPaymentService paymentService;
    @Mock private PaymentProvider paymentProvider;
    @Mock private CancellationRecordRepository cancellationRecordRepository;
    @Mock private DamageClaimRepository damageClaimRepository;
    @Mock private NotificationService notificationService;
    @Mock private SchedulerLockStore schedulerLockStore;
    @Mock private PayoutLedgerRepository payoutLedgerRepository;
    @Mock private PaymentTransactionRepository transactionRepository;
    @Mock private ProviderEventService providerEventService;
    @Mock private SchedulerItemProcessor itemProcessor;
    @Mock private AdminAlertService adminAlertService;
    @Mock private UserRepository userRepository;

    private PaymentLifecycleScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PaymentLifecycleScheduler(
                bookingRepository,
                paymentService,
                paymentProvider,
                cancellationRecordRepository,
                damageClaimRepository,
                notificationService,
                schedulerLockStore,
                payoutLedgerRepository,
                transactionRepository,
                providerEventService,
                itemProcessor,
                adminAlertService,
                userRepository,
                new SimpleMeterRegistry());
        ReflectionTestUtils.setField(scheduler, "reconciliationStaleHours", 2);
    }

    @Test
    @DisplayName("skips reconciliation when lock is held")
    void skipsWhenLockHeld() {
        when(schedulerLockStore.tryAcquireLock(any(), any())).thenReturn(false);

        scheduler.reconcileStaleNonTerminalTransactions();

        verify(transactionRepository, never()).findStaleNonTerminalTransactions(any(Instant.class));
        verify(schedulerLockStore, never()).releaseLock(any());
    }

    @Test
    @DisplayName("queries stale non-terminal transactions when lock acquired")
    void queriesWhenLockAcquired() {
        when(schedulerLockStore.tryAcquireLock(any(), any())).thenReturn(true);
        when(transactionRepository.findStaleNonTerminalTransactions(any(Instant.class))).thenReturn(List.of());

        scheduler.reconcileStaleNonTerminalTransactions();

        verify(transactionRepository).findStaleNonTerminalTransactions(any(Instant.class));
        verify(schedulerLockStore).releaseLock(any());
    }
}

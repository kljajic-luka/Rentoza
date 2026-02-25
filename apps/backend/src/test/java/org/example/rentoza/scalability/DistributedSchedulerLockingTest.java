package org.example.rentoza.scalability;

import org.example.rentoza.booking.BookingScheduler;
import org.example.rentoza.booking.checkout.saga.SagaRecoveryScheduler;
import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G6: Verifies distributed scheduler locking is applied to critical schedulers.
 *
 * <p>Ensures that BookingScheduler and SagaRecoveryScheduler both depend on
 * {@link SchedulerIdempotencyService} for distributed lock coordination,
 * and that lock constants are defined for each scheduled operation.
 */
class DistributedSchedulerLockingTest {

    // ==================== BookingScheduler ====================

    @Test
    @DisplayName("G6: BookingScheduler depends on SchedulerIdempotencyService")
    void bookingScheduler_dependsOnLockService() {
        boolean hasLockServiceParam = Arrays.stream(BookingScheduler.class.getDeclaredConstructors())
                .anyMatch(c -> Arrays.stream(c.getParameterTypes())
                        .anyMatch(SchedulerIdempotencyService.class::isAssignableFrom));

        assertThat(hasLockServiceParam)
                .as("BookingScheduler constructor must accept SchedulerIdempotencyService")
                .isTrue();
    }

    @Test
    @DisplayName("G6: BookingScheduler defines lock constants for expiry and reminder")
    void bookingScheduler_definesLockConstants() throws Exception {
        Field expiryLock = BookingScheduler.class.getDeclaredField("LOCK_EXPIRY");
        assertThat(expiryLock.getType()).isEqualTo(String.class);

        Field reminderLock = BookingScheduler.class.getDeclaredField("LOCK_REMINDER");
        assertThat(reminderLock.getType()).isEqualTo(String.class);
    }

    @Test
    @DisplayName("G6: BookingScheduler autoExpirePendingBookings uses lock pattern")
    void bookingScheduler_autoExpire_usesLockPattern() throws Exception {
        String sourceCode = readClassSource(BookingScheduler.class);

        assertThat(sourceCode)
                .as("autoExpirePendingBookings must call tryAcquireLock")
                .contains("lockService.tryAcquireLock(LOCK_EXPIRY");

        assertThat(sourceCode)
                .as("autoExpirePendingBookings must release lock in finally block")
                .contains("lockService.releaseLock(LOCK_EXPIRY)");
    }

    @Test
    @DisplayName("G6: BookingScheduler sendPendingApprovalReminders uses lock pattern")
    void bookingScheduler_sendReminders_usesLockPattern() throws Exception {
        String sourceCode = readClassSource(BookingScheduler.class);

        assertThat(sourceCode)
                .as("sendPendingApprovalReminders must call tryAcquireLock")
                .contains("lockService.tryAcquireLock(LOCK_REMINDER");

        assertThat(sourceCode)
                .as("sendPendingApprovalReminders must release lock in finally block")
                .contains("lockService.releaseLock(LOCK_REMINDER)");
    }

    // ==================== SagaRecoveryScheduler ====================

    @Test
    @DisplayName("G6: SagaRecoveryScheduler depends on SchedulerIdempotencyService")
    void sagaRecoveryScheduler_dependsOnLockService() {
        boolean hasLockServiceParam = Arrays.stream(SagaRecoveryScheduler.class.getDeclaredConstructors())
                .anyMatch(c -> Arrays.stream(c.getParameterTypes())
                        .anyMatch(SchedulerIdempotencyService.class::isAssignableFrom));

        assertThat(hasLockServiceParam)
                .as("SagaRecoveryScheduler constructor must accept SchedulerIdempotencyService")
                .isTrue();
    }

    @Test
    @DisplayName("G6: SagaRecoveryScheduler defines lock constants for all mutating jobs")
    void sagaRecoveryScheduler_definesLockConstants() throws Exception {
        Field recoverLock = SagaRecoveryScheduler.class.getDeclaredField("LOCK_RECOVER");
        assertThat(recoverLock.getType()).isEqualTo(String.class);

        Field retryLock = SagaRecoveryScheduler.class.getDeclaredField("LOCK_RETRY");
        assertThat(retryLock.getType()).isEqualTo(String.class);

        Field compensateLock = SagaRecoveryScheduler.class.getDeclaredField("LOCK_COMPENSATE");
        assertThat(compensateLock.getType()).isEqualTo(String.class);
    }

    @Test
    @DisplayName("G6: SagaRecoveryScheduler mutating methods all use lock pattern")
    void sagaRecoveryScheduler_mutatingMethods_useLockPattern() throws Exception {
        String sourceCode = readClassSource(SagaRecoveryScheduler.class);

        // recoverStuckSagas
        assertThat(sourceCode)
                .as("recoverStuckSagas must call tryAcquireLock")
                .contains("lockService.tryAcquireLock(LOCK_RECOVER");
        assertThat(sourceCode)
                .as("recoverStuckSagas must release lock in finally")
                .contains("lockService.releaseLock(LOCK_RECOVER)");

        // retryFailedSagas
        assertThat(sourceCode)
                .as("retryFailedSagas must call tryAcquireLock")
                .contains("lockService.tryAcquireLock(LOCK_RETRY");
        assertThat(sourceCode)
                .as("retryFailedSagas must release lock in finally")
                .contains("lockService.releaseLock(LOCK_RETRY)");

        // completeCompensations
        assertThat(sourceCode)
                .as("completeCompensations must call tryAcquireLock")
                .contains("lockService.tryAcquireLock(LOCK_COMPENSATE");
        assertThat(sourceCode)
                .as("completeCompensations must release lock in finally")
                .contains("lockService.releaseLock(LOCK_COMPENSATE)");
    }

    @Test
    @DisplayName("G6: SagaRecoveryScheduler logSagaStatistics is intentionally unlocked (read-only)")
    void sagaRecoveryScheduler_logStats_isUnlocked() throws Exception {
        String sourceCode = readClassSource(SagaRecoveryScheduler.class);

        // logSagaStatistics should NOT acquire a lock (it's read-only)
        // We verify the source code comment documents this intentional choice
        assertThat(sourceCode)
                .as("logSagaStatistics must be documented as intentionally unlocked")
                .contains("Intentionally unlocked");
    }

    // ==================== Helpers ====================

    private String readClassSource(Class<?> clazz) throws Exception {
        String relativePath = "src/main/java/"
                + clazz.getName().replace('.', '/') + ".java";
        java.nio.file.Path sourcePath = java.nio.file.Path.of(
                System.getProperty("user.dir"), relativePath);
        if (!java.nio.file.Files.exists(sourcePath)) {
            sourcePath = java.nio.file.Path.of(relativePath);
        }
        assertThat(sourcePath).as("Source file for %s must exist", clazz.getSimpleName()).exists();
        return java.nio.file.Files.readString(sourcePath);
    }
}

package org.example.rentoza.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserLockoutPolicyTest {

    @Test
    @DisplayName("Lockout activates after threshold and escalates by failed-attempt count")
    void lockoutThresholdAndEscalation_workAsExpected() {
        User user = new User();

        user.incrementFailedLoginAttempts("1.2.3.4");
        user.incrementFailedLoginAttempts("1.2.3.4");
        assertThat(user.isAccountLocked()).isFalse();

        user.incrementFailedLoginAttempts("1.2.3.4");
        assertThat(user.isAccountLocked()).isTrue();
        long lockMinutesAt3 = Duration.between(Instant.now(), user.getLockedUntil()).toMinutes();
        assertThat(lockMinutesAt3).isBetween(4L, 5L);

        user.incrementFailedLoginAttempts("1.2.3.4");
        user.incrementFailedLoginAttempts("1.2.3.4");
        long lockMinutesAt5 = Duration.between(Instant.now(), user.getLockedUntil()).toMinutes();
        assertThat(lockMinutesAt5).isBetween(14L, 15L);
    }

    @Test
    @DisplayName("Lockout ends once lockedUntil is in the past")
    void lockoutEndsWhenTimePasses() {
        User user = new User();
        user.setLockedUntil(Instant.now().minusSeconds(1));

        assertThat(user.isAccountLocked()).isFalse();
    }
}

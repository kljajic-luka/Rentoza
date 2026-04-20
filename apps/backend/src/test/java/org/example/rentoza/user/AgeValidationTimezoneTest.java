package org.example.rentoza.user;

import org.example.rentoza.config.timezone.SerbiaTimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verify that age-related calculations use the Belgrade timezone (Europe/Belgrade)
 * rather than JVM-default {@code LocalDate.now()}.
 *
 * <p>These tests ensure that {@link User#getAge()} and the broader registration /
 * profile age-check paths are anchored to the Serbia business timezone.
 */
@DisplayName("Age validation uses Belgrade timezone")
class AgeValidationTimezoneTest {

    private static final ZoneId BELGRADE = ZoneId.of("Europe/Belgrade");

    // ── User.getAge() ──────────────────────────────────────────────

    @Test
    @DisplayName("getAge() returns correct age based on Belgrade date")
    void getAge_usesBelgradeDateBasis() {
        User user = new User();
        LocalDate dob = SerbiaTimeZone.today().minusYears(25);
        user.setDateOfBirth(dob);

        Integer age = user.getAge();

        assertThat(age).isEqualTo(25);
    }

    @Test
    @DisplayName("getAge() returns null-safe fallback when DOB is null")
    void getAge_fallsBackToLegacyAge_whenDobIsNull() {
        User user = new User();
        user.setAge(30);

        assertThat(user.getAge()).isEqualTo(30);
    }

    @Test
    @DisplayName("getAge() matches Period.between with Belgrade today")
    void getAge_matchesPeriodBetweenWithBelgradeToday() {
        User user = new User();
        LocalDate dob = LocalDate.of(2000, 6, 15);
        user.setDateOfBirth(dob);

        int expected = Period.between(dob, LocalDate.now(BELGRADE)).getYears();

        assertThat(user.getAge()).isEqualTo(expected);
    }

    @Test
    @DisplayName("getAge() handles birthday-boundary correctly in Belgrade timezone")
    void getAge_birthdayBoundary() {
        User user = new User();
        // Set DOB to exactly N years ago in Belgrade timezone
        LocalDate today = SerbiaTimeZone.today();
        LocalDate justTurned21 = today.minusYears(21);
        user.setDateOfBirth(justTurned21);

        assertThat(user.getAge()).isEqualTo(21);

        // One day before turning 21 → still 20
        user.setDateOfBirth(justTurned21.plusDays(1));
        assertThat(user.getAge()).isEqualTo(20);
    }

    // ── meetsMinimumAge() ──────────────────────────────────────────

    @Test
    @DisplayName("meetsAgeRequirement() delegates to getAge() with Belgrade basis")
    void meetsAgeRequirement_usesBelgradeBasis() {
        User user = new User();
        user.setDateOfBirth(SerbiaTimeZone.today().minusYears(21));

        assertThat(user.meetsAgeRequirement(21)).isTrue();
        assertThat(user.meetsAgeRequirement(22)).isFalse();
    }

    // ── SerbiaTimeZone.today() contract ────────────────────────────

    @Test
    @DisplayName("SerbiaTimeZone.today() equals LocalDate.now(Belgrade)")
    void serbiaTimezoneToday_equalsLocalDateNowBelgrade() {
        LocalDate fromUtility = SerbiaTimeZone.today();
        LocalDate direct = LocalDate.now(BELGRADE);

        assertThat(fromUtility).isEqualTo(direct);
    }
}

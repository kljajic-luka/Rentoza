package org.example.rentoza.booking.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive unit tests for TripDurationCalculator.
 * 
 * <p>Tests cover:
 * <ul>
 *   <li>DST-safe duration calculations</li>
 *   <li>DST transition detection</li>
 *   <li>Invalid time validation (DST gaps)</li>
 *   <li>Ambiguous time handling (DST overlaps)</li>
 *   <li>Safe conversions between LocalDateTime and Instant</li>
 * </ul>
 * 
 * <p><b>Phase 3 - Enterprise Hardening</b>
 * 
 * @see TripDurationCalculator
 */
class TripDurationCalculatorTest {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");

    // ========================================================================
    // DURATION CALCULATION TESTS
    // ========================================================================

    @Nested
    @DisplayName("Duration Calculation")
    class DurationCalculationTests {

        @Test
        @DisplayName("Should calculate simple 24-hour duration correctly")
        void shouldCalculateSimple24HourDuration() {
            LocalDateTime start = LocalDateTime.of(2026, 6, 15, 10, 0);
            LocalDateTime end = LocalDateTime.of(2026, 6, 16, 10, 0);

            Duration duration = TripDurationCalculator.calculateActualDuration(start, end);

            assertThat(duration.toHours()).isEqualTo(24);
        }

        @Test
        @DisplayName("Should calculate multi-day duration correctly")
        void shouldCalculateMultiDayDuration() {
            LocalDateTime start = LocalDateTime.of(2026, 6, 1, 9, 0);
            LocalDateTime end = LocalDateTime.of(2026, 6, 8, 9, 0);

            Duration duration = TripDurationCalculator.calculateActualDuration(start, end);

            assertThat(duration.toDays()).isEqualTo(7);
            assertThat(duration.toHours()).isEqualTo(168);
        }

        @Test
        @DisplayName("Should handle fractional hours")
        void shouldHandleFractionalHours() {
            LocalDateTime start = LocalDateTime.of(2026, 6, 15, 10, 0);
            LocalDateTime end = LocalDateTime.of(2026, 6, 16, 10, 30);

            Duration duration = TripDurationCalculator.calculateActualDuration(start, end);

            assertThat(duration.toMinutes()).isEqualTo(24 * 60 + 30);
        }

        @Test
        @DisplayName("Should throw on null start time")
        void shouldThrowOnNullStartTime() {
            LocalDateTime end = LocalDateTime.of(2026, 6, 16, 10, 0);

            assertThatThrownBy(() -> TripDurationCalculator.calculateActualDuration(null, end))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not be null");
        }

        @Test
        @DisplayName("Should throw on null end time")
        void shouldThrowOnNullEndTime() {
            LocalDateTime start = LocalDateTime.of(2026, 6, 15, 10, 0);

            assertThatThrownBy(() -> TripDurationCalculator.calculateActualDuration(start, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not be null");
        }
    }

    // ========================================================================
    // DST SPRING FORWARD TESTS (March - clocks skip ahead)
    // ========================================================================

    @Nested
    @DisplayName("DST Spring Forward (March)")
    class DstSpringForwardTests {

        // Serbia DST 2026: March 29, 02:00 → 03:00 (skip 1 hour)

        @Test
        @DisplayName("Trip spanning spring forward should be 23 actual hours for 24 clock hours")
        void tripSpanningSpringForwardShouldBe23ActualHours() {
            // Book from March 28 22:00 to March 29 22:00
            // Clock shows 24 hours, but 1 hour is "skipped"
            LocalDateTime start = LocalDateTime.of(2026, 3, 28, 22, 0);
            LocalDateTime end = LocalDateTime.of(2026, 3, 29, 22, 0);

            Duration duration = TripDurationCalculator.calculateActualDuration(start, end);

            // Should be 23 actual hours due to DST spring forward
            assertThat(duration.toHours()).isEqualTo(23);
        }

        @Test
        @DisplayName("Trip before spring forward should be normal")
        void tripBeforeSpringForwardShouldBeNormal() {
            LocalDateTime start = LocalDateTime.of(2026, 3, 27, 10, 0);
            LocalDateTime end = LocalDateTime.of(2026, 3, 28, 10, 0);

            Duration duration = TripDurationCalculator.calculateActualDuration(start, end);

            assertThat(duration.toHours()).isEqualTo(24);
        }

        @Test
        @DisplayName("Trip after spring forward should be normal")
        void tripAfterSpringForwardShouldBeNormal() {
            LocalDateTime start = LocalDateTime.of(2026, 3, 30, 10, 0);
            LocalDateTime end = LocalDateTime.of(2026, 3, 31, 10, 0);

            Duration duration = TripDurationCalculator.calculateActualDuration(start, end);

            assertThat(duration.toHours()).isEqualTo(24);
        }

        @Test
        @DisplayName("Should detect trip spans spring forward DST transition")
        void shouldDetectTripSpansSpringForward() {
            LocalDateTime start = LocalDateTime.of(2026, 3, 28, 22, 0);
            LocalDateTime end = LocalDateTime.of(2026, 3, 29, 22, 0);

            boolean spansDst = TripDurationCalculator.spansDstTransition(start, end);

            assertThat(spansDst).isTrue();
        }

        @Test
        @DisplayName("Time during DST gap should be invalid")
        void timeDuringDstGapShouldBeInvalid() {
            // 2:30 AM on March 29, 2026 doesn't exist (clocks skip from 2:00 to 3:00)
            LocalDateTime gapTime = LocalDateTime.of(2026, 3, 29, 2, 30);

            boolean valid = TripDurationCalculator.isValidLocalTime(gapTime);

            // Note: This depends on Java's ZoneRules implementation
            // In practice, the time might be adjusted, so we check behavior
            assertThat(valid).isFalse();
        }
    }

    // ========================================================================
    // DST FALL BACK TESTS (October - clocks repeat)
    // ========================================================================

    @Nested
    @DisplayName("DST Fall Back (October)")
    class DstFallBackTests {

        // Serbia DST 2026: October 25, 03:00 → 02:00 (repeat 1 hour)

        @Test
        @DisplayName("Trip spanning fall back should be 25 actual hours for 24 clock hours")
        void tripSpanningFallBackShouldBe25ActualHours() {
            // Book from October 24 22:00 to October 25 22:00
            // Clock shows 24 hours, but 1 hour is "repeated"
            LocalDateTime start = LocalDateTime.of(2026, 10, 24, 22, 0);
            LocalDateTime end = LocalDateTime.of(2026, 10, 25, 22, 0);

            Duration duration = TripDurationCalculator.calculateActualDuration(start, end);

            // Should be 25 actual hours due to DST fall back
            assertThat(duration.toHours()).isEqualTo(25);
        }

        @Test
        @DisplayName("Should detect trip spans fall back DST transition")
        void shouldDetectTripSpansFallBack() {
            LocalDateTime start = LocalDateTime.of(2026, 10, 24, 22, 0);
            LocalDateTime end = LocalDateTime.of(2026, 10, 25, 22, 0);

            boolean spansDst = TripDurationCalculator.spansDstTransition(start, end);

            assertThat(spansDst).isTrue();
        }

        @Test
        @DisplayName("Time during DST overlap should be ambiguous")
        void timeDuringDstOverlapShouldBeAmbiguous() {
            // 2:30 AM on October 25, 2026 occurs twice
            LocalDateTime overlapTime = LocalDateTime.of(2026, 10, 25, 2, 30);

            boolean ambiguous = TripDurationCalculator.isAmbiguousTime(overlapTime);

            assertThat(ambiguous).isTrue();
        }

        @Test
        @DisplayName("Should resolve ambiguous time to earlier offset (CEST)")
        void shouldResolveToEarlierOffset() {
            LocalDateTime overlapTime = LocalDateTime.of(2026, 10, 25, 2, 30);

            ZonedDateTime resolved = TripDurationCalculator.resolveToEarlierOffset(overlapTime);

            // Earlier offset is +02:00 (CEST - summer time)
            assertThat(resolved.getOffset().getTotalSeconds()).isEqualTo(2 * 3600);
        }

        @Test
        @DisplayName("Should resolve ambiguous time to later offset (CET)")
        void shouldResolveToLaterOffset() {
            LocalDateTime overlapTime = LocalDateTime.of(2026, 10, 25, 2, 30);

            ZonedDateTime resolved = TripDurationCalculator.resolveToLaterOffset(overlapTime);

            // Later offset is +01:00 (CET - winter time)
            assertThat(resolved.getOffset().getTotalSeconds()).isEqualTo(1 * 3600);
        }
    }

    // ========================================================================
    // DST TRANSITION DETECTION TESTS
    // ========================================================================

    @Nested
    @DisplayName("DST Transition Detection")
    class DstTransitionDetectionTests {

        @Test
        @DisplayName("Trip not spanning DST should return false")
        void tripNotSpanningDstShouldReturnFalse() {
            LocalDateTime start = LocalDateTime.of(2026, 6, 15, 10, 0);
            LocalDateTime end = LocalDateTime.of(2026, 6, 16, 10, 0);

            boolean spansDst = TripDurationCalculator.spansDstTransition(start, end);

            assertThat(spansDst).isFalse();
        }

        @Test
        @DisplayName("Should handle null start gracefully")
        void shouldHandleNullStartGracefully() {
            LocalDateTime end = LocalDateTime.of(2026, 6, 16, 10, 0);

            boolean spansDst = TripDurationCalculator.spansDstTransition(null, end);

            assertThat(spansDst).isFalse();
        }

        @Test
        @DisplayName("Should handle null end gracefully")
        void shouldHandleNullEndGracefully() {
            LocalDateTime start = LocalDateTime.of(2026, 6, 15, 10, 0);

            boolean spansDst = TripDurationCalculator.spansDstTransition(start, null);

            assertThat(spansDst).isFalse();
        }

        @Test
        @DisplayName("Should get next DST transition info")
        void shouldGetNextDstTransitionInfo() {
            LocalDateTime beforeDst = LocalDateTime.of(2026, 3, 1, 10, 0);

            var transitionInfo = TripDurationCalculator.getNextDstTransition(beforeDst);

            assertThat(transitionInfo).isNotNull();
            assertThat(transitionInfo.isSpringForward()).isTrue();
            assertThat(transitionInfo.before().getMonth()).isEqualTo(Month.MARCH);
        }
    }

    // ========================================================================
    // TIME VALIDATION TESTS
    // ========================================================================

    @Nested
    @DisplayName("Time Validation")
    class TimeValidationTests {

        @ParameterizedTest
        @DisplayName("Normal times should be valid")
        @CsvSource({
            "2026, 6, 15, 10, 0",
            "2026, 6, 15, 14, 30",
            "2026, 1, 1, 0, 0",
            "2026, 12, 31, 23, 59"
        })
        void normalTimesShouldBeValid(int year, int month, int day, int hour, int minute) {
            LocalDateTime dateTime = LocalDateTime.of(year, month, day, hour, minute);

            boolean valid = TripDurationCalculator.isValidLocalTime(dateTime);

            assertThat(valid).isTrue();
        }

        @Test
        @DisplayName("Null time should be invalid")
        void nullTimeShouldBeInvalid() {
            boolean valid = TripDurationCalculator.isValidLocalTime(null);

            assertThat(valid).isFalse();
        }

        @Test
        @DisplayName("Normal times should not be ambiguous")
        void normalTimesShouldNotBeAmbiguous() {
            LocalDateTime normalTime = LocalDateTime.of(2026, 6, 15, 10, 0);

            boolean ambiguous = TripDurationCalculator.isAmbiguousTime(normalTime);

            assertThat(ambiguous).isFalse();
        }

        @Test
        @DisplayName("Null time should not be ambiguous")
        void nullTimeShouldNotBeAmbiguous() {
            boolean ambiguous = TripDurationCalculator.isAmbiguousTime(null);

            assertThat(ambiguous).isFalse();
        }
    }

    // ========================================================================
    // CONVERSION TESTS
    // ========================================================================

    @Nested
    @DisplayName("Safe Conversions")
    class ConversionTests {

        @Test
        @DisplayName("Should convert LocalDateTime to Instant correctly")
        void shouldConvertLocalDateTimeToInstant() {
            LocalDateTime localDateTime = LocalDateTime.of(2026, 6, 15, 10, 0);

            Instant instant = TripDurationCalculator.toInstant(localDateTime);

            assertThat(instant).isNotNull();
            // Verify round-trip
            LocalDateTime roundTrip = TripDurationCalculator.toSerbiaLocalDateTime(instant);
            assertThat(roundTrip).isEqualTo(localDateTime);
        }

        @Test
        @DisplayName("Should handle null in toInstant")
        void shouldHandleNullInToInstant() {
            Instant instant = TripDurationCalculator.toInstant(null);

            assertThat(instant).isNull();
        }

        @Test
        @DisplayName("Should handle null in toSerbiaLocalDateTime")
        void shouldHandleNullInToSerbiaLocalDateTime() {
            LocalDateTime localDateTime = TripDurationCalculator.toSerbiaLocalDateTime(null);

            assertThat(localDateTime).isNull();
        }

        @Test
        @DisplayName("nowInSerbia should return current Serbia time")
        void nowInSerbiaShouldReturnCurrentSerbiaTime() {
            LocalDateTime nowSerbia = TripDurationCalculator.nowInSerbia();
            LocalDateTime nowExpected = LocalDateTime.now(SERBIA_ZONE);

            // Within 1 second tolerance
            assertThat(Duration.between(nowSerbia, nowExpected).abs().toSeconds()).isLessThan(1);
        }

        @Test
        @DisplayName("nowInstant should return current UTC time")
        void nowInstantShouldReturnCurrentUtcTime() {
            Instant nowInstant = TripDurationCalculator.nowInstant();
            Instant nowExpected = Instant.now();

            // Within 1 second tolerance
            assertThat(Duration.between(nowInstant, nowExpected).abs().toSeconds()).isLessThan(1);
        }
    }

    // ========================================================================
    // HELPER METHOD TESTS
    // ========================================================================

    @Nested
    @DisplayName("Helper Methods")
    class HelperMethodTests {

        @Test
        @DisplayName("calculateActualHours should return whole hours")
        void calculateActualHoursShouldReturnWholeHours() {
            LocalDateTime start = LocalDateTime.of(2026, 6, 15, 10, 0);
            LocalDateTime end = LocalDateTime.of(2026, 6, 16, 10, 30); // 24.5 hours

            long hours = TripDurationCalculator.calculateActualHours(start, end);

            assertThat(hours).isEqualTo(24); // Truncated
        }

        @Test
        @DisplayName("calculateActualDays should return whole days")
        void calculateActualDaysShouldReturnWholeDays() {
            LocalDateTime start = LocalDateTime.of(2026, 6, 15, 10, 0);
            LocalDateTime end = LocalDateTime.of(2026, 6, 22, 20, 0); // 7 days + 10 hours

            long days = TripDurationCalculator.calculateActualDays(start, end);

            assertThat(days).isEqualTo(7); // Truncated
        }
    }

    // ========================================================================
    // DST TRANSITION INFO TESTS
    // ========================================================================

    @Nested
    @DisplayName("DST Transition Info")
    class DstTransitionInfoTests {

        @Test
        @DisplayName("Spring forward info should have positive adjustment")
        void springForwardInfoShouldHavePositiveAdjustment() {
            LocalDateTime beforeSpringForward = LocalDateTime.of(2026, 3, 1, 10, 0);

            var info = TripDurationCalculator.getNextDstTransition(beforeSpringForward);

            assertThat(info).isNotNull();
            assertThat(info.isSpringForward()).isTrue();
            assertThat(info.getAdjustment()).isPositive();
            assertThat(info.getDescription()).contains("unapred");
        }

        @Test
        @DisplayName("Fall back info should have negative adjustment")
        void fallBackInfoShouldHaveNegativeAdjustment() {
            LocalDateTime beforeFallBack = LocalDateTime.of(2026, 9, 1, 10, 0);

            var info = TripDurationCalculator.getNextDstTransition(beforeFallBack);

            assertThat(info).isNotNull();
            assertThat(info.isSpringForward()).isFalse();
            assertThat(info.getAdjustment()).isNegative();
            assertThat(info.getDescription()).contains("unazad");
        }
    }
}

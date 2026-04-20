package org.example.rentoza.booking.checkin;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.config.FeatureFlags;
import org.example.rentoza.user.RenterVerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Instant;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Boundary tests for check-in timing validation (T-61, T-60, T-59).
 *
 * <p>Verifies that both {@code validateCheckInTiming} (submit-time) and
 * {@code validateUploadTiming} (photo-upload-time) enforce the same
 * maxEarlyCheckInHours=1 policy with correct boundary behavior.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CheckInValidationService – Timing boundaries")
class CheckInValidationServiceTimingTest {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");
    private static final LocalDateTime FROZEN_NOW = LocalDateTime.of(2025, 6, 15, 10, 0, 0);
    private static final Instant FROZEN_NOW_INSTANT = FROZEN_NOW.atZone(SERBIA_ZONE).toInstant();

    @Mock private CheckInEventService eventService;
    @Mock private RenterVerificationService renterVerificationService;
    @Mock private FeatureFlags featureFlags;

    private CheckInValidationService service;
    private TimeZone originalDefaultTimeZone;

    @BeforeEach
    void setUp() {
        originalDefaultTimeZone = TimeZone.getDefault();
        service = new CheckInValidationService(
                eventService, renterVerificationService, featureFlags,
                new SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "checkInTimingValidationEnabled", true);
        ReflectionTestUtils.setField(service, "maxEarlyCheckInHours", 1);
    }

    @org.junit.jupiter.api.AfterEach
    void restoreDefaultTimeZone() {
        TimeZone.setDefault(originalDefaultTimeZone);
    }

    private Booking bookingWithStartTime(LocalDateTime startTime) {
        Booking booking = new Booking();
        booking.setStartTime(startTime);
        booking.setStartTimeUtc(startTime.atZone(SERBIA_ZONE).toInstant());
        booking.setId(1L);
        return booking;
    }

    // ====================================================================
    // validateUploadTiming – photo upload gate
    // ====================================================================

    @Nested
    @DisplayName("validateUploadTiming (photo upload gate)")
    class UploadTimingTests {

        @Test
        @DisplayName("T-61: 61 min before trip → BLOCKED (1 min before window opens)")
        void shouldBlockUploadAt61MinutesBeforeTrip() {
            // tripStart = now + 61min → earliest = tripStart - 1h = now + 1min → now < earliest → BLOCKED
            LocalDateTime tripStart = FROZEN_NOW.plusMinutes(61);
            Booking booking = bookingWithStartTime(tripStart);

            try (MockedStatic<Instant> mockedTime = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
                mockedTime.when(Instant::now).thenReturn(FROZEN_NOW_INSTANT);

                assertThatThrownBy(() -> service.validateUploadTiming(booking))
                        .isInstanceOf(CheckInTimingBlockedException.class)
                        .satisfies(ex -> {
                            CheckInTimingBlockedException blocked = (CheckInTimingBlockedException) ex;
                            assertThat(blocked.getMinutesRemaining()).isEqualTo(1);
                            assertThat(blocked.getEarliestAllowedTime()).isEqualTo(tripStart.minusHours(1));
                        });
            }
        }

        @Test
        @DisplayName("T-60: exactly 60 min before trip → ALLOWED (boundary)")
        void shouldAllowUploadAtExactly60MinutesBeforeTrip() {
            // tripStart = now + 60min → earliest = now → now.isBefore(now) = false → ALLOWED
            LocalDateTime tripStart = FROZEN_NOW.plusMinutes(60);
            Booking booking = bookingWithStartTime(tripStart);

            try (MockedStatic<Instant> mockedTime = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
                mockedTime.when(Instant::now).thenReturn(FROZEN_NOW_INSTANT);

                assertThatCode(() -> service.validateUploadTiming(booking))
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("T-59: 59 min before trip → ALLOWED (inside window)")
        void shouldAllowUploadAt59MinutesBeforeTrip() {
            // tripStart = now + 59min → earliest = now - 1min → now is after earliest → ALLOWED
            LocalDateTime tripStart = FROZEN_NOW.plusMinutes(59);
            Booking booking = bookingWithStartTime(tripStart);

            try (MockedStatic<Instant> mockedTime = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
                mockedTime.when(Instant::now).thenReturn(FROZEN_NOW_INSTANT);

                assertThatCode(() -> service.validateUploadTiming(booking))
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("T-180: 3 hours before trip → BLOCKED with correct countdown")
        void shouldBlockUploadAt180MinutesBeforeTripWithCorrectCountdown() {
            LocalDateTime tripStart = FROZEN_NOW.plusHours(3);
            Booking booking = bookingWithStartTime(tripStart);

            try (MockedStatic<Instant> mockedTime = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
                mockedTime.when(Instant::now).thenReturn(FROZEN_NOW_INSTANT);

                assertThatThrownBy(() -> service.validateUploadTiming(booking))
                        .isInstanceOf(CheckInTimingBlockedException.class)
                        .satisfies(ex -> {
                            CheckInTimingBlockedException blocked = (CheckInTimingBlockedException) ex;
                            assertThat(blocked.getMinutesRemaining()).isEqualTo(120);
                        });
            }
        }

        @Test
        @DisplayName("Validation disabled → always allowed")
        void shouldAllowUploadWhenTimingValidationDisabled() {
            ReflectionTestUtils.setField(service, "checkInTimingValidationEnabled", false);
            // 3 hours before trip — would normally be blocked
            Booking booking = bookingWithStartTime(FROZEN_NOW.plusHours(3));

            try (MockedStatic<Instant> mockedTime = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
                mockedTime.when(Instant::now).thenReturn(FROZEN_NOW_INSTANT);

                assertThatCode(() -> service.validateUploadTiming(booking))
                        .doesNotThrowAnyException();
            }
        }
    }

    // ====================================================================
    // validateCheckInTiming – submit-time enforcement
    // ====================================================================

    @Nested
    @DisplayName("validateCheckInTiming (submit-time enforcement)")
    class SubmitTimingTests {

        @Test
        @DisplayName("T-61: 61 min before trip → BLOCKED with IllegalStateException")
        void shouldBlockSubmitAt61MinutesBeforeTrip() {
            LocalDateTime tripStart = FROZEN_NOW.plusMinutes(61);
            Booking booking = bookingWithStartTime(tripStart);

            try (MockedStatic<Instant> mockedTime = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
                mockedTime.when(Instant::now).thenReturn(FROZEN_NOW_INSTANT);

                assertThatThrownBy(() -> service.validateCheckInTiming(booking, 1L, CheckInActorRole.HOST))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Prijem nije dozvoljen");
            }
        }

        @Test
        @DisplayName("T-60: exactly 60 min before trip → ALLOWED")
        void shouldAllowSubmitAtExactly60MinutesBeforeTrip() {
            LocalDateTime tripStart = FROZEN_NOW.plusMinutes(60);
            Booking booking = bookingWithStartTime(tripStart);

            try (MockedStatic<Instant> mockedTime = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
                mockedTime.when(Instant::now).thenReturn(FROZEN_NOW_INSTANT);

                assertThatCode(() -> service.validateCheckInTiming(booking, 1L, CheckInActorRole.HOST))
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("T-59: 59 min before trip → ALLOWED")
        void shouldAllowSubmitAt59MinutesBeforeTrip() {
            LocalDateTime tripStart = FROZEN_NOW.plusMinutes(59);
            Booking booking = bookingWithStartTime(tripStart);

            try (MockedStatic<Instant> mockedTime = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
                mockedTime.when(Instant::now).thenReturn(FROZEN_NOW_INSTANT);

                assertThatCode(() -> service.validateCheckInTiming(booking, 1L, CheckInActorRole.HOST))
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("Timing enforcement is stable even when JVM default timezone is non-Belgrade")
        void shouldBeJvmTimezoneIndependent() {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));

            LocalDateTime tripStart = FROZEN_NOW.plusMinutes(61);
            Booking booking = bookingWithStartTime(tripStart);

            try (MockedStatic<Instant> mockedTime = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
                mockedTime.when(Instant::now).thenReturn(FROZEN_NOW_INSTANT);

                assertThatThrownBy(() -> service.validateUploadTiming(booking))
                        .isInstanceOf(CheckInTimingBlockedException.class)
                        .satisfies(ex -> {
                            CheckInTimingBlockedException blocked = (CheckInTimingBlockedException) ex;
                            assertThat(blocked.getMinutesRemaining()).isEqualTo(1);
                        });
            }
        }
    }
}

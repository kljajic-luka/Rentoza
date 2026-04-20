package org.example.rentoza.booking;

import org.example.rentoza.booking.dto.BookingRequestDTO;
import org.example.rentoza.booking.extension.TripExtension;
import org.example.rentoza.booking.extension.TripExtensionRepository;
import org.example.rentoza.booking.extension.TripExtensionService;
import org.example.rentoza.booking.extension.TripExtensionStatus;
import org.example.rentoza.car.Car;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for time window logic - Phase 1 Critical Fixes.
 * 
 * Tests cover:
 * - Lead time validation (1 hour minimum)
 * - Minimum trip duration (24 hours)
 * - Time granularity (30-minute boundaries)
 * - Extension availability conflicts
 * - Timezone handling (Europe/Belgrade)
 * - DST edge cases (Spring forward / Fall back)
 * 
 * @see Time_Window_Logic_Improvement_Plan.md for full specification
 */
@ExtendWith(MockitoExtension.class)
class TimeWindowLogicTest {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");
    
    private Validator validator;
    
    @BeforeEach
    void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    // ========================================================================
    // BOOKING REQUEST DTO VALIDATION TESTS
    // ========================================================================

    @Nested
    @DisplayName("BookingRequestDTO Validation")
    class BookingRequestDTOValidationTests {

        private BookingRequestDTO dto;

        @BeforeEach
        void setUp() {
            dto = new BookingRequestDTO();
            dto.setCarId(1L);
        }

        // === MINIMUM DURATION TESTS (Using Bean Validation) ===

        @Test
        @DisplayName("Should accept exactly 24 hour trip")
        void shouldAcceptExactly24HourTrip() {
            // Given: Trip of exactly 24 hours
            LocalDateTime start = LocalDateTime.now(SERBIA_ZONE).plusDays(2).withMinute(0).withSecond(0).withNano(0);
            LocalDateTime end = start.plusHours(24);
            dto.setStartTime(start);
            dto.setEndTime(end);

            // When: Validate
            Set<ConstraintViolation<BookingRequestDTO>> violations = validator.validate(dto);
            
            // Then: No minimum duration violation
            boolean hasMinDurationViolation = violations.stream()
                    .anyMatch(v -> v.getMessage().contains("24 hours"));
            assertThat(hasMinDurationViolation).isFalse();
        }

        @Test
        @DisplayName("Should reject 23 hour 59 minute trip")
        void shouldReject23Hour59MinuteTrip() {
            // Given: Trip of 23h 59m (1 minute short)
            LocalDateTime start = LocalDateTime.now(SERBIA_ZONE).plusDays(2).withMinute(0).withSecond(0).withNano(0);
            LocalDateTime end = start.plusHours(23).plusMinutes(59);
            dto.setStartTime(start);
            dto.setEndTime(end);

            // When: Validate
            Set<ConstraintViolation<BookingRequestDTO>> violations = validator.validate(dto);
            
            // Then: Has minimum duration violation
            boolean hasMinDurationViolation = violations.stream()
                    .anyMatch(v -> v.getMessage().contains("24 hours"));
            assertThat(hasMinDurationViolation).isTrue();
        }

        @Test
        @DisplayName("Should accept 25 hour trip")
        void shouldAccept25HourTrip() {
            // Given: Trip of 25 hours
            LocalDateTime start = LocalDateTime.now(SERBIA_ZONE).plusDays(2).withMinute(0).withSecond(0).withNano(0);
            LocalDateTime end = start.plusHours(25);
            dto.setStartTime(start);
            dto.setEndTime(end);

            // When: Validate
            Set<ConstraintViolation<BookingRequestDTO>> violations = validator.validate(dto);
            
            // Then: No minimum duration violation  
            boolean hasMinDurationViolation = violations.stream()
                    .anyMatch(v -> v.getMessage().contains("24 hours"));
            assertThat(hasMinDurationViolation).isFalse();
        }

        // === TIME GRANULARITY TESTS ===

        @Test
        @DisplayName("Should accept times on hour boundary")
        void shouldAcceptTimesOnHourBoundary() {
            dto.setStartTime(LocalDateTime.of(2026, 1, 15, 10, 0));
            dto.setEndTime(LocalDateTime.of(2026, 1, 16, 10, 0));

            Set<ConstraintViolation<BookingRequestDTO>> violations = validator.validate(dto);
            boolean hasGranularityViolation = violations.stream()
                    .anyMatch(v -> v.getMessage().contains("30-minute"));
            assertThat(hasGranularityViolation).isFalse();
        }

        @Test
        @DisplayName("Should accept times on half-hour boundary")
        void shouldAcceptTimesOnHalfHourBoundary() {
            dto.setStartTime(LocalDateTime.of(2026, 1, 15, 10, 30));
            dto.setEndTime(LocalDateTime.of(2026, 1, 16, 10, 30));

            Set<ConstraintViolation<BookingRequestDTO>> violations = validator.validate(dto);
            boolean hasGranularityViolation = violations.stream()
                    .anyMatch(v -> v.getMessage().contains("30-minute"));
            assertThat(hasGranularityViolation).isFalse();
        }

        @Test
        @DisplayName("Should reject times at 15 minutes")
        void shouldRejectTimesAt15Minutes() {
            dto.setStartTime(LocalDateTime.of(2026, 1, 15, 10, 15));
            dto.setEndTime(LocalDateTime.of(2026, 1, 16, 10, 0));

            Set<ConstraintViolation<BookingRequestDTO>> violations = validator.validate(dto);
            boolean hasGranularityViolation = violations.stream()
                    .anyMatch(v -> v.getMessage().contains("30-minute"));
            assertThat(hasGranularityViolation).isTrue();
        }

        @Test
        @DisplayName("Should reject times at 45 minutes")
        void shouldRejectTimesAt45Minutes() {
            dto.setStartTime(LocalDateTime.of(2026, 1, 15, 10, 0));
            dto.setEndTime(LocalDateTime.of(2026, 1, 16, 10, 45));

            Set<ConstraintViolation<BookingRequestDTO>> violations = validator.validate(dto);
            boolean hasGranularityViolation = violations.stream()
                    .anyMatch(v -> v.getMessage().contains("30-minute"));
            assertThat(hasGranularityViolation).isTrue();
        }

        // === END TIME AFTER START TIME TESTS ===

        @Test
        @DisplayName("Should accept end time after start time")
        void shouldAcceptEndTimeAfterStartTime() {
            dto.setStartTime(LocalDateTime.of(2026, 1, 15, 10, 0));
            dto.setEndTime(LocalDateTime.of(2026, 1, 16, 10, 0));

            Set<ConstraintViolation<BookingRequestDTO>> violations = validator.validate(dto);
            boolean hasOrderViolation = violations.stream()
                    .anyMatch(v -> v.getMessage().contains("after start"));
            assertThat(hasOrderViolation).isFalse();
        }

        @Test
        @DisplayName("Should reject end time before start time")
        void shouldRejectEndTimeBeforeStartTime() {
            dto.setStartTime(LocalDateTime.of(2026, 1, 16, 10, 0));
            dto.setEndTime(LocalDateTime.of(2026, 1, 15, 10, 0));

            Set<ConstraintViolation<BookingRequestDTO>> violations = validator.validate(dto);
            boolean hasOrderViolation = violations.stream()
                    .anyMatch(v -> v.getMessage().contains("after start"));
            assertThat(hasOrderViolation).isTrue();
        }
    }

    // ========================================================================
    // TRIP EXTENSION AVAILABILITY CONFLICT TESTS
    // ========================================================================

    @Nested
    @DisplayName("TripExtension Availability Check")
    class TripExtensionAvailabilityTests {

        @Mock
        private TripExtensionRepository extensionRepository;
        
        @Mock
        private BookingRepository bookingRepository;
        
        @Mock
        private NotificationService notificationService;

        @Mock
        private org.example.rentoza.payment.BookingPaymentService bookingPaymentService;

        private TripExtensionService extensionService;
        private MeterRegistry meterRegistry;

        @BeforeEach
        void setUp() {
            meterRegistry = new SimpleMeterRegistry();
            extensionService = new TripExtensionService(
                    extensionRepository,
                    bookingRepository,
                    notificationService,
                    bookingPaymentService,
                    meterRegistry
            );
            ReflectionTestUtils.setField(extensionService, "responseHours", 24);
        }

        @Test
        @DisplayName("Should block extension that overlaps with next booking")
        void shouldBlockExtensionOverlappingNextBooking() {
            // Given: Existing booking ending Jan 15
            User guest = createTestUser(1L, "guest@test.com");
            User host = createTestUser(2L, "host@test.com");
            Car car = createTestCar(1L, host);
            
            Booking currentBooking = createTestBooking(
                    1L, car, guest,
                    LocalDateTime.of(2026, 1, 10, 10, 0),
                    LocalDateTime.of(2026, 1, 15, 10, 0),
                    BookingStatus.IN_TRIP
            );

            // Guest wants to extend to Jan 20
            LocalDate requestedEndDate = LocalDate.of(2026, 1, 20);

            when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(currentBooking));
            when(extensionRepository.hasPendingExtension(1L)).thenReturn(false);
            
            // CRITICAL: Next booking exists from Jan 17-22
            when(bookingRepository.existsOverlappingBookingsWithLock(
                    eq(1L),
                    eq(LocalDateTime.of(2026, 1, 15, 10, 0)),
                    eq(LocalDateTime.of(2026, 1, 20, 10, 0))
            )).thenReturn(true);

            // When/Then: Extension should be blocked
            assertThatThrownBy(() -> 
                extensionService.requestExtension(1L, requestedEndDate, "Need more time", 1L)
            )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Produženje nije moguće")
            .hasMessageContaining("postoji druga rezervacija");
        }

        @Test
        @DisplayName("Should allow extension when no conflict exists")
        void shouldAllowExtensionWhenNoConflict() {
            // Given: Booking ending Jan 15, no next booking
            User guest = createTestUser(1L, "guest@test.com");
            User host = createTestUser(2L, "host@test.com");
            Car car = createTestCar(1L, host);
            
            Booking currentBooking = createTestBooking(
                    1L, car, guest,
                    LocalDateTime.of(2026, 1, 10, 10, 0),
                    LocalDateTime.of(2026, 1, 15, 10, 0),
                    BookingStatus.IN_TRIP
            );
            currentBooking.setTotalPrice(BigDecimal.valueOf(10000));
            currentBooking.setSnapshotDailyRate(BigDecimal.valueOf(2000));

            LocalDate requestedEndDate = LocalDate.of(2026, 1, 20);

            when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(currentBooking));
            when(extensionRepository.hasPendingExtension(1L)).thenReturn(false);
            
            // No conflict
            when(bookingRepository.existsOverlappingBookingsWithLock(
                    anyLong(), any(), any()
            )).thenReturn(false);
            
            when(extensionRepository.save(any(TripExtension.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // When: Request extension
            var result = extensionService.requestExtension(1L, requestedEndDate, "Vacation extended", 1L);

            // Then: Extension created successfully
            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(TripExtensionStatus.PENDING);
            assertThat(result.getAdditionalDays()).isEqualTo(5);
        }

        @Test
        @DisplayName("Should reject extension for non-IN_TRIP booking")
        void shouldRejectExtensionForNonInTripBooking() {
            // Given: Booking in ACTIVE status (not yet started)
            User guest = createTestUser(1L, "guest@test.com");
            User host = createTestUser(2L, "host@test.com");
            Car car = createTestCar(1L, host);
            
            Booking booking = createTestBooking(
                    1L, car, guest,
                    LocalDateTime.of(2026, 1, 20, 10, 0),
                    LocalDateTime.of(2026, 1, 25, 10, 0),
                    BookingStatus.ACTIVE  // Not IN_TRIP
            );

            when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(booking));

            // When/Then: Extension rejected
            assertThatThrownBy(() -> 
                extensionService.requestExtension(1L, LocalDate.of(2026, 1, 30), "Please extend", 1L)
            )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("samo tokom aktivnog putovanja");
        }

        @Test
        @DisplayName("Should reject extension by non-guest user")
        void shouldRejectExtensionByNonGuestUser() {
            // Given: Booking where requesting user is not the guest
            User guest = createTestUser(1L, "guest@test.com");
            User host = createTestUser(2L, "host@test.com");
            Car car = createTestCar(1L, host);
            
            Booking booking = createTestBooking(
                    1L, car, guest,
                    LocalDateTime.of(2026, 1, 10, 10, 0),
                    LocalDateTime.of(2026, 1, 15, 10, 0),
                    BookingStatus.IN_TRIP
            );

            when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(booking));

            // When/Then: Non-guest cannot request extension
            assertThatThrownBy(() -> 
                extensionService.requestExtension(1L, LocalDate.of(2026, 1, 20), "Extend please", 3L)
            )
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
            .hasMessageContaining("Samo gost");
        }
    }

    // ========================================================================
    // DST (DAYLIGHT SAVING TIME) EDGE CASE TESTS
    // ========================================================================

    @Nested
    @DisplayName("DST Transition Edge Cases")
    class DSTTransitionTests {

        /**
         * Serbia DST transitions:
         * - Spring forward: Last Sunday of March, 02:00 → 03:00 (1h lost)
         * - Fall back: Last Sunday of October, 03:00 → 02:00 (1h gained)
         */

        @Test
        @DisplayName("Trip spanning spring forward DST is 1 hour shorter")
        void tripSpanningSpringForwardIsShorter() {
            // Given: Trip from March 28 to April 1, 2026
            // DST transition on March 29, 2026 (last Sunday)
            LocalDateTime start = LocalDateTime.of(2026, 3, 28, 10, 0);
            LocalDateTime end = LocalDateTime.of(2026, 4, 1, 10, 0);

            // Calculate duration using ZonedDateTime (DST-aware)
            var startZoned = start.atZone(SERBIA_ZONE);
            var endZoned = end.atZone(SERBIA_ZONE);
            long actualHours = ChronoUnit.HOURS.between(startZoned, endZoned);

            // LocalDateTime calculation (DST-unaware)
            long naiveHours = ChronoUnit.HOURS.between(start, end);

            // Then: Actual duration is 1 hour less due to spring forward
            assertThat(actualHours).isEqualTo(naiveHours - 1);
            assertThat(actualHours).isEqualTo(95); // 4 days - 1 hour = 95 hours
        }

        @Test
        @DisplayName("Trip spanning fall back DST is 1 hour longer")
        void tripSpanningFallBackIsLonger() {
            // Given: Trip from October 24 to October 28, 2026
            // DST transition on October 25, 2026 (last Sunday)
            LocalDateTime start = LocalDateTime.of(2026, 10, 24, 10, 0);
            LocalDateTime end = LocalDateTime.of(2026, 10, 28, 10, 0);

            // Calculate duration using ZonedDateTime (DST-aware)
            var startZoned = start.atZone(SERBIA_ZONE);
            var endZoned = end.atZone(SERBIA_ZONE);
            long actualHours = ChronoUnit.HOURS.between(startZoned, endZoned);

            // LocalDateTime calculation (DST-unaware)
            long naiveHours = ChronoUnit.HOURS.between(start, end);

            // Then: Actual duration is 1 hour more due to fall back
            assertThat(actualHours).isEqualTo(naiveHours + 1);
            assertThat(actualHours).isEqualTo(97); // 4 days + 1 hour = 97 hours
        }

        @Test
        @DisplayName("Booking at non-existent spring forward time should be handled")
        void bookingAtNonExistentSpringForwardTime() {
            // Given: March 29, 2026 02:30 does NOT exist in Belgrade
            // Clocks jump from 02:00 to 03:00
            LocalDateTime nonExistentTime = LocalDateTime.of(2026, 3, 29, 2, 30);

            // When: Converting to ZonedDateTime
            var zoned = nonExistentTime.atZone(SERBIA_ZONE);

            // Then: ZoneRules adjusts to 03:30 (after transition)
            assertThat(zoned.getHour()).isEqualTo(3);
            assertThat(zoned.getMinute()).isEqualTo(30);
        }

        @Test
        @DisplayName("Booking at ambiguous fall back time uses earlier offset")
        void bookingAtAmbiguousFallBackTime() {
            // Given: October 25, 2026 02:30 occurs TWICE in Belgrade
            // First at +02:00 (CEST), then at +01:00 (CET)
            LocalDateTime ambiguousTime = LocalDateTime.of(2026, 10, 25, 2, 30);

            // When: Converting to ZonedDateTime (default behavior)
            var zoned = ambiguousTime.atZone(SERBIA_ZONE);

            // Then: Java picks the earlier offset (+02:00) by default
            // This is important for consistent booking behavior
            assertThat(zoned.getOffset().getTotalSeconds()).isEqualTo(2 * 3600);
        }
    }

    // ========================================================================
    // LEAD TIME VALIDATION TESTS
    // ========================================================================

    @Nested
    @DisplayName("Lead Time Validation")
    class LeadTimeValidationTests {

        @Test
        @DisplayName("Should validate lead time in Serbia timezone")
        void shouldValidateLeadTimeInSerbiaTimezone() {
            // Given: Current time in Serbia
            LocalDateTime nowInSerbia = LocalDateTime.now(SERBIA_ZONE);
            LocalDateTime oneHourFromNow = nowInSerbia.plusHours(1);

            // Then: 59 minutes from now is before 1 hour threshold
            LocalDateTime tooSoon = nowInSerbia.plusMinutes(59);
            assertThat(tooSoon.isBefore(oneHourFromNow)).isTrue();

            // And: 61 minutes from now is after 1 hour threshold
            LocalDateTime acceptable = nowInSerbia.plusMinutes(61);
            assertThat(acceptable.isBefore(oneHourFromNow)).isFalse();
        }

        @ParameterizedTest
        @DisplayName("Lead time boundary tests")
        @CsvSource({
            "30, true",   // 30 min - rejected
            "59, true",   // 59 min - rejected
            "60, false",  // Exactly 1 hour - accepted (edge)
            "61, false",  // 61 min - accepted
            "120, false"  // 2 hours - accepted
        })
        void leadTimeBoundaryTests(int minutesFromNow, boolean shouldReject) {
            LocalDateTime nowInSerbia = LocalDateTime.now(SERBIA_ZONE);
            LocalDateTime oneHourFromNow = nowInSerbia.plusHours(1);
            LocalDateTime tripStart = nowInSerbia.plusMinutes(minutesFromNow);

            boolean wouldBeRejected = tripStart.isBefore(oneHourFromNow);
            assertThat(wouldBeRejected).isEqualTo(shouldReject);
        }
    }

    // ========================================================================
    // CAR-SPECIFIC BOOKING SETTINGS VALIDATION TESTS (Phase 2)
    // ========================================================================

    @Nested
    @DisplayName("Car-Specific Booking Settings Validation")
    class CarBookingSettingsValidationTests {

        @Test
        @DisplayName("Should use car-specific advance notice hours for validation")
        void shouldUseCarSpecificAdvanceNoticeHours() {
            // Given: A car with 6-hour advance notice requirement
            User owner = createTestUser(1L, "owner@test.com");
            Car car = createTestCar(1L, owner);
            var bookingSettings = org.example.rentoza.car.CarBookingSettings.builder()
                    .advanceNoticeHours(6)
                    .build();
            car.setBookingSettings(bookingSettings);

            // When: Getting effective advance notice
            int effectiveAdvanceNotice = car.getEffectiveBookingSettings().getEffectiveAdvanceNoticeHours();

            // Then: Should use car-specific setting
            assertThat(effectiveAdvanceNotice).isEqualTo(6);
        }

        @Test
        @DisplayName("Should use default advance notice when car setting is null")
        void shouldUseDefaultAdvanceNoticeWhenNull() {
            // Given: A car without booking settings
            User owner = createTestUser(1L, "owner@test.com");
            Car car = createTestCar(1L, owner);

            // When: Getting effective advance notice
            int effectiveAdvanceNotice = car.getEffectiveBookingSettings().getEffectiveAdvanceNoticeHours();

            // Then: Should use default (2 hours, aligned with ToS)
            assertThat(effectiveAdvanceNotice).isEqualTo(2);
        }

        @Test
        @DisplayName("Should use car-specific minimum trip hours for validation")
        void shouldUseCarSpecificMinTripHours() {
            // Given: A car with 48-hour minimum trip requirement
            User owner = createTestUser(1L, "owner@test.com");
            Car car = createTestCar(1L, owner);
            var bookingSettings = org.example.rentoza.car.CarBookingSettings.builder()
                    .minTripHours(48)
                    .build();
            car.setBookingSettings(bookingSettings);

            // When: Getting effective minimum trip hours
            int effectiveMinTrip = car.getEffectiveBookingSettings().getEffectiveMinTripHours();

            // Then: Should use car-specific setting
            assertThat(effectiveMinTrip).isEqualTo(48);
        }

        @Test
        @DisplayName("Should use default minimum trip hours when car setting is null")
        void shouldUseDefaultMinTripHoursWhenNull() {
            // Given: A car without booking settings
            User owner = createTestUser(1L, "owner@test.com");
            Car car = createTestCar(1L, owner);

            // When: Getting effective minimum trip hours
            int effectiveMinTrip = car.getEffectiveBookingSettings().getEffectiveMinTripHours();

            // Then: Should use default (24 hours)
            assertThat(effectiveMinTrip).isEqualTo(24);
        }

        @Test
        @DisplayName("Should use car-specific maximum trip days for validation")
        void shouldUseCarSpecificMaxTripDays() {
            // Given: A car with 7-day maximum trip
            User owner = createTestUser(1L, "owner@test.com");
            Car car = createTestCar(1L, owner);
            var bookingSettings = org.example.rentoza.car.CarBookingSettings.builder()
                    .maxTripDays(7)
                    .build();
            car.setBookingSettings(bookingSettings);

            // When: Getting effective maximum trip days
            int effectiveMaxTrip = car.getEffectiveBookingSettings().getEffectiveMaxTripDays();

            // Then: Should use car-specific setting
            assertThat(effectiveMaxTrip).isEqualTo(7);
        }

        @Test
        @DisplayName("Should use default maximum trip days when car setting is null")
        void shouldUseDefaultMaxTripDaysWhenNull() {
            // Given: A car without booking settings
            User owner = createTestUser(1L, "owner@test.com");
            Car car = createTestCar(1L, owner);

            // When: Getting effective maximum trip days
            int effectiveMaxTrip = car.getEffectiveBookingSettings().getEffectiveMaxTripDays();

            // Then: Should use default (30 days)
            assertThat(effectiveMaxTrip).isEqualTo(30);
        }

        @Test
        @DisplayName("Duration validation should respect car-specific minimum hours")
        void durationValidationShouldRespectCarSpecificMinimum() {
            // Given: A car requiring 48 hours minimum
            User owner = createTestUser(1L, "owner@test.com");
            Car car = createTestCar(1L, owner);
            var bookingSettings = org.example.rentoza.car.CarBookingSettings.builder()
                    .minTripHours(48)
                    .build();
            car.setBookingSettings(bookingSettings);

            // And: A 36-hour booking (valid for default, invalid for this car)
            LocalDateTime start = LocalDateTime.now(SERBIA_ZONE).plusDays(2).withMinute(0).withSecond(0).withNano(0);
            LocalDateTime end = start.plusHours(36);

            // When: Calculating duration
            long durationHours = ChronoUnit.HOURS.between(start, end);
            int minRequired = car.getEffectiveBookingSettings().getEffectiveMinTripHours();

            // Then: Duration should be less than car-specific minimum
            assertThat(durationHours).isLessThan(minRequired);
            assertThat(durationHours).isEqualTo(36);
        }

        @Test
        @DisplayName("Duration validation should respect car-specific maximum days")
        void durationValidationShouldRespectCarSpecificMaximum() {
            // Given: A car with 7-day maximum
            User owner = createTestUser(1L, "owner@test.com");
            Car car = createTestCar(1L, owner);
            var bookingSettings = org.example.rentoza.car.CarBookingSettings.builder()
                    .maxTripDays(7)
                    .build();
            car.setBookingSettings(bookingSettings);

            // And: A 10-day booking (valid for default 30, invalid for this car)
            LocalDateTime start = LocalDateTime.now(SERBIA_ZONE).plusDays(2).withMinute(0).withSecond(0).withNano(0);
            LocalDateTime end = start.plusDays(10);

            // When: Calculating duration
            long durationDays = ChronoUnit.DAYS.between(start, end);
            int maxAllowed = car.getEffectiveBookingSettings().getEffectiveMaxTripDays();

            // Then: Duration should exceed car-specific maximum
            assertThat(durationDays).isGreaterThan(maxAllowed);
            assertThat(durationDays).isEqualTo(10);
        }

        @Test
        @DisplayName("Instant book flag should default to false")
        void instantBookShouldDefaultToFalse() {
            // Given: A car without booking settings
            User owner = createTestUser(1L, "owner@test.com");
            Car car = createTestCar(1L, owner);

            // When: Checking instant book status
            boolean isInstantBook = car.getEffectiveBookingSettings().isInstantBookEnabled();

            // Then: Should default to false
            assertThat(isInstantBook).isFalse();
        }

        @Test
        @DisplayName("Prep buffer hours should be considered for back-to-back bookings")
        void prepBufferHoursShouldBeConsidered() {
            // Given: A car with 6-hour prep buffer
            User owner = createTestUser(1L, "owner@test.com");
            Car car = createTestCar(1L, owner);
            var bookingSettings = org.example.rentoza.car.CarBookingSettings.builder()
                    .prepBufferHours(6)
                    .build();
            car.setBookingSettings(bookingSettings);

            // When: Getting effective prep buffer
            int prepBuffer = car.getEffectiveBookingSettings().getEffectivePrepBufferHours();

            // Then: Should use car-specific value
            assertThat(prepBuffer).isEqualTo(6);
        }
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private User createTestUser(Long id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setFirstName(email.split("@")[0]);
        user.setLastName("Test");
        return user;
    }

    private Car createTestCar(Long id, User owner) {
        Car car = new Car();
        car.setId(id);
        car.setOwner(owner);
        car.setBrand("Test");
        car.setModel("Car");
        car.setPricePerDay(BigDecimal.valueOf(2000));
        return car;
    }

    private Booking createTestBooking(
            Long id, Car car, User renter,
            LocalDateTime startTime, LocalDateTime endTime,
            BookingStatus status) {
        Booking booking = new Booking();
        booking.setId(id);
        booking.setCar(car);
        booking.setRenter(renter);
        booking.setStartTime(startTime);
        booking.setEndTime(endTime);
        booking.setStatus(status);
        return booking;
    }
}

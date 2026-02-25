package org.example.rentoza.booking.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for W1: Maximum booking duration validation (30 days).
 */
class BookingRequestDTOMaxDurationTest {

    private static final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    private BookingRequestDTO createDto(LocalDateTime start, LocalDateTime end) {
        BookingRequestDTO dto = new BookingRequestDTO();
        dto.setCarId(1L);
        dto.setStartTime(start);
        dto.setEndTime(end);
        dto.setPaymentMethodId("mock_default");
        return dto;
    }

    @Test
    @DisplayName("W1: 30-day booking is accepted")
    void thirtyDayBooking_isAccepted() {
        LocalDateTime start = LocalDateTime.of(2026, 6, 1, 10, 0);
        LocalDateTime end = start.plusDays(30);
        BookingRequestDTO dto = createDto(start, end);

        Set<String> messages = validator.validate(dto).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());

        assertThat(messages).doesNotContain("Maximum rental duration is 30 days");
    }

    @Test
    @DisplayName("W1: 31-day booking is rejected")
    void thirtyOneDayBooking_isRejected() {
        LocalDateTime start = LocalDateTime.of(2026, 6, 1, 10, 0);
        LocalDateTime end = start.plusDays(31);
        BookingRequestDTO dto = createDto(start, end);

        Set<String> messages = validator.validate(dto).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());

        assertThat(messages).contains("Maximum rental duration is 30 days");
    }

    @Test
    @DisplayName("W1: 90-day booking is rejected")
    void ninetyDayBooking_isRejected() {
        LocalDateTime start = LocalDateTime.of(2026, 6, 1, 10, 0);
        LocalDateTime end = start.plusDays(90);
        BookingRequestDTO dto = createDto(start, end);

        Set<String> messages = validator.validate(dto).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());

        assertThat(messages).contains("Maximum rental duration is 30 days");
    }

    @Test
    @DisplayName("W1: 2-day booking passes max duration check")
    void twoDayBooking_passesMaxDurationCheck() {
        LocalDateTime start = LocalDateTime.of(2026, 6, 1, 10, 0);
        LocalDateTime end = start.plusDays(2);
        BookingRequestDTO dto = createDto(start, end);

        Set<String> messages = validator.validate(dto).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());

        assertThat(messages).doesNotContain("Maximum rental duration is 30 days");
    }
}

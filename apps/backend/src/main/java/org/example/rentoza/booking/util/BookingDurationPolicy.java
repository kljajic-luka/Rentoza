package org.example.rentoza.booking.util;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Canonical trip duration policy for booking/search/cancellation flows.
 */
public final class BookingDurationPolicy {

    private BookingDurationPolicy() {
    }

    public static DurationBreakdown calculate(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("Start and end times must be provided");
        }

        Duration actualDuration = TripDurationCalculator.calculateActualDuration(start, end);
        if (actualDuration.isZero() || actualDuration.isNegative()) {
            throw new IllegalArgumentException("Trip duration must be positive");
        }

        long billablePeriods = Math.max(1L, (long) Math.ceil(actualDuration.toMinutes() / 1440.0));
        long calendarDays = Math.max(1L, ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()));
        long cancellationCalendarDaysInclusive = Math.max(1L,
                ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()) + 1L);

        return new DurationBreakdown(actualDuration, billablePeriods, calendarDays, cancellationCalendarDaysInclusive);
    }

    public record DurationBreakdown(
            Duration actualDuration,
            long billablePeriods,
            long calendarDays,
            long cancellationCalendarDaysInclusive
    ) {
    }
}

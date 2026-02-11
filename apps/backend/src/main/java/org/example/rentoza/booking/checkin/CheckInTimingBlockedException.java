package org.example.rentoza.booking.checkin;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Thrown when a check-in write operation (photo upload, submission) is attempted
 * before the timing window allows it.
 *
 * <p>Carries countdown data so the controller can return an HTTP 409 with
 * machine-readable fields for the frontend countdown display.
 */
@Getter
public class CheckInTimingBlockedException extends RuntimeException {

    private final long minutesRemaining;
    private final LocalDateTime earliestAllowedTime;

    public CheckInTimingBlockedException(long minutesRemaining, LocalDateTime earliestAllowedTime) {
        super(String.format("Check-in upload blocked: %d minutes remaining until %s",
                minutesRemaining, earliestAllowedTime));
        this.minutesRemaining = minutesRemaining;
        this.earliestAllowedTime = earliestAllowedTime;
    }
}

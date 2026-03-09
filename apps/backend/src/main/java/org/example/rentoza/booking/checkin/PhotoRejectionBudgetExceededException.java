package org.example.rentoza.booking.checkin;

import java.time.Instant;

public class PhotoRejectionBudgetExceededException extends RuntimeException {

    private final long retryAfterSeconds;
    private final Instant cooldownUntil;

    public PhotoRejectionBudgetExceededException(String message, long retryAfterSeconds, Instant cooldownUntil) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
        this.cooldownUntil = cooldownUntil;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public Instant getCooldownUntil() {
        return cooldownUntil;
    }
}

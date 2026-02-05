package org.example.rentoza.user.gdpr;

import lombok.Getter;
import java.util.List;

/**
 * Exception thrown when account deletion is blocked by active bookings.
 */
@Getter
public class ActiveBookingsException extends RuntimeException {
    private final List<Long> bookingIds;
    
    public ActiveBookingsException(String message, List<Long> bookingIds) {
        super(message);
        this.bookingIds = bookingIds;
    }
}

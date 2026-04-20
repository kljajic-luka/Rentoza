package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.booking.BookingStatus;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'booking_status'.
 */
@Converter(autoApply = true)
public class BookingStatusConverter extends PostgresEnumConverter<BookingStatus> {
    public BookingStatusConverter() {
        super(BookingStatus.class);
    }
}

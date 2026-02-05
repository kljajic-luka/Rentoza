package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.booking.checkin.CheckInEventType;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'check_in_event_type'.
 */
@Converter(autoApply = true)
public class CheckInEventTypeConverter extends PostgresEnumConverter<CheckInEventType> {
    public CheckInEventTypeConverter() {
        super(CheckInEventType.class);
    }
}

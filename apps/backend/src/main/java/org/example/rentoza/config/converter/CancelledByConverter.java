package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.booking.cancellation.CancelledBy;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'cancellation_initiator'.
 */
@Converter(autoApply = true)
public class CancelledByConverter extends PostgresEnumConverter<CancelledBy> {
    public CancelledByConverter() {
        super(CancelledBy.class);
    }
}

package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.booking.cancellation.CancellationReason;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'cancellation_reason'.
 */
@Converter(autoApply = true)
public class CancellationReasonConverter extends PostgresEnumConverter<CancellationReason> {
    public CancellationReasonConverter() {
        super(CancellationReason.class);
    }
}

package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.booking.checkin.PhotoDiscrepancy.Severity;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'discrepancy_severity'.
 */
@Converter(autoApply = true)
public class SeverityConverter extends PostgresEnumConverter<Severity> {
    public SeverityConverter() {
        super(Severity.class);
    }
}

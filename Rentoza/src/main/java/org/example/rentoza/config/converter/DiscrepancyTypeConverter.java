package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.booking.checkin.PhotoDiscrepancy.DiscrepancyType;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'discrepancy_type'.
 */
@Converter(autoApply = true)
public class DiscrepancyTypeConverter extends PostgresEnumConverter<DiscrepancyType> {
    public DiscrepancyTypeConverter() {
        super(DiscrepancyType.class);
    }
}

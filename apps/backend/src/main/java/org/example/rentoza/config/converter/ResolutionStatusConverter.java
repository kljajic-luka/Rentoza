package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.booking.checkin.PhotoDiscrepancy.ResolutionStatus;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'resolution_status'.
 */
@Converter(autoApply = true)
public class ResolutionStatusConverter extends PostgresEnumConverter<ResolutionStatus> {
    public ResolutionStatusConverter() {
        super(ResolutionStatus.class);
    }
}

package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.booking.extension.TripExtensionStatus;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'trip_extension_status'.
 */
@Converter(autoApply = true)
public class TripExtensionStatusConverter extends PostgresEnumConverter<TripExtensionStatus> {
    public TripExtensionStatusConverter() {
        super(TripExtensionStatus.class);
    }
}

package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.booking.checkin.ExifValidationStatus;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'exif_validation_status'.
 */
@Converter(autoApply = true)
public class ExifValidationStatusConverter extends PostgresEnumConverter<ExifValidationStatus> {
    public ExifValidationStatusConverter() {
        super(ExifValidationStatus.class);
    }
}

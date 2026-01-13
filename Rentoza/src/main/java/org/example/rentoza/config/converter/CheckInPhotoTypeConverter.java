package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.booking.checkin.CheckInPhotoType;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'check_in_photo_type'.
 */
@Converter(autoApply = true)
public class CheckInPhotoTypeConverter extends PostgresEnumConverter<CheckInPhotoType> {
    public CheckInPhotoTypeConverter() {
        super(CheckInPhotoType.class);
    }
}

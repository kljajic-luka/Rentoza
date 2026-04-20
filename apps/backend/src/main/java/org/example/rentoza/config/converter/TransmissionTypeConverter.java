package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.car.TransmissionType;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'transmission_type'.
 */
@Converter(autoApply = true)
public class TransmissionTypeConverter extends PostgresEnumConverter<TransmissionType> {
    public TransmissionTypeConverter() {
        super(TransmissionType.class);
    }
}

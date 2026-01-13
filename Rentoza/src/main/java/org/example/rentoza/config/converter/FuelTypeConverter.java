package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.car.FuelType;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'fuel_type'.
 */
@Converter(autoApply = true)
public class FuelTypeConverter extends PostgresEnumConverter<FuelType> {
    public FuelTypeConverter() {
        super(FuelType.class);
    }
}

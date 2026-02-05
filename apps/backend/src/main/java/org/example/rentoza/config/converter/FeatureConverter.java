package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.car.Feature;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'feature'.
 */
@Converter(autoApply = true)
public class FeatureConverter extends PostgresEnumConverter<Feature> {
    public FeatureConverter() {
        super(Feature.class);
    }
}

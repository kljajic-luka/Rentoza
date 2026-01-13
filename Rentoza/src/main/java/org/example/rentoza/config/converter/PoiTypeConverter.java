package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.delivery.DeliveryPoi.PoiType;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'poi_type'.
 */
@Converter(autoApply = true)
public class PoiTypeConverter extends PostgresEnumConverter<PoiType> {
    public PoiTypeConverter() {
        super(PoiType.class);
    }
}

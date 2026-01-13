package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.user.OwnerType;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'user_type'.
 */
@Converter(autoApply = true)
public class OwnerTypeConverter extends PostgresEnumConverter<OwnerType> {
    public OwnerTypeConverter() {
        super(OwnerType.class);
    }
}

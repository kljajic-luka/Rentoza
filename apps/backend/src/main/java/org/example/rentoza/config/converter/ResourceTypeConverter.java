package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.admin.entity.ResourceType;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'resource_type'.
 */
@Converter(autoApply = true)
public class ResourceTypeConverter extends PostgresEnumConverter<ResourceType> {
    public ResourceTypeConverter() {
        super(ResourceType.class);
    }
}

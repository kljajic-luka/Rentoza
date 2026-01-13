package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.user.Role;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'user_role'.
 */
@Converter(autoApply = true)
public class RoleConverter extends PostgresEnumConverter<Role> {
    public RoleConverter() {
        super(Role.class);
    }
}

package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.admin.entity.AdminAction;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'admin_action'.
 */
@Converter(autoApply = true)
public class AdminActionConverter extends PostgresEnumConverter<AdminAction> {
    public AdminActionConverter() {
        super(AdminAction.class);
    }
}

package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.user.RegistrationStatus;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'registration_status'.
 */
@Converter(autoApply = true)
public class RegistrationStatusConverter extends PostgresEnumConverter<RegistrationStatus> {
    public RegistrationStatusConverter() {
        super(RegistrationStatus.class);
    }
}

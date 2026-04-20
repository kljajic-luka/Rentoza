package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.booking.checkin.IdVerificationStatus;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'id_verification_status'.
 */
@Converter(autoApply = true)
public class IdVerificationStatusConverter extends PostgresEnumConverter<IdVerificationStatus> {
    public IdVerificationStatusConverter() {
        super(IdVerificationStatus.class);
    }
}

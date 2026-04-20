package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.booking.checkin.CheckInActorRole;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'check_in_actor_role'.
 */
@Converter(autoApply = true)
public class CheckInActorRoleConverter extends PostgresEnumConverter<CheckInActorRole> {
    public CheckInActorRoleConverter() {
        super(CheckInActorRole.class);
    }
}

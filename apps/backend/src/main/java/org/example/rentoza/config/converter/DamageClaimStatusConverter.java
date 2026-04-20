package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.booking.dispute.DamageClaimStatus;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'damage_claim_status'.
 */
@Converter(autoApply = true)
public class DamageClaimStatusConverter extends PostgresEnumConverter<DamageClaimStatus> {
    public DamageClaimStatusConverter() {
        super(DamageClaimStatus.class);
    }
}

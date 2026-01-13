package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.car.CancellationPolicy;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'cancellation_policy'.
 */
@Converter(autoApply = true)
public class CancellationPolicyConverter extends PostgresEnumConverter<CancellationPolicy> {
    public CancellationPolicyConverter() {
        super(CancellationPolicy.class);
    }
}

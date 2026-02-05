package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.admin.dto.enums.DisputeDecision;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'dispute_decision'.
 */
@Converter(autoApply = true)
public class DisputeDecisionConverter extends PostgresEnumConverter<DisputeDecision> {
    public DisputeDecisionConverter() {
        super(DisputeDecision.class);
    }
}

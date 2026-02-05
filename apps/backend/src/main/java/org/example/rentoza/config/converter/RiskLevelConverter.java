package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.user.RiskLevel;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'risk_level'.
 */
@Converter(autoApply = true)
public class RiskLevelConverter extends PostgresEnumConverter<RiskLevel> {
    public RiskLevelConverter() {
        super(RiskLevel.class);
    }
}

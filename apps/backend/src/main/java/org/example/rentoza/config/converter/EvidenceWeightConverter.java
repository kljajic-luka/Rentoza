package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.booking.checkin.EvidenceWeight;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'evidence_weight'.
 */
@Converter(autoApply = true)
public class EvidenceWeightConverter extends PostgresEnumConverter<EvidenceWeight> {
    public EvidenceWeightConverter() {
        super(EvidenceWeight.class);
    }
}

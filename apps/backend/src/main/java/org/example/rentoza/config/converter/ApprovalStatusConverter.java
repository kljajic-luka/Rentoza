package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.car.ApprovalStatus;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'approval_status'.
 */
@Converter(autoApply = true)
public class ApprovalStatusConverter extends PostgresEnumConverter<ApprovalStatus> {
    public ApprovalStatusConverter() {
        super(ApprovalStatus.class);
    }
}

package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.user.document.RenterVerificationAudit.AuditAction;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'audit_action'.
 */
@Converter(autoApply = true)
public class AuditActionConverter extends PostgresEnumConverter<AuditAction> {
    public AuditActionConverter() {
        super(AuditAction.class);
    }
}

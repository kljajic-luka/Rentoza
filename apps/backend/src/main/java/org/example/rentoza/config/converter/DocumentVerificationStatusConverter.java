package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.car.DocumentVerificationStatus;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'document_verification_status'.
 */
@Converter(autoApply = true)
public class DocumentVerificationStatusConverter extends PostgresEnumConverter<DocumentVerificationStatus> {
    public DocumentVerificationStatusConverter() {
        super(DocumentVerificationStatus.class);
    }
}

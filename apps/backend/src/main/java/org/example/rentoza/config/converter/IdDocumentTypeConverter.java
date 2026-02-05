package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.booking.checkin.CheckInIdVerification.DocumentType;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'id_document_type'.
 */
@Converter(autoApply = true)
public class IdDocumentTypeConverter extends PostgresEnumConverter<DocumentType> {
    public IdDocumentTypeConverter() {
        super(DocumentType.class);
    }
}

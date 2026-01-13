package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.car.DocumentType;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'car_document_type'.
 */
@Converter(autoApply = true)
public class DocumentTypeConverter extends PostgresEnumConverter<DocumentType> {
    public DocumentTypeConverter() {
        super(DocumentType.class);
    }
}

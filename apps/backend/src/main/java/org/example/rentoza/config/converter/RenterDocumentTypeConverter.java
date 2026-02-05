package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.user.document.RenterDocumentType;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'document_type'.
 */
@Converter(autoApply = true)
public class RenterDocumentTypeConverter extends PostgresEnumConverter<RenterDocumentType> {
    public RenterDocumentTypeConverter() {
        super(RenterDocumentType.class);
    }
}

package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.user.document.RenterDocument.ProcessingStatus;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'processing_status'.
 */
@Converter(autoApply = true)
public class ProcessingStatusConverter extends PostgresEnumConverter<ProcessingStatus> {
    public ProcessingStatusConverter() {
        super(ProcessingStatus.class);
    }
}

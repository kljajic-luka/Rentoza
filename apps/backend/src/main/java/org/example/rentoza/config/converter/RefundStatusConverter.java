package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.booking.cancellation.RefundStatus;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'refund_status'.
 */
@Converter(autoApply = true)
public class RefundStatusConverter extends PostgresEnumConverter<RefundStatus> {
    public RefundStatusConverter() {
        super(RefundStatus.class);
    }
}

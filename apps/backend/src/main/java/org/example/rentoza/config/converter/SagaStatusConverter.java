package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.booking.checkout.saga.CheckoutSagaState.SagaStatus;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'saga_status'.
 */
@Converter(autoApply = true)
public class SagaStatusConverter extends PostgresEnumConverter<SagaStatus> {
    public SagaStatusConverter() {
        super(SagaStatus.class);
    }
}

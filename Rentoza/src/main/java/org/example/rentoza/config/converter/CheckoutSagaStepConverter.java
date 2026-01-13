package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.booking.checkout.saga.CheckoutSagaStep;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'checkout_saga_step'.
 */
@Converter(autoApply = true)
public class CheckoutSagaStepConverter extends PostgresEnumConverter<CheckoutSagaStep> {
    public CheckoutSagaStepConverter() {
        super(CheckoutSagaStep.class);
    }
}

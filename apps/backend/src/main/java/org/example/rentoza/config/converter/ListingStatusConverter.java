package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.car.ListingStatus;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'listing_status'.
 */
@Converter(autoApply = true)
public class ListingStatusConverter extends PostgresEnumConverter<ListingStatus> {
    public ListingStatusConverter() {
        super(ListingStatus.class);
    }
}

package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.review.ReviewDirection;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'review_direction'.
 */
@Converter(autoApply = true)
public class ReviewDirectionConverter extends PostgresEnumConverter<ReviewDirection> {
    public ReviewDirectionConverter() {
        super(ReviewDirection.class);
    }
}

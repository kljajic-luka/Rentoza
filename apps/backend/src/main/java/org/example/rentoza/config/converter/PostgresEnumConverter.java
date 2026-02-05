package org.example.rentoza.config.converter;

import jakarta.persistence.AttributeConverter;

/**
 * Base class for PostgreSQL ENUM type converters.
 * 
 * <p>PostgreSQL native ENUMs are strict types that don't accept VARCHAR comparisons.
 * This base converter handles the String ↔ Enum conversion, allowing PostgreSQL
 * to auto-cast the String value to the native ENUM type.
 * 
 * <h2>Usage</h2>
 * <pre>
 * &#64;Converter(autoApply = true)
 * public class BookingStatusConverter extends PostgresEnumConverter&lt;BookingStatus&gt; {
 *     public BookingStatusConverter() {
 *         super(BookingStatus.class);
 *     }
 * }
 * </pre>
 * 
 * @param <E> The Java enum type
 */
public abstract class PostgresEnumConverter<E extends Enum<E>> 
        implements AttributeConverter<E, String> {
    
    private final Class<E> enumClass;
    
    protected PostgresEnumConverter(Class<E> enumClass) {
        this.enumClass = enumClass;
    }
    
    @Override
    public String convertToDatabaseColumn(E attribute) {
        return attribute != null ? attribute.name() : null;
    }
    
    @Override
    public E convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(enumClass, dbData);
        } catch (IllegalArgumentException e) {
            // Log and return null for unknown values (defensive programming)
            return null;
        }
    }
}

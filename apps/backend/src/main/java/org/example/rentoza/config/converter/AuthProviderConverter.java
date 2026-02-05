package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.user.AuthProvider;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'auth_provider'.
 */
@Converter(autoApply = true)
public class AuthProviderConverter extends PostgresEnumConverter<AuthProvider> {
    public AuthProviderConverter() {
        super(AuthProvider.class);
    }
}

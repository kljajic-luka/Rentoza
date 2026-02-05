package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.user.DriverLicenseStatus;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'driver_license_status'.
 */
@Converter(autoApply = true)
public class DriverLicenseStatusConverter extends PostgresEnumConverter<DriverLicenseStatus> {
    public DriverLicenseStatusConverter() {
        super(DriverLicenseStatus.class);
    }
}

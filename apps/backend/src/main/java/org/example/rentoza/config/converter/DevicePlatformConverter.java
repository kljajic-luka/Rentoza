package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.notification.UserDeviceToken.DevicePlatform;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'device_platform'.
 */
@Converter(autoApply = true)
public class DevicePlatformConverter extends PostgresEnumConverter<DevicePlatform> {
    public DevicePlatformConverter() {
        super(DevicePlatform.class);
    }
}

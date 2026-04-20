package org.example.rentoza.config.converter;

import jakarta.persistence.Converter;
import org.example.rentoza.notification.NotificationType;

/**
 * JPA AttributeConverter for PostgreSQL native ENUM type 'notification_type'.
 */
@Converter(autoApply = true)
public class NotificationTypeConverter extends PostgresEnumConverter<NotificationType> {
    public NotificationTypeConverter() {
        super(NotificationType.class);
    }
}

package org.example.rentoza.scalability;

import org.example.rentoza.config.RabbitMQConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G4: Verifies RabbitMQ configuration is gated behind a feature toggle.
 *
 * <p>Ensures that RabbitMQ beans are only created when
 * {@code app.rabbitmq.enabled=true}, preventing startup failures
 * when no broker is available.
 */
class RabbitMQToggleTest {

    @Test
    @DisplayName("G4: RabbitMQConfig is gated by @ConditionalOnProperty(app.rabbitmq.enabled)")
    void rabbitMQConfig_isConditionalOnProperty() {
        ConditionalOnProperty annotation = RabbitMQConfig.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(annotation)
                .as("RabbitMQConfig must have @ConditionalOnProperty")
                .isNotNull();

        assertThat(annotation.name())
                .as("Conditional property must be app.rabbitmq.enabled")
                .contains("app.rabbitmq.enabled");

        assertThat(annotation.havingValue())
                .as("Property must require 'true' to activate")
                .isEqualTo("true");

        assertThat(annotation.matchIfMissing())
                .as("Must NOT activate when property is missing (fail-closed)")
                .isFalse();
    }

    @Test
    @DisplayName("G4: RabbitMQConfig declares required queue topology beans")
    void rabbitMQConfig_declaresRequiredBeans() throws Exception {
        // Verify critical bean method declarations exist
        assertThat(RabbitMQConfig.class.getDeclaredMethod("directExchange"))
                .as("directExchange bean must exist").isNotNull();

        assertThat(RabbitMQConfig.class.getDeclaredMethod("photoValidationQueue"))
                .as("photoValidationQueue bean must exist").isNotNull();

        assertThat(RabbitMQConfig.class.getDeclaredMethod("checkoutSagaQueue"))
                .as("checkoutSagaQueue bean must exist").isNotNull();

        assertThat(RabbitMQConfig.class.getDeclaredMethod("notificationQueue"))
                .as("notificationQueue bean must exist").isNotNull();

        assertThat(RabbitMQConfig.class.getDeclaredMethod("photoValidationDlqQueue"))
                .as("DLQ must exist for photo validation").isNotNull();
    }

    @Test
    @DisplayName("G4: Production properties contain RabbitMQ broker settings")
    void productionProperties_containRabbitMQSettings() throws Exception {
        String prodProps = readProdProperties();

        assertThat(prodProps)
                .as("Prod properties must define app.rabbitmq.enabled")
                .contains("app.rabbitmq.enabled=${RABBITMQ_ENABLED:false}");

        assertThat(prodProps)
                .as("Prod properties must configure spring.rabbitmq.host")
                .contains("spring.rabbitmq.host=${RABBITMQ_HOST:");

        assertThat(prodProps)
                .as("Prod properties must enable publisher confirms")
                .contains("spring.rabbitmq.publisher-confirm-type=correlated");

        assertThat(prodProps)
                .as("Prod properties must configure consumer retry")
                .contains("spring.rabbitmq.listener.simple.retry.enabled=true");

        assertThat(prodProps)
                .as("Prod properties must enable SSL for broker")
                .contains("spring.rabbitmq.ssl.enabled=${RABBITMQ_SSL_ENABLED:true}");
    }

    @Test
    @DisplayName("G4: Rabbit health check is dynamic (tied to RABBITMQ_ENABLED)")
    void rabbitHealthCheck_isDynamicInProd() throws Exception {
        String prodProps = readProdProperties();

        assertThat(prodProps)
                .as("Rabbit health check must be dynamic, not hardcoded off")
                .contains("management.health.rabbit.enabled=${RABBITMQ_ENABLED:false}");
    }

    private String readProdProperties() throws Exception {
        java.nio.file.Path path = java.nio.file.Path.of(
                System.getProperty("user.dir"),
                "src/main/resources/application-prod.properties");
        if (!java.nio.file.Files.exists(path)) {
            path = java.nio.file.Path.of(
                    "src/main/resources/application-prod.properties");
        }
        assertThat(path).as("application-prod.properties must exist").exists();
        return java.nio.file.Files.readString(path);
    }
}

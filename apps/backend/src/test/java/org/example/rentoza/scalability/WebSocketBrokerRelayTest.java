package org.example.rentoza.scalability;

import org.example.rentoza.config.WebSocketConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G5: Verifies WebSocket broker relay configuration for horizontal scaling.
 *
 * <p>Ensures WebSocketConfig supports an external STOMP broker relay
 * controlled by {@code app.websocket.broker-relay.enabled}, and that
 * the relay configuration fields exist for host, port, login, passcode.
 */
class WebSocketBrokerRelayTest {

    @Test
    @DisplayName("G5: WebSocketConfig has brokerRelayEnabled toggle field")
    void webSocketConfig_hasBrokerRelayToggle() throws Exception {
        Field field = WebSocketConfig.class.getDeclaredField("brokerRelayEnabled");
        assertThat(field.getType()).as("brokerRelayEnabled must be boolean").isEqualTo(boolean.class);

        Value valueAnnotation = field.getAnnotation(Value.class);
        assertThat(valueAnnotation).as("Must be annotated with @Value").isNotNull();
        assertThat(valueAnnotation.value())
                .as("Default must be false (SimpleBroker fallback)")
                .contains("app.websocket.broker-relay.enabled")
                .contains("false");
    }

    @Test
    @DisplayName("G5: WebSocketConfig has relay connection fields (host, port, login, passcode)")
    void webSocketConfig_hasRelayConnectionFields() throws Exception {
        // Verify all relay configuration fields exist with @Value annotations
        assertRelayField("relayHost", "app.websocket.broker-relay.host");
        assertRelayField("relayPort", "app.websocket.broker-relay.port");
        assertRelayField("relayLogin", "app.websocket.broker-relay.login");
        assertRelayField("relayPasscode", "app.websocket.broker-relay.passcode");
    }

    @Test
    @DisplayName("G5: configureMessageBroker method uses conditional relay logic")
    void webSocketConfig_configureMessageBroker_exists() throws Exception {
        // Verify the configureMessageBroker method exists and uses relay
        String sourceCode = readClassSource(WebSocketConfig.class);

        assertThat(sourceCode)
                .as("Must check brokerRelayEnabled condition")
                .contains("brokerRelayEnabled");

        assertThat(sourceCode)
                .as("Must configure STOMP broker relay when enabled")
                .contains("enableStompBrokerRelay");

        assertThat(sourceCode)
                .as("Must fall back to SimpleBroker when relay disabled")
                .contains("enableSimpleBroker");

        assertThat(sourceCode)
                .as("Must configure relay heartbeats")
                .contains("setSystemHeartbeatSendInterval");
    }

    private void assertRelayField(String fieldName, String expectedProperty) throws Exception {
        Field field = WebSocketConfig.class.getDeclaredField(fieldName);
        Value valueAnnotation = field.getAnnotation(Value.class);
        assertThat(valueAnnotation)
                .as("Field %s must have @Value annotation", fieldName)
                .isNotNull();
        assertThat(valueAnnotation.value())
                .as("@Value for %s must reference %s", fieldName, expectedProperty)
                .contains(expectedProperty);
    }

    private String readClassSource(Class<?> clazz) throws Exception {
        String relativePath = "src/main/java/"
                + clazz.getName().replace('.', '/') + ".java";
        java.nio.file.Path sourcePath = java.nio.file.Path.of(
                System.getProperty("user.dir"), relativePath);
        if (!java.nio.file.Files.exists(sourcePath)) {
            sourcePath = java.nio.file.Path.of(relativePath);
        }
        assertThat(sourcePath).as("Source file for %s must exist", clazz.getSimpleName()).exists();
        return java.nio.file.Files.readString(sourcePath);
    }
}

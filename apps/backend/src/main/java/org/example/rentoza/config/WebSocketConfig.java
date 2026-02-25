package org.example.rentoza.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket configuration for real-time notifications.
 * Enables STOMP over WebSocket for in-app notification delivery.
 *
 * <h2>G5: Broker Relay for Horizontal Scaling</h2>
 * <p>When {@code app.websocket.broker-relay.enabled=true}, uses an external
 * STOMP broker relay (RabbitMQ with STOMP plugin) instead of the in-memory
 * SimpleBroker. This allows WebSocket messages to be shared across multiple
 * application instances.
 *
 * <h3>Fallback behavior:</h3>
 * <ul>
 *   <li>If broker-relay is disabled or not configured: uses SimpleBroker (single-instance)</li>
 *   <li>If broker-relay is enabled but broker is unreachable: Spring will retry connections
 *       and log errors, but the application will still start</li>
 * </ul>
 *
 * Endpoints:
 * - /ws - WebSocket handshake endpoint
 *
 * Topics:
 * - /user/{userId}/queue/notifications - User-specific notification queue
 * - /topic/* - Broadcast topics (future use)
 *
 * Application prefix: /app
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
@Slf4j
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${websocket.allowed-origins:http://localhost:4200}")
    private String allowedOrigins;

    @Value("${app.websocket.broker-relay.enabled:false}")
    private boolean brokerRelayEnabled;

    @Value("${app.websocket.broker-relay.host:localhost}")
    private String relayHost;

    @Value("${app.websocket.broker-relay.port:61613}")
    private int relayPort;

    @Value("${app.websocket.broker-relay.login:guest}")
    private String relayLogin;

    @Value("${app.websocket.broker-relay.passcode:guest}")
    private String relayPasscode;

    /**
     * Configure message broker for STOMP messaging.
     *
     * <p>G5: When broker-relay is enabled, uses an external STOMP broker
     * (e.g., RabbitMQ with rabbitmq_stomp plugin on port 61613) for
     * multi-instance message distribution.
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        if (brokerRelayEnabled) {
            log.info("[WebSocket] Configuring STOMP broker relay: {}:{}", relayHost, relayPort);
            config.enableStompBrokerRelay("/topic", "/queue")
                    .setRelayHost(relayHost)
                    .setRelayPort(relayPort)
                    .setClientLogin(relayLogin)
                    .setClientPasscode(relayPasscode)
                    .setSystemLogin(relayLogin)
                    .setSystemPasscode(relayPasscode)
                    .setSystemHeartbeatSendInterval(10000)
                    .setSystemHeartbeatReceiveInterval(10000);
        } else {
            log.info("[WebSocket] Using in-memory SimpleBroker (single-instance mode)");
            config.enableSimpleBroker("/topic", "/queue");
        }
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    private final WebSocketAuthInterceptor authInterceptor;

    /**
     * Register STOMP endpoints for WebSocket connections.
     * Endpoint: /ws with SockJS fallback support and JWT authentication.
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins.split(","))
                .addInterceptors(authInterceptor)
                .withSockJS();
    }
}

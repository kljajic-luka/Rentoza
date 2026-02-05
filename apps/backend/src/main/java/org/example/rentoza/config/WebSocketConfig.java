package org.example.rentoza.config;

import lombok.RequiredArgsConstructor;
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
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${websocket.allowed-origins:http://localhost:4200}")
    private String allowedOrigins;

    /**
     * Configure message broker for STOMP messaging.
     * - /topic prefix for broadcast messages
     * - /queue prefix for user-specific messages (notifications)
     * - /app prefix for client-to-server messages
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
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

package org.example.rentoza.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * OPS-GAP-6 Remediation: Cross-service health check for chat-service dependency.
 *
 * <p>Proactively validates chat-service availability via its actuator health endpoint
 * rather than discovering failures at request time. Reports status in
 * {@code /actuator/health} so operators see degraded state before user impact.
 *
 * <p>Uses a short timeout (2s) to prevent health check from blocking
 * the actuator response. Chat-service unavailability degrades the health
 * status to {@code DOWN} with diagnostic details but does not prevent
 * the backend from functioning (chat is non-blocking for core flows).
 */
@Component
@Slf4j
public class ChatServiceHealthIndicator implements HealthIndicator {

    private final RestTemplate restTemplate;
    private final String chatServiceUrl;

    public ChatServiceHealthIndicator(
            RestTemplate restTemplate,
            @Value("${chat.service.url:http://localhost:8081}") String chatServiceUrl) {
        this.restTemplate = restTemplate;
        this.chatServiceUrl = chatServiceUrl;
    }

    @Override
    public Health health() {
        String healthUrl = chatServiceUrl + "/actuator/health";
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(healthUrl, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                return Health.up()
                        .withDetail("url", chatServiceUrl)
                        .withDetail("status", "reachable")
                        .build();
            }

            return Health.down()
                    .withDetail("url", chatServiceUrl)
                    .withDetail("httpStatus", response.getStatusCode().value())
                    .build();
        } catch (Exception e) {
            log.warn("[HealthCheck] Chat-service unreachable at {}: {}", healthUrl, e.getMessage());
            return Health.down()
                    .withDetail("url", chatServiceUrl)
                    .withDetail("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                    .build();
        }
    }
}

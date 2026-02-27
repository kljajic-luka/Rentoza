package org.example.rentoza.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests for OPS-GAP-6 remediation: Cross-service health check for chat-service.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Health reports UP when chat-service is reachable and healthy</li>
 *   <li>Health reports DOWN when chat-service returns non-2xx</li>
 *   <li>Health reports DOWN with error details when chat-service is unreachable</li>
 *   <li>Health includes diagnostic details (url, status) for operator visibility</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OPS-GAP-6: Chat-Service Health Indicator")
class ChatServiceHealthIndicatorTest {

    @Mock
    private RestTemplate restTemplate;

    private ChatServiceHealthIndicator healthIndicator;

    private static final String CHAT_URL = "http://chat-service:8081";

    @BeforeEach
    void setUp() {
        healthIndicator = new ChatServiceHealthIndicator(restTemplate, CHAT_URL);
    }

    @Nested
    @DisplayName("Health Status Reporting")
    class HealthStatusReporting {

        @Test
        @DisplayName("OPS-GAP-6: Reports UP when chat-service responds 200")
        void reportsUpWhenHealthy() {
            when(restTemplate.getForEntity(
                    eq(CHAT_URL + "/actuator/health"), eq(String.class)))
                    .thenReturn(new ResponseEntity<>("{\"status\":\"UP\"}", HttpStatus.OK));

            Health health = healthIndicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("url", CHAT_URL);
            assertThat(health.getDetails()).containsEntry("status", "reachable");
        }

        @Test
        @DisplayName("OPS-GAP-6: Reports DOWN when chat-service returns 503")
        void reportsDownOnServerError() {
            when(restTemplate.getForEntity(
                    eq(CHAT_URL + "/actuator/health"), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(HttpStatus.SERVICE_UNAVAILABLE));

            Health health = healthIndicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("httpStatus", 503);
        }

        @Test
        @DisplayName("OPS-GAP-6: Reports DOWN with error when chat-service is unreachable")
        void reportsDownWhenUnreachable() {
            when(restTemplate.getForEntity(anyString(), eq(String.class)))
                    .thenThrow(new ResourceAccessException("Connection refused"));

            Health health = healthIndicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsKey("error");
            assertThat(health.getDetails().get("error").toString())
                    .contains("Connection refused");
        }

        @Test
        @DisplayName("OPS-GAP-6: Health details include chat-service URL for diagnostics")
        void includesUrlInDetails() {
            when(restTemplate.getForEntity(anyString(), eq(String.class)))
                    .thenThrow(new ResourceAccessException("timeout"));

            Health health = healthIndicator.health();

            assertThat(health.getDetails()).containsEntry("url", CHAT_URL);
        }
    }
}

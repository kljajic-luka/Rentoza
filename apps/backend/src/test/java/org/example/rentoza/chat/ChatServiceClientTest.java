package org.example.rentoza.chat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.chat.dto.ConversationResponse;
import org.example.rentoza.chat.dto.CreateConversationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ChatServiceClient covering the A2 audit fix:
 * managed async executor (bounded thread pool with CompletableFuture)
 * replacing raw Thread, with metrics and retry logic verification.
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceClientTest {

    @Mock
    private RestTemplate restTemplate;

    private SimpleMeterRegistry meterRegistry;
    private ChatServiceClient chatServiceClient;

    private static final String CHAT_SERVICE_URL = "http://localhost:8081";
    private static final String BOOKING_ID = "booking-123";
    private static final String RENTER_ID = "renter-456";
    private static final String OWNER_ID = "owner-789";
    private static final String JWT_TOKEN = "eyJhbGciOiJIUzI1NiJ9.test-token";

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        chatServiceClient = new ChatServiceClient(restTemplate, CHAT_SERVICE_URL, meterRegistry);
    }

    /**
     * Helper to build a valid ConversationResponse for stubbed RestTemplate calls.
     */
    private ConversationResponse buildConversationResponse() {
        ConversationResponse response = new ConversationResponse();
        response.setId(1L);
        response.setBookingId(BOOKING_ID);
        response.setRenterId(RENTER_ID);
        response.setOwnerId(OWNER_ID);
        response.setStatus("ACTIVE");
        response.setCreatedAt(LocalDateTime.now());
        response.setUpdatedAt(LocalDateTime.now());
        response.setMessagingAllowed(true);
        return response;
    }

    // ======================================================================
    // Synchronous createConversation tests
    // ======================================================================

    @Nested
    @DisplayName("Sync createConversation")
    class SyncCreateConversation {

        @Test
        @DisplayName("1. Returns ConversationResponse on successful 200 response")
        void createConversation_success_returnsResponse() {
            ConversationResponse expected = buildConversationResponse();
            ResponseEntity<ConversationResponse> responseEntity =
                    new ResponseEntity<>(expected, HttpStatus.OK);

            when(restTemplate.exchange(
                    eq(CHAT_SERVICE_URL + "/api/conversations"),
                    eq(HttpMethod.POST),
                    any(HttpEntity.class),
                    eq(ConversationResponse.class)
            )).thenReturn(responseEntity);

            ConversationResponse result = chatServiceClient.createConversation(
                    BOOKING_ID, RENTER_ID, OWNER_ID, JWT_TOKEN);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getBookingId()).isEqualTo(BOOKING_ID);
            assertThat(result.getRenterId()).isEqualTo(RENTER_ID);
            assertThat(result.getOwnerId()).isEqualTo(OWNER_ID);
            assertThat(result.isMessagingAllowed()).isTrue();

            // Should only call exchange once (no retries needed)
            verify(restTemplate, times(1)).exchange(
                    anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(ConversationResponse.class));
        }

        @Test
        @DisplayName("2. Bearer prefix is stripped from token before setting auth header")
        void createConversation_bearerPrefix_strippedFromToken() {
            ConversationResponse expected = buildConversationResponse();
            ResponseEntity<ConversationResponse> responseEntity =
                    new ResponseEntity<>(expected, HttpStatus.OK);

            ArgumentCaptor<HttpEntity<CreateConversationRequest>> entityCaptor =
                    ArgumentCaptor.forClass(HttpEntity.class);

            when(restTemplate.exchange(
                    eq(CHAT_SERVICE_URL + "/api/conversations"),
                    eq(HttpMethod.POST),
                    entityCaptor.capture(),
                    eq(ConversationResponse.class)
            )).thenReturn(responseEntity);

            // Pass token WITH "Bearer " prefix -- client should strip it
            chatServiceClient.createConversation(
                    BOOKING_ID, RENTER_ID, OWNER_ID, "Bearer " + JWT_TOKEN);

            HttpHeaders capturedHeaders = entityCaptor.getValue().getHeaders();
            String authHeader = capturedHeaders.getFirst(HttpHeaders.AUTHORIZATION);

            // Spring's setBearerAuth adds "Bearer " back, so the raw token must not have double prefix
            assertThat(authHeader)
                    .isNotNull()
                    .startsWith("Bearer ")
                    .doesNotContain("Bearer Bearer ");

            // The actual token value (after "Bearer ") should be the clean JWT
            String tokenValue = authHeader.substring("Bearer ".length());
            assertThat(tokenValue).isEqualTo(JWT_TOKEN);
        }

        @Test
        @DisplayName("3. CONFLICT (409) returns null without retrying (idempotent)")
        void createConversation_conflict_returnsNull() {
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(ConversationResponse.class)
            )).thenThrow(new HttpClientErrorException(HttpStatus.CONFLICT));

            ConversationResponse result = chatServiceClient.createConversation(
                    BOOKING_ID, RENTER_ID, OWNER_ID, JWT_TOKEN);

            assertThat(result).isNull();

            // No retry on client errors -- single invocation
            verify(restTemplate, times(1)).exchange(
                    anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(ConversationResponse.class));
        }

        @Test
        @DisplayName("4. Other client errors (e.g. 400) return null immediately, no retry")
        void createConversation_clientError_returnsNullNoRetry() {
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(ConversationResponse.class)
            )).thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

            ConversationResponse result = chatServiceClient.createConversation(
                    BOOKING_ID, RENTER_ID, OWNER_ID, JWT_TOKEN);

            assertThat(result).isNull();

            // Must NOT retry on 4xx
            verify(restTemplate, times(1)).exchange(
                    anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(ConversationResponse.class));
        }

        @Test
        @DisplayName("5. Server error (5xx) triggers retry")
        void createConversation_serverError_retriesThenSucceeds() {
            ConversationResponse expected = buildConversationResponse();
            ResponseEntity<ConversationResponse> successResponse =
                    new ResponseEntity<>(expected, HttpStatus.OK);

            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(ConversationResponse.class)
            ))
                    // First attempt: server error
                    .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR))
                    // Second attempt: success
                    .thenReturn(successResponse);

            ConversationResponse result = chatServiceClient.createConversation(
                    BOOKING_ID, RENTER_ID, OWNER_ID, JWT_TOKEN);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(1L);

            // Two invocations: initial attempt + one retry
            verify(restTemplate, times(2)).exchange(
                    anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(ConversationResponse.class));
        }

        @Test
        @DisplayName("6. All retry attempts exhausted returns null")
        void createConversation_allRetriesExhausted_returnsNull() {
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(ConversationResponse.class)
            )).thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

            ConversationResponse result = chatServiceClient.createConversation(
                    BOOKING_ID, RENTER_ID, OWNER_ID, JWT_TOKEN);

            assertThat(result).isNull();

            // MAX_RETRY_ATTEMPTS = 2, so exactly 2 invocations
            verify(restTemplate, times(2)).exchange(
                    anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(ConversationResponse.class));
        }

        @Test
        @DisplayName("11. Null jwtToken is handled safely without NPE")
        void createConversation_nullToken_doesNotThrowNPE() {
            ConversationResponse expected = buildConversationResponse();
            ResponseEntity<ConversationResponse> responseEntity =
                    new ResponseEntity<>(expected, HttpStatus.OK);

            ArgumentCaptor<HttpEntity<CreateConversationRequest>> entityCaptor =
                    ArgumentCaptor.forClass(HttpEntity.class);

            when(restTemplate.exchange(
                    eq(CHAT_SERVICE_URL + "/api/conversations"),
                    eq(HttpMethod.POST),
                    entityCaptor.capture(),
                    eq(ConversationResponse.class)
            )).thenReturn(responseEntity);

            // Should not throw even with null token
            ConversationResponse result = chatServiceClient.createConversation(
                    BOOKING_ID, RENTER_ID, OWNER_ID, null);

            assertThat(result).isNotNull();

            // When token is null, cleanToken becomes "" and setBearerAuth("") produces "Bearer "
            HttpHeaders capturedHeaders = entityCaptor.getValue().getHeaders();
            String authHeader = capturedHeaders.getFirst(HttpHeaders.AUTHORIZATION);
            assertThat(authHeader).isNotNull();
        }
    }

    // ======================================================================
    // Async createConversationAsync tests -- A2 audit fix verification
    // ======================================================================

    @Nested
    @DisplayName("Async createConversationAsync (A2 audit fix)")
    class AsyncCreateConversation {

        @Test
        @DisplayName("7. Async success increments success counter")
        void createConversationAsync_success_incrementsSuccessCounter() throws Exception {
            ConversationResponse expected = buildConversationResponse();
            ResponseEntity<ConversationResponse> responseEntity =
                    new ResponseEntity<>(expected, HttpStatus.OK);

            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(ConversationResponse.class)
            )).thenReturn(responseEntity);

            CompletableFuture<ConversationResponse> future =
                    chatServiceClient.createConversationAsync(BOOKING_ID, RENTER_ID, OWNER_ID, JWT_TOKEN);

            ConversationResponse result = future.get(10, TimeUnit.SECONDS);
            assertThat(result).isNotNull();

            Counter successCounter = meterRegistry.find("chat.conversation.async.success").counter();
            assertThat(successCounter).isNotNull();
            assertThat(successCounter.count()).isEqualTo(1.0);

            Counter failureCounter = meterRegistry.find("chat.conversation.async.failure").counter();
            assertThat(failureCounter).isNotNull();
            assertThat(failureCounter.count()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("8. Async failure (null result) increments failure counter")
        void createConversationAsync_nullResult_incrementsFailureCounter() throws Exception {
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(ConversationResponse.class)
            )).thenThrow(new HttpClientErrorException(HttpStatus.CONFLICT));

            CompletableFuture<ConversationResponse> future =
                    chatServiceClient.createConversationAsync(BOOKING_ID, RENTER_ID, OWNER_ID, JWT_TOKEN);

            ConversationResponse result = future.get(10, TimeUnit.SECONDS);
            assertThat(result).isNull();

            Counter failureCounter = meterRegistry.find("chat.conversation.async.failure").counter();
            assertThat(failureCounter).isNotNull();
            assertThat(failureCounter.count()).isEqualTo(1.0);

            Counter successCounter = meterRegistry.find("chat.conversation.async.success").counter();
            assertThat(successCounter).isNotNull();
            assertThat(successCounter.count()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("9. Async records timer duration on completion")
        void createConversationAsync_recordsDuration() throws Exception {
            ConversationResponse expected = buildConversationResponse();
            ResponseEntity<ConversationResponse> responseEntity =
                    new ResponseEntity<>(expected, HttpStatus.OK);

            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(ConversationResponse.class)
            )).thenReturn(responseEntity);

            CompletableFuture<ConversationResponse> future =
                    chatServiceClient.createConversationAsync(BOOKING_ID, RENTER_ID, OWNER_ID, JWT_TOKEN);

            future.get(10, TimeUnit.SECONDS);

            Timer timer = meterRegistry.find("chat.conversation.async.duration").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
            // Duration should be non-negative (sanity check)
            assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("10. Async returns CompletableFuture that completes with result")
        void createConversationAsync_returnsCompletedFutureWithResult() throws Exception {
            ConversationResponse expected = buildConversationResponse();
            ResponseEntity<ConversationResponse> responseEntity =
                    new ResponseEntity<>(expected, HttpStatus.OK);

            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(ConversationResponse.class)
            )).thenReturn(responseEntity);

            CompletableFuture<ConversationResponse> future =
                    chatServiceClient.createConversationAsync(BOOKING_ID, RENTER_ID, OWNER_ID, JWT_TOKEN);

            assertThat(future).isNotNull();
            assertThat(future).isInstanceOf(CompletableFuture.class);

            ConversationResponse result = future.get(10, TimeUnit.SECONDS);
            assertThat(result).isNotNull();
            assertThat(result.getBookingId()).isEqualTo(BOOKING_ID);
            assertThat(result.getRenterId()).isEqualTo(RENTER_ID);
            assertThat(result.getOwnerId()).isEqualTo(OWNER_ID);

            // Verify the future completed normally (not exceptionally)
            assertThat(future.isCompletedExceptionally()).isFalse();
            assertThat(future.isDone()).isTrue();
        }

        @Test
        @DisplayName("Async records timer duration even on failure path")
        void createConversationAsync_recordsDuration_onFailure() throws Exception {
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(ConversationResponse.class)
            )).thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

            CompletableFuture<ConversationResponse> future =
                    chatServiceClient.createConversationAsync(BOOKING_ID, RENTER_ID, OWNER_ID, JWT_TOKEN);

            future.get(10, TimeUnit.SECONDS);

            Timer timer = meterRegistry.find("chat.conversation.async.duration").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("Async does not complete exceptionally -- exceptions are caught internally")
        void createConversationAsync_neverCompletesExceptionally() throws Exception {
            // Even when all retries are exhausted, the future should resolve to null, not throw
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(ConversationResponse.class)
            )).thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

            CompletableFuture<ConversationResponse> future =
                    chatServiceClient.createConversationAsync(BOOKING_ID, RENTER_ID, OWNER_ID, JWT_TOKEN);

            ConversationResponse result = future.get(30, TimeUnit.SECONDS);

            assertThat(result).isNull();
            assertThat(future.isCompletedExceptionally()).isFalse();

            // Failure counter should be incremented
            Counter failureCounter = meterRegistry.find("chat.conversation.async.failure").counter();
            assertThat(failureCounter).isNotNull();
            assertThat(failureCounter.count()).isEqualTo(1.0);
        }
    }
}

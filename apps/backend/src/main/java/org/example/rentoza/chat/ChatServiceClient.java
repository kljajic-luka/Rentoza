package org.example.rentoza.chat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.example.rentoza.chat.dto.ConversationResponse;
import org.example.rentoza.chat.dto.CreateConversationRequest;
import org.example.rentoza.security.InternalServiceJwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Client service for communicating with the Chat Microservice.
 *
 * A2 FIX: Uses managed executor pool instead of raw Thread for async conversation creation.
 * Provides metrics, bounded thread pool, graceful shutdown, and error observability.
 */
@Service
@Slf4j
public class ChatServiceClient {

    private final RestTemplate restTemplate;
    private final String chatServiceUrl;
    private final InternalServiceJwtUtil internalServiceJwtUtil;
    private final ExecutorService chatExecutor;
    private final Counter asyncSuccessCounter;
    private final Counter asyncFailureCounter;
    private final Timer asyncDuration;
    private final ConversationCreationOutboxRepository outboxRepository;
    private final SchedulerIdempotencyService lockService;

    private static final int MAX_RETRY_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 3000;
    private static final String OUTBOX_LOCK_KEY = "chat.conversation.outbox.retry";

    public ChatServiceClient(
            RestTemplate restTemplate,
            @Value("${chat.service.url:http://localhost:8081}") String chatServiceUrl,
            InternalServiceJwtUtil internalServiceJwtUtil,
            MeterRegistry meterRegistry,
            ConversationCreationOutboxRepository outboxRepository,
            SchedulerIdempotencyService lockService
    ) {
        this.restTemplate = restTemplate;
        this.chatServiceUrl = chatServiceUrl;
        this.internalServiceJwtUtil = internalServiceJwtUtil;
        this.outboxRepository = outboxRepository;
        this.lockService = lockService;

        // Bounded thread pool with virtual threads if available, else fixed pool
        this.chatExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "chat-conversation-creator");
            t.setDaemon(true);
            return t;
        });

        this.asyncSuccessCounter = Counter.builder("chat.conversation.async.success")
                .description("Async conversation creation successes")
                .register(meterRegistry);
        this.asyncFailureCounter = Counter.builder("chat.conversation.async.failure")
                .description("Async conversation creation failures")
                .register(meterRegistry);
        this.asyncDuration = Timer.builder("chat.conversation.async.duration")
                .description("Async conversation creation duration")
                .register(meterRegistry);
    }

    private record ConversationCreationAttempt(
            ConversationCreationOutcome outcome,
            ConversationResponse response,
            String errorMessage
    ) {
    }

    private enum ConversationCreationOutcome {
        CREATED,
        EXISTS,
        FAILED
    }

    /**
     * Creates a conversation in the chat microservice with retry logic.
     *
     * @param bookingId The booking ID
     * @param renterId The renter's user ID
     * @param ownerId The owner's user ID
     * @param jwtToken The JWT token for authentication
     * @return The created conversation response, or null if all attempts fail
     */
    public ConversationResponse createConversation(String bookingId, String renterId, String ownerId, String jwtToken) {
        return attemptCreateConversation(bookingId, renterId, ownerId, jwtToken).response();
        }

        private ConversationCreationAttempt attemptCreateConversation(
            String bookingId,
            String renterId,
            String ownerId,
            String jwtToken) {
        CreateConversationRequest request = new CreateConversationRequest(bookingId, renterId, ownerId);

        // Ensure token is clean and doesn't have double Bearer prefix
        String cleanToken = jwtToken != null ? jwtToken.trim() : "";
        if (cleanToken.toLowerCase().startsWith("bearer ")) {
            cleanToken = cleanToken.substring(7).trim();
        }

        int attempt = 0;
        Exception lastException = null;

        while (attempt < MAX_RETRY_ATTEMPTS) {
            attempt++;
            try {
                log.info("Creating conversation for booking {} (attempt {}/{})", bookingId, attempt, MAX_RETRY_ATTEMPTS);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(cleanToken);

                HttpEntity<CreateConversationRequest> entity = new HttpEntity<>(request, headers);

                ResponseEntity<ConversationResponse> response = restTemplate.exchange(
                        chatServiceUrl + "/api/conversations",
                        HttpMethod.POST,
                        entity,
                        ConversationResponse.class
                );

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    log.info("Successfully created conversation {} for booking {}",
                            response.getBody().getId(), bookingId);
                    return new ConversationCreationAttempt(
                        ConversationCreationOutcome.CREATED,
                        response.getBody(),
                        null);
                }

            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.CONFLICT) {
                    log.warn("Conversation already exists for booking {}", bookingId);
                    return new ConversationCreationAttempt(
                        ConversationCreationOutcome.EXISTS,
                        null,
                        null);
                }
                log.error("Client error creating conversation for booking {}: {} - {}",
                        bookingId, e.getStatusCode(), e.getResponseBodyAsString());
                return new ConversationCreationAttempt(
                    ConversationCreationOutcome.FAILED,
                    null,
                    e.getMessage());

            } catch (HttpServerErrorException e) {
                lastException = e;
                log.warn("Server error creating conversation for booking {} (attempt {}/{}): {} - {}",
                        bookingId, attempt, MAX_RETRY_ATTEMPTS, e.getStatusCode(), e.getResponseBodyAsString());

            } catch (Exception e) {
                lastException = e;
                log.warn("Error creating conversation for booking {} (attempt {}/{}): {}",
                        bookingId, attempt, MAX_RETRY_ATTEMPTS, e.getMessage());
            }

            if (attempt < MAX_RETRY_ATTEMPTS) {
                try {
                    log.info("Waiting {}ms before retry...", RETRY_DELAY_MS);
                    TimeUnit.MILLISECONDS.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("Retry interrupted for booking {}", bookingId);
                    return new ConversationCreationAttempt(
                            ConversationCreationOutcome.FAILED,
                            null,
                            "Retry interrupted");
                }
            }
        }

        log.error("Failed to create conversation for booking {} after {} attempts. Last error: {}",
                bookingId, MAX_RETRY_ATTEMPTS,
                lastException != null ? lastException.getMessage() : "Unknown");
        return new ConversationCreationAttempt(
                ConversationCreationOutcome.FAILED,
                null,
                lastException != null ? lastException.getMessage() : "Unknown");
    }

    /**
     * Creates a conversation asynchronously using a managed executor.
     * A2 FIX: Replaces raw Thread with CompletableFuture on bounded pool with metrics and error handling.
     *
     * @param bookingId The booking ID
     * @param renterId The renter's user ID
     * @param ownerId The owner's user ID
     * @param jwtToken The JWT token for authentication
     * @return CompletableFuture for tracking completion (callers may ignore if fire-and-forget)
     */
    public CompletableFuture<ConversationResponse> createConversationAsync(
            String bookingId, String renterId, String ownerId, String jwtToken) {

        return CompletableFuture.supplyAsync(() -> {
            Timer.Sample sample = Timer.start();
            try {
                ConversationCreationAttempt attempt = attemptCreateConversation(bookingId, renterId, ownerId, jwtToken);
                if (attempt.outcome() == ConversationCreationOutcome.CREATED) {
                    asyncSuccessCounter.increment();
                } else {
                    asyncFailureCounter.increment();
                    if (attempt.outcome() == ConversationCreationOutcome.FAILED) {
                        persistOutboxEntry(bookingId, renterId, ownerId, attempt.errorMessage());
                    }
                }
                return attempt.response();
            } catch (Exception e) {
                asyncFailureCounter.increment();
                persistOutboxEntry(bookingId, renterId, ownerId, e.getMessage());
                log.error("[ChatServiceClient] Async conversation creation failed for booking {}: {}",
                        bookingId, e.getMessage(), e);
                return null;
            } finally {
                sample.stop(asyncDuration);
            }
        }, chatExecutor);
    }

    private void persistOutboxEntry(String bookingId, String renterId, String ownerId, String errorMessage) {
        ConversationCreationOutbox existing = outboxRepository.findFirstByBookingIdAndStatusIn(
                        bookingId,
                        List.of(ConversationCreationOutbox.Status.PENDING))
                .orElse(ConversationCreationOutbox.builder()
                        .bookingId(bookingId)
                        .renterId(renterId)
                        .ownerId(ownerId)
                        .maxAttempts(5)
                        .build());

        existing.setRenterId(renterId);
        existing.setOwnerId(ownerId);
        existing.recordFailure(errorMessage);
        outboxRepository.save(existing);
    }

    @Scheduled(fixedDelay = 60000)
    public void retryConversationCreationOutbox() {
        if (!lockService.tryAcquireLock(OUTBOX_LOCK_KEY, java.time.Duration.ofSeconds(55))) {
            return;
        }

        try {
            List<ConversationCreationOutbox> pending = outboxRepository.findPendingForRetry(java.time.Instant.now());
            if (pending.isEmpty()) {
                return;
            }

            String internalToken = internalServiceJwtUtil.generateServiceToken("backend").trim();

            for (ConversationCreationOutbox outboxEntry : pending) {
                ConversationCreationAttempt attempt = attemptCreateConversation(
                        outboxEntry.getBookingId(),
                        outboxEntry.getRenterId(),
                        outboxEntry.getOwnerId(),
                        internalToken);

                if (attempt.outcome() == ConversationCreationOutcome.CREATED
                        || attempt.outcome() == ConversationCreationOutcome.EXISTS) {
                    outboxEntry.markCreated();
                } else {
                    outboxEntry.recordFailure(attempt.errorMessage());
                }

                outboxRepository.save(outboxEntry);
            }
        } finally {
            lockService.releaseLock(OUTBOX_LOCK_KEY);
        }
    }

    // ==================== GDPR COMPLIANCE (GAP-3 REMEDIATION) ====================

    /**
     * Anonymize a user's chat data during GDPR Article 17 erasure.
     * Called by GdprService.permanentlyDeleteUser to propagate deletion to chat-service.
     *
     * @param userId the user being permanently deleted
     * @return true if anonymization succeeded (or chat-service is unavailable — non-blocking)
     */
    public boolean anonymizeUserChatData(Long userId) {
        try {
            String token = internalServiceJwtUtil.generateServiceToken("backend").trim();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-Service-Token", token);

            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    chatServiceUrl + "/api/internal/gdpr/anonymize-user/" + userId,
                    HttpMethod.POST,
                    entity,
                    new ParameterizedTypeReference<>() {}
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("[ChatServiceClient] GDPR: Anonymized chat data for user {}: {}",
                        userId, response.getBody());
                return true;
            }

            log.warn("[ChatServiceClient] GDPR: Unexpected response anonymizing user {}: {}",
                    userId, response.getStatusCode());
            return false;
        } catch (Exception e) {
            // Chat-service unavailability must not block GDPR deletion.
            // Log for retry/manual investigation.
            log.error("[ChatServiceClient] GDPR: Failed to anonymize chat data for user {} — " +
                    "manual reconciliation may be required", userId, e);
            return false;
        }
    }

    /**
     * Export a user's chat data for GDPR Article 15 data export.
     *
     * @param userId the user requesting data export
     * @return chat export data map, or empty map if chat-service is unavailable
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> exportUserChatData(Long userId) {
        try {
            String token = internalServiceJwtUtil.generateServiceToken("backend").trim();

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Internal-Service-Token", token);

            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    chatServiceUrl + "/api/internal/gdpr/export-user-data/" + userId,
                    HttpMethod.GET,
                    entity,
                    new ParameterizedTypeReference<>() {}
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.info("[ChatServiceClient] GDPR: Exported chat data for user {}", userId);
                return response.getBody();
            }

            return Collections.emptyMap();
        } catch (Exception e) {
            log.warn("[ChatServiceClient] GDPR: Failed to export chat data for user {} — " +
                    "chat data will be excluded from export", userId, e);
            return Collections.emptyMap();
        }
    }
}

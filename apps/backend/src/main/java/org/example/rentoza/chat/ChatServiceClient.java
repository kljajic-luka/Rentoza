package org.example.rentoza.chat;

import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.chat.dto.ConversationResponse;
import org.example.rentoza.chat.dto.CreateConversationRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.TimeUnit;

/**
 * Client service for communicating with the Chat Microservice
 */
@Service
@Slf4j
public class ChatServiceClient {

    private final RestTemplate restTemplate;
    private final String chatServiceUrl;
    private static final int MAX_RETRY_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 3000;

    public ChatServiceClient(
            RestTemplate restTemplate,
            @Value("${chat.service.url:http://localhost:8081}") String chatServiceUrl
    ) {
        this.restTemplate = restTemplate;
        this.chatServiceUrl = chatServiceUrl;
    }

    /**
     * Creates a conversation in the chat microservice with retry logic
     * 
     * @param bookingId The booking ID
     * @param renterId The renter's user ID
     * @param ownerId The owner's user ID
     * @param jwtToken The JWT token for authentication
     * @return The created conversation response, or null if all attempts fail
     */
    public ConversationResponse createConversation(String bookingId, String renterId, String ownerId, String jwtToken) {
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
                // setBearerAuth adds the "Bearer " prefix automatically
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
                    return response.getBody();
                }
                
            } catch (HttpClientErrorException e) {
                // 4xx errors (client errors) - don't retry
                if (e.getStatusCode() == HttpStatus.CONFLICT) {
                    log.warn("Conversation already exists for booking {}", bookingId);
                    return null; // Conversation already exists, not an error
                }
                log.error("Client error creating conversation for booking {}: {} - {}", 
                        bookingId, e.getStatusCode(), e.getResponseBodyAsString());
                return null; // Don't retry on client errors
                
            } catch (HttpServerErrorException e) {
                // 5xx errors (server errors) - retry
                lastException = e;
                log.warn("Server error creating conversation for booking {} (attempt {}/{}): {} - {}", 
                        bookingId, attempt, MAX_RETRY_ATTEMPTS, e.getStatusCode(), e.getResponseBodyAsString());
                
            } catch (Exception e) {
                // Network errors, timeouts, etc. - retry
                lastException = e;
                log.warn("Error creating conversation for booking {} (attempt {}/{}): {}", 
                        bookingId, attempt, MAX_RETRY_ATTEMPTS, e.getMessage());
            }

            // If not the last attempt, wait before retrying
            if (attempt < MAX_RETRY_ATTEMPTS) {
                try {
                    log.info("Waiting {}ms before retry...", RETRY_DELAY_MS);
                    TimeUnit.MILLISECONDS.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("Retry interrupted for booking {}", bookingId);
                    return null;
                }
            }
        }

        // All attempts failed
        log.error("Failed to create conversation for booking {} after {} attempts. Last error: {}", 
                bookingId, MAX_RETRY_ATTEMPTS, 
                lastException != null ? lastException.getMessage() : "Unknown");
        return null;
    }

    /**
     * Creates a conversation asynchronously (fire and forget)
     * This is a non-blocking version that won't delay the booking creation
     */
    public void createConversationAsync(String bookingId, String renterId, String ownerId, String jwtToken) {

        new Thread(() -> createConversation(bookingId, renterId, ownerId, jwtToken)).start();
    }
}

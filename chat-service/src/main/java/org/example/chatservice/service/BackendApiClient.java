package org.example.chatservice.service;

import lombok.RequiredArgsConstructor;
import org.example.chatservice.dto.client.BookingDetailsDTO;
import org.example.chatservice.dto.client.UserDetailsDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class BackendApiClient {

    private static final Logger logger = LoggerFactory.getLogger(BackendApiClient.class);

    private final WebClient webClient;

    public Mono<BookingDetailsDTO> getBookingDetails(String bookingId) {
        logger.info("🔄 Fetching booking details for bookingId: {}", bookingId);
        
        return webClient.get()
                .uri("/api/bookings/{bookingId}", bookingId)
                .retrieve()
                .onStatus(
                    status -> status == HttpStatus.NOT_FOUND,
                    response -> {
                        logger.warn("⚠️ Booking [id={}] not found in backend (404) — will use fallback", bookingId);
                        return Mono.empty();
                    }
                )
                .bodyToMono(BookingDetailsDTO.class)
                .doOnSuccess(response -> {
                    if (response != null) {
                        logger.info("✅ Successfully enriched booking [{}] with real backend data", bookingId);
                    }
                })
                .doOnError(error -> {
                    if (!(error instanceof WebClientResponseException.NotFound)) {
                        logger.error("❌ Failed to fetch booking details for bookingId: {}. Error: {}", 
                                bookingId, error.getMessage());
                    }
                })
                .onErrorResume(e -> {
                    if (e instanceof WebClientResponseException.NotFound) {
                        logger.warn("⚠️ Booking [id={}] not found on main backend — returning fallback DTO", bookingId);
                    } else {
                        logger.warn("⚠️ Error fetching booking [id={}] — returning fallback DTO: {}", 
                                bookingId, e.getMessage());
                    }
                    return Mono.just(BookingDetailsDTO.createFallback(bookingId));
                });
    }

    public Mono<UserDetailsDTO> getUserDetails(Long userId) {
        logger.info("🔄 Fetching user details for userId: {}", userId);
        
        return webClient.get()
                .uri("/api/users/profile/{userId}", userId)
                .retrieve()
                .onStatus(
                    status -> status == HttpStatus.NOT_FOUND,
                    response -> {
                        logger.warn("⚠️ User [id={}] not found in backend", userId);
                        return Mono.empty();
                    }
                )
                .bodyToMono(UserDetailsDTO.class)
                .doOnSuccess(response -> {
                    if (response != null) {
                        logger.info("✅ Successfully fetched user details for userId: {}", userId);
                    }
                })
                .doOnError(error -> {
                    if (!(error instanceof WebClientResponseException.NotFound)) {
                        logger.error("❌ Failed to fetch user details for userId: {}. Error: {}", 
                                userId, error.getMessage());
                    }
                })
                .onErrorResume(e -> {
                    logger.warn("⚠️ Returning empty user details due to error for userId: {}", userId);
                    return Mono.empty();
                });
    }

    /**
     * Get conversation-safe booking summary for chat enrichment.
     * 
     * Purpose:
     * - Enrich chat conversations with booking context ("Future trip with 2020 BMW X5")
     * - Uses service-to-service authentication with user assertion
     * - NO PII: No renter/owner names, emails, phone numbers, pricing
     * 
     * Security:
     * - X-Internal-Service-Token: Automatically added by WebClient filter
     * - X-Act-As-User-Id: User ID asserting access (must be renter or owner)
     * - Main API validates: actAsUserId matches booking participant (RLS)
     * 
     * Fallback Strategy:
     * - 404 (booking not found): Return fallback DTO with "Unknown car"
     * - 403 (access denied): Return fallback DTO (user not authorized)
     * - Timeout/Network error: Return fallback DTO
     * - Fallback DTO has messagingAllowed=false for safety
     * 
     * @param bookingId Booking ID to fetch conversation view for
     * @param actAsUserId User ID asserting access (from authenticated user in chat)
     * @return Mono<BookingConversationDTO> with conversation-safe booking summary or fallback
     */
    public Mono<org.example.chatservice.dto.client.BookingConversationDTO> getConversationView(
            String bookingId, String actAsUserId) {
        logger.debug("[BackendAPI] Fetching conversation view: bookingId={}", bookingId);
        
        return webClient.get()
                .uri("/api/bookings/{bookingId}/conversation-view", bookingId)
                .header("X-Act-As-User-Id", actAsUserId)
                .retrieve()
                .onStatus(
                    status -> status == HttpStatus.NOT_FOUND,
                    response -> {
                        logger.debug("Booking {} not found (404)", bookingId);
                        return Mono.empty();
                    }
                )
                .onStatus(
                    status -> status == HttpStatus.FORBIDDEN,
                    response -> {
                        logger.debug("Access denied for booking {} (403)", bookingId);
                        return Mono.empty();
                    }
                )
                .bodyToMono(org.example.chatservice.dto.client.BookingConversationDTO.class)
                .doOnError(error -> {
                    if (error instanceof WebClientResponseException webClientError) {
                        logger.error("Failed to fetch conversation view for bookingId {}: HTTP {}", 
                                bookingId, webClientError.getStatusCode().value());
                    } else if (!(error instanceof WebClientResponseException.NotFound) && 
                               !(error instanceof WebClientResponseException.Forbidden)) {
                        logger.error("Failed to fetch conversation view for bookingId {}: {}", 
                                bookingId, error.getMessage());
                    }
                })
                .onErrorResume(e -> {
                    logger.debug("Using fallback for booking {}", bookingId);
                    return Mono.just(org.example.chatservice.dto.client.BookingConversationDTO.createFallback(bookingId));
                });
    }
    
    /**
     * Send a new message notification to the main backend.
     * 
     * This notifies the recipient (offline or inactive) that they have
     * a new chat message. The main backend handles notification delivery
     * via push, email, etc.
     * 
     * @param recipientId User ID to notify
     * @param bookingId Related booking ID
     * @param senderName Name of the message sender
     * @param messagePreview Preview of the message content
     * @return Mono<Void> completing when notification is sent
     */
    public Mono<Void> sendNewMessageNotification(Long recipientId, Long bookingId, String senderName, String messagePreview) {
        logger.info("📤 Sending NEW_MESSAGE notification to user {} for booking {}", recipientId, bookingId);
        
        return webClient.post()
                .uri("/api/internal/notifications/new-message")
                .bodyValue(new NewMessageNotificationRequest(recipientId, bookingId, senderName, messagePreview))
                .retrieve()
                .bodyToMono(Void.class)
                .doOnSuccess(v -> logger.info("✅ NEW_MESSAGE notification sent to user {}", recipientId))
                .doOnError(error -> logger.error("❌ Failed to send NEW_MESSAGE notification to user {}: {}",
                        recipientId, error.getMessage()))
                .onErrorResume(e -> {
                    // Don't fail the message send if notification fails
                    logger.warn("⚠️ Notification delivery failed, message was still sent");
                    return Mono.empty();
                });
    }
    
    /**
     * DTO for new message notification request.
     */
    public record NewMessageNotificationRequest(
            Long recipientId,
            Long bookingId,
            String senderName,
            String messagePreview
    ) {}
}

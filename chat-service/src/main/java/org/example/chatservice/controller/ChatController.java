package org.example.chatservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.chatservice.config.RateLimitConfig;
import org.example.chatservice.dto.*;
import org.example.chatservice.exception.ContentModerationException;
import org.example.chatservice.exception.RateLimitExceededException;
import org.example.chatservice.model.ConversationStatus;
import org.example.chatservice.security.ContentModerationFilter;
import org.example.chatservice.security.ContentModerationFilter.ContentModerationResult;
import org.example.chatservice.service.ChatService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for chat messaging.
 * 
 * Security features:
 * - Content moderation (blocks PII like phone/email/URL)
 * - Rate limiting (50/hour, 5/minute burst)
 * - Idempotency key support for retry-safe message sending
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ChatService chatService;
    private final ContentModerationFilter contentModerationFilter;
    private final RateLimitConfig rateLimitConfig;

    /**
     * Create a new conversation for a booking.
     * 
     * Security: The authenticated user must be either the renter or owner in the request.
     * This prevents users from creating conversations they shouldn't have access to.
     * 
     * @param request The conversation creation request
     * @param authentication The authenticated user
     * @return The created conversation
     */
    @PostMapping("/conversations")
    public ResponseEntity<ConversationDTO> createConversation(
            @Valid @RequestBody CreateConversationRequest request,
            Authentication authentication
    ) {
        String userId = authentication.getName();
        boolean isInternalService = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_INTERNAL_SERVICE"));
        
        log.info("[Security] {} creating conversation for booking {} (renter={}, owner={}, internal={})", 
                userId, request.getBookingId(), request.getRenterId(), request.getOwnerId(), isInternalService);
        
        ConversationDTO conversation = chatService.createConversationSecure(request, userId, isInternalService);
        return ResponseEntity.status(HttpStatus.CREATED).body(conversation);
    }

    @GetMapping("/conversations/{bookingId}")
    public ResponseEntity<ConversationDTO> getConversation(
            @PathVariable String bookingId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication authentication
    ) {
        String userId = authentication.getName();
        log.info("Getting conversation for booking {} by user {}", bookingId, userId);
        
        ConversationDTO conversation = chatService.getConversation(bookingId, userId, page, size);
        return ResponseEntity.ok(conversation);
    }

    /**
     * Send a message in a conversation.
     * 
     * Security checks applied:
     * 1. Rate limiting (50/hour, 5/min burst)
     * 2. Content moderation (blocks phone/email/URL)
     * 3. Idempotency key for duplicate detection (optional)
     * 
     * @param bookingId The booking ID for the conversation
     * @param idempotencyKey Optional UUID for retry-safe sending
     * @param request The message content
     * @param authentication The authenticated user
     * @return The created message
     */
    @PostMapping("/conversations/{bookingId}/messages")
    public ResponseEntity<MessageDTO> sendMessage(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SendMessageRequest request,
            Authentication authentication
    ) {
        String userId = authentication.getName();
        
        // 1. Rate limiting check
        if (!rateLimitConfig.tryConsume(userId)) {
            log.warn("[Security] Rate limit exceeded for user {} in booking {}", userId, bookingId);
            throw new RateLimitExceededException("Too many messages. Please wait a moment before sending more.");
        }
        
        // 2. Content moderation check
        ContentModerationResult moderationResult = contentModerationFilter.validateMessage(request.getContent());
        if (!moderationResult.isApproved()) {
            log.warn("[Security] Content moderation blocked message from user {} in booking {}: {}", 
                    userId, bookingId, moderationResult.getReason());
            throw new ContentModerationException(moderationResult.getReason());
        }
        
        // 3. Log with idempotency key if present (for future Redis idempotency integration)
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            log.debug("[Idempotency] Processing message with key {} from user {}", 
                    idempotencyKey.substring(0, Math.min(8, idempotencyKey.length())) + "...", userId);
            // TODO: Integrate with Redis idempotency service when available
        }
        
        log.info("Sending message in conversation for booking {} by user {}", bookingId, userId);
        
        MessageDTO message = chatService.sendMessage(bookingId, userId, request);
        
        // Add rate limit remaining to response headers for client awareness
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("X-RateLimit-Remaining", String.valueOf(rateLimitConfig.getRemainingTokens(userId)))
                .body(message);
    }

    @PutMapping("/conversations/{bookingId}/read")
    public ResponseEntity<Void> markAsRead(
            @PathVariable String bookingId,
            Authentication authentication
    ) {
        String userId = authentication.getName();
        log.info("Marking messages as read in conversation for booking {} by user {}", bookingId, userId);
        
        chatService.markMessagesAsRead(bookingId, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Update conversation status (e.g., ACTIVE -> CLOSED).
     * 
     * Security: Only conversation participants (renter or owner) or ADMIN can update status.
     * This prevents malicious users from closing other users' conversations.
     * 
     * @param bookingId The booking ID for the conversation
     * @param status The new status
     * @param authentication The authenticated user
     * @return 204 No Content on success
     */
    @PutMapping("/conversations/{bookingId}/status")
    public ResponseEntity<Void> updateConversationStatus(
            @PathVariable String bookingId,
            @RequestParam ConversationStatus status,
            Authentication authentication
    ) {
        String userId = authentication.getName();
        log.info("[Security] User {} attempting to update conversation status for booking {} to {}", 
                userId, bookingId, status);
        
        // Authorization check: verify user is participant or admin
        chatService.updateConversationStatusSecure(bookingId, status, userId);
        
        log.info("[Security] Conversation status updated successfully for booking {} by user {}", 
                bookingId, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationDTO>> getUserConversations(
            Authentication authentication
    ) {
        String userId = authentication.getName();
        log.info("Getting all conversations for user {}", userId);
        
        List<ConversationDTO> conversations = chatService.getUserConversations(userId);
        return ResponseEntity.ok(conversations);
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Chat service is running");
    }
}

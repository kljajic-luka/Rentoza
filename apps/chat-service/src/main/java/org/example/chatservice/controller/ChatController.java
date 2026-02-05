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
 * <h3>Security Features:</h3>
 * <ul>
 *   <li>Supabase ES256 JWT authentication (user tokens)</li>
 *   <li>Internal service HS256 authentication (service tokens)</li>
 *   <li>Content moderation (blocks PII like phone/email/URL)</li>
 *   <li>Rate limiting (50/hour, 5/minute burst)</li>
 *   <li>Idempotency key support for retry-safe message sending</li>
 * </ul>
 * 
 * <h3>User ID Handling:</h3>
 * <p>SecurityContext principal is stored as String (from SupabaseJwtAuthFilter or JwtAuthenticationFilter).</p>
 * <p>This controller safely converts to Long using {@link #extractUserId(Authentication)}.</p>
 * 
 * @author Rentoza Development Team
 * @since 2.0.0 (Supabase Migration)
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
     * Extract user ID from SecurityContext principal.
     * 
     * <p>Handles two authentication types:</p>
     * <ul>
     *   <li><strong>User Authentication</strong>: Principal is numeric user ID (Long as String)</li>
     *   <li><strong>Internal Service Authentication</strong>: Principal is service name (e.g., "chat-service")</li>
     * </ul>
     * 
     * <p>Internal service requests (those with ROLE_INTERNAL_SERVICE) should use
     * {@link #extractUserIdOrNull(Authentication)} instead, as they don't represent
     * a specific user and should use the InternalChatController.</p>
     * 
     * @param authentication Spring Security authentication
     * @return User ID as Long (for user authentication)
     * @throws IllegalArgumentException if principal is null, not numeric, or appears to be an internal service
     */
    private Long extractUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new IllegalArgumentException("Authentication principal is null");
        }
        
        Object principal = authentication.getPrincipal();
        
        // Handle case where principal is already Long (shouldn't happen, but be defensive)
        if (principal instanceof Long) {
            return (Long) principal;
        }
        
        // Principal is String - parse to Long
        if (principal instanceof String) {
            String principalStr = (String) principal;
            
            // Check if this looks like an internal service (non-numeric)
            if (!principalStr.matches("\\d+")) {
                log.error("Internal service principal '{}' passed to user endpoint. Use InternalChatController instead.", principalStr);
                throw new IllegalArgumentException(
                    "This endpoint requires user authentication. Use /api/internal for service-to-service calls.");
            }
            
            try {
                return Long.parseLong(principalStr);
            } catch (NumberFormatException e) {
                log.error("Invalid user ID format in principal: {}", principal);
                throw new IllegalArgumentException("Principal is not a valid numeric user ID: " + principal, e);
            }
        }
        
        // Unexpected type
        log.error("Unexpected principal type: {} value: {}", principal.getClass().getName(), principal);
        throw new IllegalArgumentException("Unexpected principal type: " + principal.getClass().getName());
    }

    /**
     * Extract user ID from SecurityContext principal, or null for internal services.
     * 
     * <p>Used by InternalChatController endpoints that accept both user and service authentication.</p>
     * 
     * @param authentication Spring Security authentication
     * @return User ID as Long (for user tokens), or null (for internal service tokens)
     */
    private Long extractUserIdOrNull(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            return null;
        }
        
        Object principal = authentication.getPrincipal();
        
        if (principal instanceof Long) {
            return (Long) principal;
        }
        
        if (principal instanceof String) {
            String principalStr = (String) principal;
            
            // If numeric, parse to Long
            if (principalStr.matches("\\d+")) {
                try {
                    return Long.parseLong(principalStr);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            
            // If non-numeric (internal service name), return null
            return null;
        }
        
        return null;
    }

    /**
     * Create a new conversation for a booking.
     * 
     * Security:
     * - User Auth: The authenticated user must be either the renter or owner in the request.
     * - Service Auth: Internal services with ROLE_INTERNAL_SERVICE can create any conversation.
     * 
     * @param request The conversation creation request
     * @param authentication The authenticated user or service
     * @return The created conversation
     */
    @PostMapping("/conversations")
    public ResponseEntity<ConversationDTO> createConversation(
            @Valid @RequestBody CreateConversationRequest request,
            Authentication authentication
    ) {
        // Check if this is an internal service call
        boolean isInternalService = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_INTERNAL_SERVICE"));
        
        // Extract user ID (null for internal services, Long for users)
        Long userId = isInternalService ? null : extractUserId(authentication);
        
        log.info("[Security] {} creating conversation for booking {} (renter={}, owner={}, internal={})", 
                userId != null ? "User " + userId : "Service", 
                request.getBookingId(), request.getRenterId(), request.getOwnerId(), isInternalService);
        
        // For internal services, use 0L as userId (will be ignored due to isInternalService flag)
        Long effectiveUserId = userId != null ? userId : 0L;
        
        ConversationDTO conversation = chatService.createConversationSecure(request, effectiveUserId, isInternalService);
        return ResponseEntity.status(HttpStatus.CREATED).body(conversation);
    }

    @GetMapping("/conversations/{bookingId}")
    public ResponseEntity<ConversationDTO> getConversation(
            @PathVariable String bookingId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication authentication
    ) {
        Long userId = extractUserId(authentication);
        Long bookingIdLong = Long.parseLong(bookingId);  // Parse String → Long
        log.info("Getting conversation for booking {} by user {}", bookingIdLong, userId);
        
        ConversationDTO conversation = chatService.getConversation(bookingIdLong, userId, page, size);
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
        Long userId = extractUserId(authentication);
        Long bookingIdLong = Long.parseLong(bookingId);  // Parse String → Long
        
        // 1. Rate limiting check
        if (!rateLimitConfig.tryConsume(userId)) {
            log.warn("[Security] Rate limit exceeded for user {} in booking {}", userId, bookingIdLong);
            throw new RateLimitExceededException("Too many messages. Please wait a moment before sending more.");
        }
        
        // 2. Content moderation check
        ContentModerationResult moderationResult = contentModerationFilter.validateMessage(request.getContent());
        if (!moderationResult.isApproved()) {
            log.warn("[Security] Content moderation blocked message from user {} in booking {}: {}", 
                    userId, bookingIdLong, moderationResult.getReason());
            throw ContentModerationException.fromViolations(
                    moderationResult.getReason(), 
                    moderationResult.getViolations()
            );
        }
        
        // 3. Log with idempotency key if present (for future Redis idempotency integration)
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            log.debug("[Idempotency] Processing message with key {} from user {}", 
                    idempotencyKey.substring(0, Math.min(8, idempotencyKey.length())) + "...", userId);
            // TODO: Integrate with Redis idempotency service when available
        }
        
        log.info("Sending message in conversation for booking {} by user {}", bookingIdLong, userId);
        
        MessageDTO message = chatService.sendMessage(bookingIdLong, userId, request);
        
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
        Long userId = extractUserId(authentication);
        Long bookingIdLong = Long.parseLong(bookingId);  // Parse String → Long
        log.info("Marking messages as read in conversation for booking {} by user {}", bookingIdLong, userId);
        
        chatService.markMessagesAsRead(bookingIdLong, userId);
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
        Long userId = extractUserId(authentication);
        Long bookingIdLong = Long.parseLong(bookingId);  // Parse String → Long
        log.info("[Security] User {} attempting to update conversation status for booking {} to {}", 
                userId, bookingIdLong, status);
        
        // Authorization check: verify user is participant or admin
        chatService.updateConversationStatusSecure(bookingIdLong, status, userId);
        
        log.info("[Security] Conversation status updated successfully for booking {} by user {}", 
                bookingIdLong, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationDTO>> getUserConversations(
            Authentication authentication
    ) {
        Long userId = extractUserId(authentication);
        log.info("Getting all conversations for user {}", userId);
        
        List<ConversationDTO> conversations = chatService.getUserConversations(userId);
        return ResponseEntity.ok(conversations);
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Chat service is running");
    }
}

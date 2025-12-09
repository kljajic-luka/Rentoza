package org.example.chatservice.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.chatservice.config.RateLimitConfig;
import org.example.chatservice.dto.MessageDTO;
import org.example.chatservice.dto.SendMessageRequest;
import org.example.chatservice.dto.TypingIndicatorDTO;
import org.example.chatservice.exception.ContentModerationException;
import org.example.chatservice.exception.RateLimitExceededException;
import org.example.chatservice.security.ContentModerationFilter;
import org.example.chatservice.security.ContentModerationFilter.ContentModerationResult;
import org.example.chatservice.service.ChatService;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * WebSocket controller for real-time chat messaging.
 * 
 * Handles:
 * - Real-time message sending via WebSocket
 * - Typing indicators
 * - Read receipt broadcast
 * 
 * Security:
 * - Rate limiting applied
 * - Content moderation applied
 * - Authorization via WebSocketSecurityInterceptor
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ChatWebSocketController {

    private final ChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ContentModerationFilter contentModerationFilter;
    private final RateLimitConfig rateLimitConfig;

    /**
     * Handle sending a message via WebSocket.
     * 
     * Client sends to: /app/chat/{bookingId}/send
     * Broadcast to: /topic/conversation/{bookingId}
     * 
     * @param bookingId The booking ID for the conversation
     * @param request The message content
     * @param headerAccessor STOMP header accessor for authentication
     */
    @MessageMapping("/chat/{bookingId}/send")
    public void sendMessage(
            @DestinationVariable String bookingId,
            @Payload SendMessageRequest request,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        Principal principal = headerAccessor.getUser();
        if (principal == null) {
            log.warn("[WS] No authenticated user for message send");
            return;
        }

        String userId = principal.getName();

        // Rate limiting check
        if (!rateLimitConfig.tryConsume(userId)) {
            log.warn("[WS] Rate limit exceeded for user {} in booking {}", userId, bookingId);
            sendError(bookingId, userId, "Too many messages. Please wait a moment.");
            return;
        }

        // Content moderation check
        ContentModerationResult moderationResult = contentModerationFilter.validateMessage(request.getContent());
        if (!moderationResult.isApproved()) {
            log.warn("[WS] Content moderation blocked message from user {} in booking {}", userId, bookingId);
            sendError(bookingId, userId, moderationResult.getReason());
            return;
        }

        try {
            // Send message through service (handles persistence and authorization)
            MessageDTO message = chatService.sendMessage(bookingId, userId, request);

            // Broadcast to conversation topic
            messagingTemplate.convertAndSend(
                    "/topic/conversation/" + bookingId,
                    message
            );

            log.debug("[WS] Message sent in conversation {}: {}", bookingId, message.getId());

        } catch (Exception e) {
            log.error("[WS] Error sending message in booking {}: {}", bookingId, e.getMessage());
            sendError(bookingId, userId, "Failed to send message. Please try again.");
        }
    }

    /**
     * Handle typing indicator.
     * 
     * Client sends to: /app/chat/{bookingId}/typing
     * Broadcast to: /topic/conversation/{bookingId}/typing
     * 
     * @param bookingId The booking ID for the conversation
     * @param typingIndicator The typing status
     * @param headerAccessor STOMP header accessor
     */
    @MessageMapping("/chat/{bookingId}/typing")
    public void handleTyping(
            @DestinationVariable String bookingId,
            @Payload TypingIndicatorDTO typingIndicator,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        Principal principal = headerAccessor.getUser();
        if (principal == null) {
            return;
        }

        String userId = principal.getName();
        
        // Set the user ID from authenticated principal (don't trust client)
        typingIndicator.setUserId(userId);

        // Broadcast typing indicator to conversation
        messagingTemplate.convertAndSend(
                "/topic/conversation/" + bookingId + "/typing",
                typingIndicator
        );

        log.trace("[WS] Typing indicator: {} isTyping={} in {}", 
                userId, typingIndicator.isTyping(), bookingId);
    }

    /**
     * Handle marking messages as read via WebSocket.
     * 
     * Client sends to: /app/chat/{bookingId}/read
     * Broadcast to: /topic/conversation/{bookingId}/read
     * 
     * @param bookingId The booking ID for the conversation
     * @param headerAccessor STOMP header accessor
     */
    @MessageMapping("/chat/{bookingId}/read")
    public void markAsRead(
            @DestinationVariable String bookingId,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        Principal principal = headerAccessor.getUser();
        if (principal == null) {
            return;
        }

        String userId = principal.getName();

        try {
            chatService.markMessagesAsRead(bookingId, userId);

            // Broadcast read receipt to conversation
            ReadReceiptBroadcast broadcast = new ReadReceiptBroadcast(userId, System.currentTimeMillis());
            messagingTemplate.convertAndSend(
                    "/topic/conversation/" + bookingId + "/read",
                    broadcast
            );

            log.debug("[WS] Read receipt broadcast for user {} in {}", userId, bookingId);

        } catch (Exception e) {
            log.error("[WS] Error marking messages as read in {}: {}", bookingId, e.getMessage());
        }
    }

    /**
     * Send error message to specific user.
     */
    private void sendError(String bookingId, String userId, String errorMessage) {
        ErrorMessage error = new ErrorMessage(errorMessage, System.currentTimeMillis());
        messagingTemplate.convertAndSendToUser(
                userId,
                "/queue/errors",
                error
        );
    }

    // Simple DTOs for WebSocket messages

    public record ReadReceiptBroadcast(String userId, long timestamp) {}

    public record ErrorMessage(String message, long timestamp) {}
}

package org.example.chatservice.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.chatservice.model.Conversation;
import org.example.chatservice.repository.ConversationRepository;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Optional;

/**
 * WebSocket security interceptor for STOMP subscriptions.
 * 
 * Validates that users can only subscribe to conversations they are participants in.
 * This prevents eavesdropping on other users' conversations.
 * 
 * Security mechanism:
 * 1. On SUBSCRIBE command, extract the destination (e.g., /topic/conversation/{bookingId})
 * 2. Check if the authenticated user is a participant in that conversation
 * 3. Reject subscription if not authorized
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketSecurityInterceptor implements ChannelInterceptor {

    private final ConversationRepository conversationRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        
        if (accessor == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        
        // Only validate SUBSCRIBE commands to topic destinations
        if (StompCommand.SUBSCRIBE.equals(command)) {
            String destination = accessor.getDestination();
            Principal principal = accessor.getUser();
            
            if (destination != null && principal != null) {
                validateSubscription(destination, principal.getName());
            } else if (destination != null && destination.startsWith("/topic/conversation/")) {
                // No principal available but trying to subscribe to conversation topic
                log.warn("[WS Security] Subscription attempt without authentication to: {}", destination);
                throw new IllegalArgumentException("Authentication required for conversation subscriptions");
            }
        }

        return message;
    }

    /**
     * Validate that the user is authorized to subscribe to the given destination.
     * 
     * @param destination The STOMP destination (e.g., /topic/conversation/{bookingId})
     * @param userId The authenticated user ID
     * @throws IllegalArgumentException if not authorized
     */
    private void validateSubscription(String destination, String userId) {
        // Check if this is a conversation-related topic
        if (destination.startsWith("/topic/conversation/")) {
            String bookingId = extractBookingId(destination);
            
            if (bookingId != null) {
                Optional<Conversation> conversation = conversationRepository.findByBookingId(bookingId);
                
                if (conversation.isEmpty()) {
                    log.warn("[WS Security] Subscription to non-existent conversation: user={}, booking={}", 
                            userId, bookingId);
                    throw new IllegalArgumentException("Conversation not found");
                }
                
                if (!conversation.get().isParticipant(userId)) {
                    log.warn("[WS Security] Unauthorized subscription attempt: user={}, booking={}, participants=[{}, {}]", 
                            userId, bookingId, 
                            conversation.get().getRenterId(), 
                            conversation.get().getOwnerId());
                    throw new IllegalArgumentException("Not authorized to subscribe to this conversation");
                }
                
                log.debug("[WS Security] Subscription authorized: user={}, booking={}", userId, bookingId);
            }
        }
        // Other destinations (e.g., broadcasting topics) are allowed
    }

    /**
     * Extract booking ID from destination path.
     * 
     * Handles various destination patterns:
     * - /topic/conversation/{bookingId}
     * - /topic/conversation/{bookingId}/status
     * - /topic/conversation/{bookingId}/typing
     * 
     * @param destination The STOMP destination
     * @return The booking ID, or null if not a conversation topic
     */
    private String extractBookingId(String destination) {
        // Pattern: /topic/conversation/{bookingId}[/optional-suffix]
        String prefix = "/topic/conversation/";
        
        if (!destination.startsWith(prefix)) {
            return null;
        }
        
        String remainder = destination.substring(prefix.length());
        
        // Handle paths with additional segments (e.g., /status, /typing)
        int slashIndex = remainder.indexOf('/');
        if (slashIndex > 0) {
            return remainder.substring(0, slashIndex);
        }
        
        return remainder.isEmpty() ? null : remainder;
    }
}

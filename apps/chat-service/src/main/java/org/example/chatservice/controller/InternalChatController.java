package org.example.chatservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.chatservice.dto.ConversationDTO;
import org.example.chatservice.dto.CreateConversationRequest;
import org.example.chatservice.model.ConversationStatus;
import org.example.chatservice.model.Message;
import org.example.chatservice.repository.ConversationRepository;
import org.example.chatservice.repository.MessageRepository;
import org.example.chatservice.service.ChatService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Internal Chat Controller - For Main Backend Service-to-Service Communication
 * 
 * <h3>Purpose:</h3>
 * <p>Provides internal API endpoints for main backend to manage conversations</p>
 * <p>Used for booking lifecycle events (APPROVED, COMPLETED, CANCELLED)</p>
 * 
 * <h3>Authentication:</h3>
 * <p>Requires ROLE_INTERNAL_SERVICE (validated via internal HS256 JWT)</p>
 * <p>NOT accessible to regular users (only service-to-service)</p>
 * 
 * <h3>Booking Authorization Strategy (Option B - MVP):</h3>
 * <ul>
 *   <li>Main backend creates conversation when booking APPROVED</li>
 *   <li>Main backend closes conversation when booking COMPLETED/CANCELLED</li>
 *   <li>Chat service trusts main backend (no additional validation)</li>
 * </ul>
 * 
 * <h3>Future Migration (Option A - Phase 2):</h3>
 * <p>See PHASE2_MIGRATION_PATH.md for migration to chat service validation</p>
 * 
 * @author Rentoza Development Team
 * @since 2.0.0 (Supabase Migration)
 */
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
@Slf4j
public class InternalChatController {

    private final ChatService chatService;
    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;

    /**
     * Create conversation (called by main backend when booking approved)
     * 
     * <p>Endpoint: POST /api/internal/conversations</p>
     * <p>Auth: Requires ROLE_INTERNAL_SERVICE</p>
     * 
     * <h3>Usage:</h3>
     * <pre>
     * POST /api/internal/conversations
     * X-Internal-Service-Token: &lt;HS256-token&gt;
     * {
     *   "bookingId": "booking-123",
     *   "renterId": "100",
     *   "ownerId": "200",
     *   "initialMessage": "Booking approved! You can now chat."
     * }
     * </pre>
     * 
     * <h3>When to Call:</h3>
     * <ul>
     *   <li>Main backend approves booking (status → APPROVED)</li>
     *   <li>Creates conversation for renter and owner</li>
     *   <li>Optionally sends initial system message</li>
     * </ul>
     * 
     * @param request Conversation creation request
     * @param authentication Spring Security authentication (contains service name)
     * @return 201 Created with ConversationDTO
     */
    @PostMapping("/conversations")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<ConversationDTO> createConversationInternal(
            @Valid @RequestBody CreateConversationRequest request,
            Authentication authentication
    ) {
        String serviceName = authentication != null ? authentication.getName() : "UNKNOWN";
        log.info("[Internal] Creating conversation for booking {} (called by service: {})", 
                request.getBookingId(), serviceName);

        // A3 FIX: Use secure method with isInternalService=true (authorization already enforced by @PreAuthorize)
        ConversationDTO conversation = chatService.createConversationSecure(request, 0L, true);

        log.info("[Internal] Conversation created successfully: conversationId={}, bookingId={}", 
                conversation.getId(), request.getBookingId());

        return ResponseEntity.status(HttpStatus.CREATED).body(conversation);
    }

    /**
     * Update conversation status (called when booking lifecycle changes)
     * 
     * <p>Endpoint: PUT /api/internal/conversations/{bookingId}/status</p>
     * <p>Auth: Requires ROLE_INTERNAL_SERVICE</p>
     * 
     * <h3>Usage:</h3>
     * <pre>
     * PUT /api/internal/conversations/booking-123/status?status=CLOSED
     * X-Internal-Service-Token: &lt;HS256-token&gt;
     * </pre>
     * 
     * <h3>When to Call:</h3>
     * <ul>
     *   <li>Booking completed (status → CLOSED)</li>
     *   <li>Booking cancelled (status → CLOSED)</li>
     * </ul>
     * 
     * <h3>Effect:</h3>
     * <ul>
     *   <li>Updates conversation status in database</li>
     *   <li>Broadcasts status change via WebSocket</li>
     *   <li>Prevents new messages (application-level check)</li>
     *   <li>Allows read-only access to conversation history</li>
     * </ul>
     * 
     * @param bookingId Booking ID (unique conversation identifier)
     * @param status New conversation status (PENDING, ACTIVE, CLOSED)
     * @param authentication Spring Security authentication
     * @return 204 No Content
     */
    @PutMapping("/conversations/{bookingId}/status")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<Void> updateConversationStatus(
            @PathVariable String bookingId,
            @RequestParam ConversationStatus status,
            Authentication authentication
    ) {
        String serviceName = authentication != null ? authentication.getName() : "UNKNOWN";
        log.info("[Internal] Updating conversation status: bookingId={}, newStatus={} (called by: {})", 
                bookingId, status, serviceName);

        // A3 FIX: Use secure method with isInternalService=true (participant check bypassed)
        Long bookingIdLong = Long.parseLong(bookingId);
        chatService.updateConversationStatusSecure(bookingIdLong, status, 0L, true);

        // Broadcast via WebSocket (handled internally by ChatService)
        log.info("[Internal] Conversation status updated successfully: bookingId={}, status={}", 
                bookingId, status);

        return ResponseEntity.noContent().build();
    }

    // ==================== GDPR COMPLIANCE (GAP-3 REMEDIATION) ====================

    /**
     * Anonymize all chat data for a deleted user.
     *
     * <p>Called by the main backend's GDPR deletion flow to propagate
     * Article 17 erasure to the chat service. Replaces message content
     * and unlinks sender identity.</p>
     *
     * @param userId the user being deleted
     * @return count of anonymized messages
     */
    @PostMapping("/gdpr/anonymize-user/{userId}")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    @Transactional
    public ResponseEntity<?> anonymizeUserData(
            @PathVariable Long userId,
            Authentication authentication
    ) {
        String serviceName = authentication != null ? authentication.getName() : "UNKNOWN";
        log.info("[GDPR] Anonymizing chat data for user {} (called by: {})", userId, serviceName);

        int anonymizedMessages = messageRepository.anonymizeMessagesBySenderId(userId);

        log.info("[GDPR] Anonymized {} messages for user {}", anonymizedMessages, userId);

        return ResponseEntity.ok(java.util.Map.of(
                "userId", userId,
                "anonymizedMessages", anonymizedMessages
        ));
    }

    /**
     * Export chat data for GDPR Article 15 data export.
     *
     * <p>Returns all messages sent by the user and all conversations
     * they participated in, for inclusion in the main backend's data
     * export response.</p>
     *
     * @param userId the user requesting data export
     * @return chat messages and conversation metadata
     */
    @GetMapping("/gdpr/export-user-data/{userId}")
    @PreAuthorize("hasRole('INTERNAL_SERVICE')")
    public ResponseEntity<?> exportUserChatData(
            @PathVariable Long userId,
            Authentication authentication
    ) {
        String serviceName = authentication != null ? authentication.getName() : "UNKNOWN";
        log.info("[GDPR] Exporting chat data for user {} (called by: {})", userId, serviceName);

        var messages = messageRepository.findBySenderIdOrderByTimestampAsc(userId);
        var conversations = conversationRepository.findByParticipant(userId);

        var messageExport = messages.stream()
                .map(m -> java.util.Map.of(
                        "messageId", m.getId(),
                        "conversationId", m.getConversationId(),
                        "content", m.getContent(),
                        "timestamp", m.getTimestamp().toString(),
                        "hasMedia", m.getMediaUrl() != null
                ))
                .toList();

        var conversationExport = conversations.stream()
                .map(c -> java.util.Map.of(
                        "conversationId", c.getId(),
                        "bookingId", c.getBookingId(),
                        "role", c.getRenterId().equals(userId) ? "RENTER" : "OWNER",
                        "status", c.getStatus().name(),
                        "createdAt", c.getCreatedAt().toString()
                ))
                .toList();

        log.info("[GDPR] Exported {} messages and {} conversations for user {}",
                messages.size(), conversations.size(), userId);

        return ResponseEntity.ok(java.util.Map.of(
                "userId", userId,
                "messages", messageExport,
                "conversations", conversationExport
        ));
    }
}

package org.example.chatservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.chatservice.config.RateLimitConfig;
import org.example.chatservice.dto.*;
import org.example.chatservice.exception.ContentModerationException;
import org.example.chatservice.exception.RateLimitExceededException;
import org.example.chatservice.model.ConversationStatus;
import org.example.chatservice.model.Message;
import org.example.chatservice.repository.MessageRepository;
import org.example.chatservice.security.ContentModerationFilter;
import org.example.chatservice.security.ContentModerationFilter.ContentModerationResult;
import org.example.chatservice.service.ChatService;
import org.example.chatservice.service.FileStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final FileStorageService fileStorageService;
    private final org.example.chatservice.service.IdempotencyService idempotencyService;
    private final MessageRepository messageRepository;

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

        // 2b. Log moderation flags for admin review queue (message still sent)
        List<String> moderationFlags = null;
        if (moderationResult.hasFlags()) {
            moderationFlags = moderationResult.getFlags();
            log.info("[Moderation] Message from user {} in booking {} flagged for admin review: {}", 
                    userId, bookingIdLong, moderationFlags);
        }
        
        // 3. Idempotency check - return cached result if duplicate request
        //    Keys are user-scoped to prevent cross-user collisions
        String scopedKey = null;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            scopedKey = userId + ":" + idempotencyKey;
            MessageDTO cachedResult = idempotencyService.getExistingResult(scopedKey);
            if (cachedResult != null) {
                log.info("[Idempotency] Returning cached result for key {} from user {}", 
                        idempotencyKey.substring(0, Math.min(8, idempotencyKey.length())) + "...", userId);
                return ResponseEntity.status(HttpStatus.CREATED).body(cachedResult);
            }
            // Try to claim the key (prevent concurrent duplicate processing)
            if (!idempotencyService.tryClaimKey(scopedKey)) {
                log.warn("[Idempotency] Concurrent duplicate request for key {} from user {}", 
                        idempotencyKey.substring(0, Math.min(8, idempotencyKey.length())) + "...", userId);
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .header("Retry-After", "1")
                        .build();
            }
        }
        
        log.info("Sending message in conversation for booking {} by user {}", bookingIdLong, userId);
        
        MessageDTO message;
        try {
            message = chatService.sendMessage(bookingIdLong, userId, request, moderationFlags);
        } catch (Exception e) {
            // Release the idempotency key so client can retry
            if (scopedKey != null) {
                idempotencyService.releaseKey(scopedKey);
            }
            throw e;
        }
        
        // Store result for idempotency
        if (scopedKey != null) {
            idempotencyService.storeResult(scopedKey, message);
        }
        
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

    // =========================================================================
    // Admin Dispute Transcript API (P1 - Turo Standard)
    // =========================================================================

    /**
     * Admin-only: List ALL conversations for admin oversight / dispute resolution.
     *
     * Security:
     * - Requires ADMIN role
     * - Returns all conversations regardless of participant
     * - Audit-logged
     */
    @GetMapping("/admin/conversations")
    public ResponseEntity<List<ConversationDTO>> getAdminConversations(
            Authentication authentication
    ) {
        Long adminUserId = extractUserId(authentication);

        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ADMIN"));
        if (!isAdmin) {
            log.warn("[Security] Non-admin user {} attempted to list all conversations", adminUserId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        log.info("[Admin][Audit] Admin user {} listing all conversations", adminUserId);
        List<ConversationDTO> conversations = chatService.getAllConversationsForAdmin();
        return ResponseEntity.ok(conversations);
    }

    /**
     * Admin-only: Get read-only conversation transcript for dispute resolution.
     * 
     * Security:
     * - Requires ADMIN role (checked via @PreAuthorize or role in token)
     * - Read-only access - admin cannot send messages through this endpoint
     * - All admin access is audit-logged
     * 
     * @param bookingId The booking ID for the conversation
     * @param authentication The authenticated admin user
     * @return Full conversation with all messages (unpaginated for dispute review)
     */
    @GetMapping("/admin/conversations/{bookingId}/transcript")
    public ResponseEntity<ConversationDTO> getAdminTranscript(
            @PathVariable String bookingId,
            Authentication authentication
    ) {
        Long adminUserId = extractUserId(authentication);
        Long bookingIdLong = Long.parseLong(bookingId);

        // Verify admin role
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ADMIN"));
        if (!isAdmin) {
            log.warn("[Security] Non-admin user {} attempted to access admin transcript for booking {}", 
                    adminUserId, bookingIdLong);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        log.info("[Admin][Audit] Admin user {} accessing transcript for booking {} (dispute resolution)", 
                adminUserId, bookingIdLong);

        // Fetch full transcript without participant check (admin override)
        ConversationDTO transcript = chatService.getConversationForAdmin(bookingIdLong);
        
        log.info("[Admin][Audit] Admin user {} retrieved {} messages for booking {}", 
                adminUserId, 
                transcript.getMessages() != null ? transcript.getMessages().size() : 0,
                bookingIdLong);
        
        return ResponseEntity.ok(transcript);
    }

    // =========================================================================
    // Attachment Upload/Retrieval (P1 - Turo Standard)
    // =========================================================================

    /**
     * Upload a file attachment for a conversation.
     * 
     * Validation:
     * - MIME type: image/jpeg, image/png, image/gif, image/webp, application/pdf
     * - Size: max 10MB
     * - User must be a conversation participant
     * 
     * @param bookingId The booking ID for the conversation
     * @param file The uploaded file
     * @param authentication The authenticated user
     * @return The URL to the uploaded file (to be used in mediaUrl field)
     */
    @PostMapping(value = "/conversations/{bookingId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAttachment(
            @PathVariable String bookingId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) {
        Long userId = extractUserId(authentication);
        Long bookingIdLong = Long.parseLong(bookingId);

        // Verify user is a participant
        if (!chatService.isUserParticipant(bookingIdLong, userId)) {
            log.warn("[Security] Non-participant user {} attempted to upload attachment for booking {}", 
                    userId, bookingIdLong);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(java.util.Map.of("error", "You are not a participant in this conversation"));
        }

        try {
            String fileUrl = fileStorageService.uploadAttachment(file, bookingIdLong, userId);
            log.info("[Upload] User {} uploaded attachment for booking {}: {}", userId, bookingIdLong, fileUrl);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(java.util.Map.of("url", fileUrl, "filename", file.getOriginalFilename()));
        } catch (IllegalArgumentException e) {
            log.warn("[Upload] Validation failed for user {} in booking {}: {}", userId, bookingIdLong, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("error", e.getMessage()));
        }
    }

    /**
     * Retrieve an uploaded attachment.
     * 
     * Security:
     * - Requires authentication (JWT)
     * - Extracts booking ID from path (booking-{id}/{file})
     * - Verifies user is a participant or admin before serving
     * - Uses Cache-Control: private (not public — these are private chat files)
     * 
     * @param request The HTTP request (for path extraction)
     * @param authentication The authenticated user
     * @return The file content with correct content type
     */
    @GetMapping("/attachments/**")
    public ResponseEntity<byte[]> getAttachment(
            jakarta.servlet.http.HttpServletRequest request,
            Authentication authentication
    ) {
        // Extract path after /api/attachments/
        String fullPath = request.getRequestURI();
        String prefix = "/api/attachments/";
        int prefixIndex = fullPath.indexOf(prefix);
        if (prefixIndex < 0) {
            return ResponseEntity.notFound().build();
        }
        String relativePath = fullPath.substring(prefixIndex + prefix.length());

        // SECURITY: Extract booking ID from path pattern "booking-{id}/{filename}"
        // and verify the requesting user is a participant or admin
        try {
            Long requestingUserId = extractUserId(authentication);
            boolean isAdmin = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ADMIN"));

            // Parse booking ID from directory name (e.g., "booking-123/uuid.jpg")
            String[] pathParts = relativePath.split("/");
            if (pathParts.length < 2 || !pathParts[0].startsWith("booking-")) {
                log.warn("[Security] Invalid attachment path format: {}", relativePath);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }
            Long bookingId = Long.parseLong(pathParts[0].substring("booking-".length()));

            // Authorization: user must be participant or admin
            if (!isAdmin && !chatService.isUserParticipant(bookingId, requestingUserId)) {
                log.warn("[Security] IDOR attempt: User {} tried to access attachment for booking {} (not a participant)",
                        requestingUserId, bookingId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            byte[] fileData = fileStorageService.getFile(relativePath);
            String contentType = fileStorageService.getContentType(relativePath);
            return ResponseEntity.ok()
                    .header("Content-Type", contentType)
                    .header("Cache-Control", "private, max-age=86400") // Private — chat files are not public
                    .header("X-Content-Type-Options", "nosniff")
                    .body(fileData);
        } catch (SecurityException e) {
            log.warn("[Security] Path traversal attempt: {}", relativePath);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (NumberFormatException e) {
            log.warn("[Security] Invalid booking ID in attachment path: {}", relativePath);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    // =========================================================================
    // Admin: Flagged Message Moderation Queue
    // =========================================================================

    /**
     * Admin-only: Get messages with moderation flags (review queue).
     * 
     * @param page Page number (0-indexed)
     * @param size Page size (max 50)
     * @param authentication The authenticated admin user
     * @return Paginated list of flagged messages with moderation details
     */
    @GetMapping("/admin/messages/flagged")
    public ResponseEntity<?> getFlaggedMessages(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication
    ) {
        Long adminUserId = extractUserId(authentication);
        
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ADMIN"));
        if (!isAdmin) {
            log.warn("[Security] Non-admin user {} attempted to access flagged messages queue", adminUserId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        
        if (size > 50) size = 50;
        
        log.info("[Admin][Audit] Admin user {} accessing flagged messages queue (page={}, size={})", 
                adminUserId, page, size);
        
        Page<Message> flaggedMessages = messageRepository.findFlaggedMessages(PageRequest.of(page, size));
        
        Page<MessageDTO> flaggedDTOs = flaggedMessages.map(msg -> MessageDTO.builder()
                .id(msg.getId())
                .conversationId(msg.getConversationId())
                .senderId(msg.getSenderId())
                .content(msg.getContent())
                .timestamp(msg.getTimestamp())
                .readBy(msg.getReadBy() != null ? msg.getReadBy() : Set.of())
                .mediaUrl(msg.getMediaUrl())
                .isOwnMessage(false)
                .sentAt(msg.getTimestamp())
                .moderationFlags(msg.getModerationFlags())
                .build());
        
        return ResponseEntity.ok(flaggedDTOs);
    }

    /**
     * Admin-only: Get count of flagged messages (for dashboard badge).
     */
    @GetMapping("/admin/messages/flagged/count")
    public ResponseEntity<?> getFlaggedMessageCount(Authentication authentication) {
        Long adminUserId = extractUserId(authentication);
        
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ADMIN"));
        if (!isAdmin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        
        long count = messageRepository.countFlaggedMessages();
        return ResponseEntity.ok(Map.of("count", count));
    }

    /**
     * Admin-only: Dismiss moderation flags on a message (mark as reviewed/OK).
     */
    @PostMapping("/admin/messages/{messageId}/dismiss-flags")
    public ResponseEntity<?> dismissFlags(
            @PathVariable Long messageId,
            Authentication authentication
    ) {
        Long adminUserId = extractUserId(authentication);
        
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ADMIN"));
        if (!isAdmin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        
        Message msg = messageRepository.findById(messageId).orElse(null);
        if (msg == null) {
            return ResponseEntity.notFound().build();
        }
        
        log.info("[Admin][Audit] Admin user {} dismissed moderation flags on message {} (flags were: {})", 
                adminUserId, messageId, msg.getModerationFlags());
        
        msg.setModerationFlags(null);
        messageRepository.save(msg);
        
        return ResponseEntity.ok(Map.of("message", "Flags dismissed"));
    }
}

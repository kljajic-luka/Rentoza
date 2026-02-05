package org.example.chatservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.chatservice.dto.*;
import org.example.chatservice.dto.client.BookingDetailsDTO;
import org.example.chatservice.dto.client.UserDetailsDTO;
import org.example.chatservice.exception.ConversationNotFoundException;
import org.example.chatservice.exception.ForbiddenException;
import org.example.chatservice.exception.MessagingNotAllowedException;
import org.example.chatservice.model.Conversation;
import org.example.chatservice.model.ConversationStatus;
import org.example.chatservice.model.Message;
import org.example.chatservice.repository.ConversationRepository;
import org.example.chatservice.repository.MessageRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final BackendApiClient backendApiClient;
    private final ConversationEnrichmentService enrichmentService;
    private final ReadReceiptService readReceiptService;

    /**
     * Create a conversation with authorization check.
     * The authenticated user must be either the renter or owner specified in the request,
     * OR the caller must be an internal service (ROLE_INTERNAL_SERVICE).
     * 
     * @param request The conversation creation request
     * @param authenticatedUserId The authenticated user/service making the request
     * @param isInternalService True if the caller has ROLE_INTERNAL_SERVICE authority
     * @return The created conversation DTO
     * @throws ForbiddenException if user is not the renter or owner and not an internal service
     */
    @Transactional
    public ConversationDTO createConversationSecure(CreateConversationRequest request, Long authenticatedUserId, boolean isInternalService) {
        // SECURITY: Internal services (main backend) are trusted to create conversations on behalf of users
        if (isInternalService) {
            log.info("[Security] INTERNAL SERVICE {} creating conversation for booking {} on behalf of users",
                    authenticatedUserId, request.getBookingId());
            return createConversation(request);
        }
        
        // SECURITY: Verify the authenticated user is one of the participants
        if (!Objects.equals(authenticatedUserId, request.getRenterId()) && 
            !Objects.equals(authenticatedUserId, request.getOwnerId())) {
            log.warn("[Security] UNAUTHORIZED: User {} attempted to create conversation for booking {} " +
                    "but is neither renter ({}) nor owner ({})",
                    authenticatedUserId, request.getBookingId(), request.getRenterId(), request.getOwnerId());
            throw new ForbiddenException("You are not authorized to create a conversation for this booking");
        }
        
        return createConversation(request);
    }

    /**
     * @deprecated Use {@link #createConversationSecure(CreateConversationRequest, String)} instead.
     * This method lacks authorization checks and should only be used for internal/system operations.
     */
    @Deprecated
    @Transactional
    public ConversationDTO createConversation(CreateConversationRequest request) {
        // Check if conversation already exists
        if (conversationRepository.existsByBookingId(request.getBookingId())) {
            throw new IllegalStateException("Conversation already exists for booking: " + request.getBookingId());
        }

        Conversation conversation = Conversation.builder()
                .bookingId(request.getBookingId())
                .renterId(request.getRenterId())
                .ownerId(request.getOwnerId())
                .status(ConversationStatus.PENDING)
                .build();

        conversation = conversationRepository.save(conversation);
        log.info("Created conversation {} for booking {}", conversation.getId(), request.getBookingId());

        // Send initial message if provided
        if (request.getInitialMessage() != null && !request.getInitialMessage().isBlank()) {
            Message initialMessage = Message.builder()
                    .conversationId(conversation.getId())
                    .senderId(request.getRenterId())
                    .content(request.getInitialMessage())
                    .build();
            messageRepository.save(initialMessage);
            conversation.setLastMessageAt(LocalDateTime.now());
            conversationRepository.save(conversation);
        }

        return toDTO(conversation, request.getRenterId());
    }

    @Transactional(readOnly = true)
    public ConversationDTO getConversation(Long bookingId, Long userId, int page, int size) {
        Conversation conversation = conversationRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new ConversationNotFoundException("Conversation not found for booking: " + bookingId));

        // Verify user is participant
        if (!conversation.isParticipant(userId)) {
            throw new ForbiddenException("You are not a participant in this conversation");
        }

        // Get paginated messages (newest first, then reverse for display)
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "timestamp"));
        Page<Message> messagesPage = messageRepository.findByConversationIdOrderByTimestampDesc(
                conversation.getId(), pageable);

        List<MessageDTO> messages = messagesPage.getContent().stream()
                .map(msg -> toMessageDTO(msg, userId))
                .collect(Collectors.toList());

        // Reverse to show oldest first
        java.util.Collections.reverse(messages);

        ConversationDTO dto = toDTO(conversation, userId);
        dto.setMessages(messages);
        dto.setUnreadCount(messageRepository.countUnreadMessages(conversation.getId(), userId));

        // Enrich with backend data (pass userId for RLS validation)
        return enrichDtoWithBackendData(dto, userId);
    }

    @Transactional
    public MessageDTO sendMessage(Long bookingId, Long userId, SendMessageRequest request) {
        Conversation conversation = conversationRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new ConversationNotFoundException("Conversation not found for booking: " + bookingId));

        // Verify user is participant
        if (!conversation.isParticipant(userId)) {
            throw new ForbiddenException("You are not a participant in this conversation");
        }

        // Check if messaging is allowed
        if (!conversation.isMessagingAllowed()) {
            throw new MessagingNotAllowedException("Messaging is not allowed in this conversation state");
        }

        Message message = Message.builder()
                .conversationId(conversation.getId())
                .senderId(userId)
                .content(request.getContent())
                .mediaUrl(request.getMediaUrl())
                .build();

        // ✅ CRITICAL FIX: Save in correct order
        // 1. Save message to get database-generated ID
        message = messageRepository.save(message);

        // 2. Mark as read by sender (now message.id exists)
        message.markAsReadBy(userId);

        // 3. Save again to persist the read receipt
        message = messageRepository.save(message);

        // Update conversation last message time
        conversation.setLastMessageAt(LocalDateTime.now());
        conversationRepository.save(conversation);

        log.info("Message sent in conversation {} by user {}", conversation.getId(), userId);

        // Send real-time notification via WebSocket
        // CRITICAL: Use toMessageDTOForBroadcast() instead of toMessageDTO()
        // This prevents the inverted message bug where recipients see isOwnMessage=true
        // for messages they didn't send. Frontend will recalculate isOwnMessage locally.
        MessageDTO broadcastDTO = toMessageDTOForBroadcast(message);
        messagingTemplate.convertAndSend(
                "/topic/conversation/" + bookingId,
                broadcastDTO
        );
        
        // Send NEW_MESSAGE notification to the other participant (for offline users)
        Long recipientId = userId.equals(conversation.getRenterId()) 
                ? conversation.getOwnerId() 
                : conversation.getRenterId();
        String messagePreview = request.getContent().length() > 50 
                ? request.getContent().substring(0, 47) + "..." 
                : request.getContent();
        backendApiClient.sendNewMessageNotification(
                recipientId, 
                bookingId, 
                "Korisnik", // Sender name - could be enriched
                messagePreview
        ).subscribe(); // Fire and forget - don't block message delivery

        // Return the correct DTO to the sender with isOwnMessage=true
        MessageDTO senderDTO = toMessageDTO(message, userId);
        return senderDTO;
    }

    @Transactional
    public void markMessagesAsRead(Long bookingId, Long userId) {
        Conversation conversation = conversationRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new ConversationNotFoundException("Conversation not found for booking: " + bookingId));

        if (!conversation.isParticipant(userId)) {
            throw new ForbiddenException("You are not a participant in this conversation");
        }

        // Use ReadReceiptService for proper persistence to message_read_by table
        int markedCount = readReceiptService.markMessagesAsRead(conversation.getId(), userId);

        log.info("Marked {} messages as read in conversation {} by user {}", 
                markedCount, conversation.getId(), userId);
    }

    /**
     * Check if a user is a participant in the conversation.
     * 
     * @param bookingId The booking ID for the conversation
     * @param userId The user ID to check
     * @return true if the user is a participant (renter or owner)
     */
    @Transactional(readOnly = true)
    public boolean isUserParticipant(Long bookingId, Long userId) {
        return conversationRepository.findByBookingId(bookingId)
                .map(conversation -> conversation.isParticipant(userId))
                .orElse(false);
    }

    /**
     * Update conversation status with authorization check.
     * Only participants of the conversation can update its status.
     * 
     * Production security: This method enforces row-level security by verifying
     * the requesting user is either the renter or owner of the conversation.
     * 
     * @param bookingId The booking ID for the conversation
     * @param status The new status to set
     * @param userId The authenticated user making the request
     * @throws ConversationNotFoundException if conversation doesn't exist
     * @throws ForbiddenException if user is not a participant
     */
    @Transactional
    public void updateConversationStatusSecure(Long bookingId, ConversationStatus status, Long userId) {
        Conversation conversation = conversationRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new ConversationNotFoundException("Conversation not found for booking: " + bookingId));

        // SECURITY: Verify user is a participant before allowing status update
        if (!conversation.isParticipant(userId)) {
            log.warn("[Security] UNAUTHORIZED: User {} attempted to update status for conversation {} (booking {}). " +
                    "User is not a participant (renter={}, owner={})",
                    userId, conversation.getId(), bookingId, conversation.getRenterId(), conversation.getOwnerId());
            throw new ForbiddenException("You are not authorized to update this conversation's status");
        }

        // Additional validation: prevent closing conversations with active disputes
        // (future enhancement: check dispute status from main backend)
        
        ConversationStatus previousStatus = conversation.getStatus();
        conversation.setStatus(status);
        conversationRepository.save(conversation);

        log.info("[Security] Conversation {} status changed from {} to {} by authorized user {}", 
                conversation.getId(), previousStatus, status, userId);

        // Notify participants via WebSocket
        messagingTemplate.convertAndSend(
                "/topic/conversation/" + bookingId + "/status",
                status.name()
        );
    }

    /**
     * @deprecated Use {@link #updateConversationStatusSecure(String, ConversationStatus, String)} instead.
     * This method lacks authorization checks and should only be used for internal/system operations.
     */
    @Deprecated
    @Transactional
    public void updateConversationStatus(Long bookingId, ConversationStatus status) {
        Conversation conversation = conversationRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new ConversationNotFoundException("Conversation not found for booking: " + bookingId));

        conversation.setStatus(status);
        conversationRepository.save(conversation);

        log.info("Conversation {} status updated to {} (internal operation)", conversation.getId(), status);

        // Notify participants via WebSocket
        messagingTemplate.convertAndSend(
                "/topic/conversation/" + bookingId + "/status",
                status.name()
        );
    }

    @Transactional(readOnly = true)
    public List<ConversationDTO> getUserConversations(Long userId) {
        List<Conversation> conversations = conversationRepository.findByParticipant(userId);
        return conversations.stream()
                .map(conv -> {
                    ConversationDTO dto = toDTO(conv, userId);
                    dto.setUnreadCount(messageRepository.countUnreadMessages(conv.getId(), userId));
                    
                    // Fetch last message for preview
                    Message lastMessage = messageRepository.findFirstByConversationIdOrderByTimestampDesc(conv.getId());
                    if (lastMessage != null) {
                        String content = lastMessage.getContent();
                        // Truncate to 100 chars for preview
                        dto.setLastMessageContent(content != null && content.length() > 100 
                                ? content.substring(0, 100) + "..." 
                                : content);
                    }
                    
                    return enrichDtoWithBackendData(dto, userId);
                })
                .collect(Collectors.toList());
    }

    /**
     * Enrich conversation DTO with booking and user details from main backend.
     * Uses new ConversationEnrichmentService with caching and resilience patterns.
     * 
     * Security: actAsUserId is the authenticated user making the request
     * RLS: Main API validates that actAsUserId is a participant (renter or owner)
     * 
     * @param dto ConversationDTO to enrich
     * @param userId Authenticated user ID (for RLS validation on Main API)
     */
    private ConversationDTO enrichDtoWithBackendData(ConversationDTO dto, Long userId) {
        long startTime = System.currentTimeMillis();

        try {
            log.debug("[Enrichment] Starting for conversation={}, bookingId={}", dto.getId(), dto.getBookingId());

            // CRITICAL: Use authenticated userId (current user) as actAsUserId for RLS validation
            // The authenticated user must be either the renter OR owner of the conversation/booking
            // Main API will validate: actAsUserId == booking.renterId OR actAsUserId == booking.ownerId
            String actAsUserId = String.valueOf(userId);

            // Fetch booking conversation view and user details in parallel
            Mono<org.example.chatservice.dto.client.BookingConversationDTO> bookingConversationMono =
                    enrichmentService.enrichConversation(String.valueOf(dto.getBookingId()), actAsUserId);

            Mono<UserDetailsDTO> renterDetailsMono = backendApiClient.getUserDetails(dto.getRenterId());

            Mono<UserDetailsDTO> ownerDetailsMono = backendApiClient.getUserDetails(dto.getOwnerId());

            // FIX: Assign the result of the synchronous block operation to a variable
            ConversationDTO enrichedDto = Mono.zip(bookingConversationMono, renterDetailsMono, ownerDetailsMono)
                    .map(tuple -> {
                        org.example.chatservice.dto.client.BookingConversationDTO bookingConv = tuple.getT1();
                        UserDetailsDTO renter = tuple.getT2();
                        UserDetailsDTO owner = tuple.getT3();

                        // Check if booking enrichment is a fallback (UNAVAILABLE status)
                        boolean isBookingFallback = bookingConv != null &&
                                "UNAVAILABLE".equalsIgnoreCase(bookingConv.getTripStatus());

                        // Enrich with booking conversation data (from new secure endpoint)
                        if (bookingConv != null && !isBookingFallback) {
                            // Set car details from BookingConversationDTO
                            dto.setCarBrand(bookingConv.getCarBrand() != null
                                    ? bookingConv.getCarBrand() : "Unknown");
                            dto.setCarModel(bookingConv.getCarModel() != null
                                    ? bookingConv.getCarModel() : "Unknown");
                            dto.setCarYear(bookingConv.getCarYear() != null
                                    ? bookingConv.getCarYear() : 0);
                            dto.setCarImageUrl(bookingConv.getCarImageUrl()); // Nullable

                            // Set trip dates
                            if (bookingConv.getStartDate() != null) {
                                dto.setStartDate(bookingConv.getStartDate().toString());
                            }
                            if (bookingConv.getEndDate() != null) {
                                dto.setEndDate(bookingConv.getEndDate().toString());
                            }

                            // Use computed trip status from BookingConversationDTO
                            dto.setTripStatus(bookingConv.getTripStatus() != null
                                    ? bookingConv.getTripStatus() : "Unknown");

                            // Update messaging allowed from BookingConversationDTO
                            dto.setMessagingAllowed(bookingConv.isMessagingAllowed());
                            
                            // Set profile picture URLs from BookingConversationDTO
                            if (bookingConv.getRenterProfilePicUrl() != null) {
                                dto.setRenterProfilePicUrl(bookingConv.getRenterProfilePicUrl());
                            }
                            if (bookingConv.getOwnerProfilePicUrl() != null) {
                                dto.setOwnerProfilePicUrl(bookingConv.getOwnerProfilePicUrl());
                            }
                            
                            // Set user names from BookingConversationDTO if available
                            if (bookingConv.getRenterName() != null) {
                                dto.setRenterName(bookingConv.getRenterName());
                            }
                            if (bookingConv.getOwnerName() != null) {
                                dto.setOwnerName(bookingConv.getOwnerName());
                            }
                        } else {
                            // Booking not found or is fallback - mark as unavailable
                            dto.setCarBrand("Unknown");
                            dto.setCarModel("Unknown");
                            dto.setCarYear(0);
                            dto.setCarImageUrl(null);
                            dto.setTripStatus("UNAVAILABLE");
                            dto.setMessagingAllowed(false);

                            if (isBookingFallback) {
                                log.warn("⚠️ Enrichment fallback used for bookingId={} (not found or access denied)",
                                        dto.getBookingId());
                            }
                        }

                        // Enrich with user names (with fallbacks)
                        if (renter != null && renter.getFirstName() != null) {
                            String lastName = renter.getLastName() != null ? " " + renter.getLastName() : "";
                            dto.setRenterName(renter.getFirstName() + lastName);
                        } else {
                            dto.setRenterName("Renter");
                        }

                        if (owner != null && owner.getFirstName() != null) {
                            String lastName = owner.getLastName() != null ? " " + owner.getLastName() : "";
                            dto.setOwnerName(owner.getFirstName() + lastName);
                        } else {
                            dto.setOwnerName("Owner");
                        }

                        // Log enrichment result
                        long latency = System.currentTimeMillis() - startTime;
                        if (isBookingFallback) {
                            log.warn("Enriched conversation with fallback: conversationId={}, bookingId={}, latency={}ms",
                                    dto.getId(), dto.getBookingId(), latency);
                        } else {
                            log.info("Enriched conversation: conversationId={}, bookingId={}, tripStatus={}, car={} {} {}, latency={}ms",
                                    dto.getId(),
                                    dto.getBookingId(),
                                    dto.getTripStatus(),
                                    dto.getCarYear() > 0 ? dto.getCarYear() : "",
                                    dto.getCarBrand(),
                                    dto.getCarModel(),
                                    latency);
                        }

                        // The map returns the updated DTO object
                        return dto;
                    })
                    .onErrorReturn(dto) // Return DTO with safe defaults on error
                    .block(); // Blocks execution and returns the result

            return enrichedDto; // Return the enriched result

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            log.error("Error enriching conversation {} (latency={}ms): {}",
                    dto.getId(), latency, e.getMessage(), e);

            // Ensure safe defaults are set even on exception
            if (dto.getRenterName() == null) dto.setRenterName("Renter");
            if (dto.getOwnerName() == null) dto.setOwnerName("Owner");
            if (dto.getCarBrand() == null) dto.setCarBrand("Unknown");
            if (dto.getCarModel() == null) dto.setCarModel("Unknown");
            if (dto.getCarYear() == null) dto.setCarYear(0);
            if (dto.getTripStatus() == null) dto.setTripStatus("Unknown");
            dto.setMessagingAllowed(false); // Safe default on error

            return dto;
        }
    }

    /**
     * Calculate trip status based on start and end dates
     */
    private String calculateTripStatus(java.time.LocalDate startDate, java.time.LocalDate endDate) {
        if (startDate == null || endDate == null) {
            return "Unknown";
        }
        
        java.time.LocalDate today = java.time.LocalDate.now();
        
        if (today.isBefore(startDate)) {
            return "Future";
        } else if (today.isAfter(endDate)) {
            return "Past";
        } else {
            return "Current";
        }
    }

    private ConversationDTO toDTO(Conversation conversation, Long userId) {
        return ConversationDTO.builder()
                .id(conversation.getId())
                .bookingId(conversation.getBookingId())
                .renterId(conversation.getRenterId())
                .ownerId(conversation.getOwnerId())
                .status(conversation.getStatus())
                .createdAt(conversation.getCreatedAt())
                // Use lastMessageAt if available, otherwise fall back to createdAt
                .lastMessageAt(conversation.getLastMessageAt() != null 
                        ? conversation.getLastMessageAt() 
                        : conversation.getCreatedAt())
                .messagingAllowed(conversation.isMessagingAllowed())
                // Initialize with safe defaults - will be enriched later
                .renterName("Renter")
                .ownerName("Owner")
                .carBrand("Unknown")
                .carModel("Unknown")
                .carYear(0)
                .tripStatus("Unknown")
                .build();
    }

    /**
     * Convert Message to MessageDTO for the specified user.
     * 
     * Used for:
     * - Initial conversation load (REST API response)
     * - Direct HTTP responses to the requesting user
     * 
     * The isOwnMessage flag is calculated relative to the provided userId.
     * This is correct for API responses because userId is the requester.
     * 
     * @param message The message to convert
     * @param userId The ID of the user requesting the message (for isOwnMessage calculation)
     * @return MessageDTO with isOwnMessage correctly set for this user
     */
    private MessageDTO toMessageDTO(Message message, Long userId) {
        boolean isRead = message.getReadBy() != null && !message.getReadBy().isEmpty();
        
        return MessageDTO.builder()
                .id(message.getId())
                .conversationId(message.getConversationId())
                .senderId(message.getSenderId())
                .content(message.getContent())
                .timestamp(message.getTimestamp())
                .readBy(message.getReadBy() != null ? message.getReadBy() : Set.of())
                .mediaUrl(message.getMediaUrl())
                .isOwnMessage(message.getSenderId().equals(userId))
                // Ensure all timestamp fields are non-null
                .sentAt(message.getTimestamp()) // Sent time is the creation timestamp
                .deliveredAt(message.getTimestamp()) // For now, consider delivered immediately
                .readAt(isRead ? message.getTimestamp() : null) // Only set if actually read
                .build();
    }

    /**
     * Convert Message to MessageDTO for WebSocket broadcast.
     * 
     * CRITICAL: This method does NOT set isOwnMessage flag.
     * Instead, it defaults isOwnMessage to FALSE.
     * 
     * WHY:
     * - WebSocket broadcasts to MULTIPLE users (sender + recipients)
     * - The isOwnMessage flag is USER-SPECIFIC (different for each receiver)
     * - Broadcasting a pre-calculated flag assumes all receivers are the SENDER
     * - This causes the inverted message bug where recipients see their own flag as true
     * 
     * SOLUTION:
     * - Broadcast only the immutable facts: id, senderId, content, etc.
     * - Let the frontend calculate isOwnMessage by comparing senderId with currentUserId
     * - Frontend correctly recalculates for each user based on their context
     * 
     * Used for:
     * - WebSocket topic broadcasts (/topic/conversation/X)
     * - Any message sent to multiple recipients
     * 
     * @param message The message to convert
     * @return MessageDTO with isOwnMessage = false (frontend will recalculate)
     */
    private MessageDTO toMessageDTOForBroadcast(Message message) {
        boolean isRead = message.getReadBy() != null && !message.getReadBy().isEmpty();
        
        return MessageDTO.builder()
                .id(message.getId())
                .conversationId(message.getConversationId())
                .senderId(message.getSenderId())  // ✓ Include who sent it (immutable fact)
                .content(message.getContent())
                .timestamp(message.getTimestamp())
                .readBy(message.getReadBy() != null ? message.getReadBy() : Set.of())
                .mediaUrl(message.getMediaUrl())
                .isOwnMessage(false)  // ✓ Always false on broadcast - frontend will recalculate based on currentUserId
                // Ensure all timestamp fields are non-null
                .sentAt(message.getTimestamp())
                .deliveredAt(message.getTimestamp())
                .readAt(isRead ? message.getTimestamp() : null)
                .build();
    }
}

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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final SimpMessagingTemplate messagingTemplate;

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
    public ConversationDTO getConversation(String bookingId, String userId, int page, int size) {
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

        return dto;
    }

    @Transactional
    public MessageDTO sendMessage(String bookingId, String userId, SendMessageRequest request) {
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

        // Sender automatically reads their own message
        message.markAsReadBy(userId);

        message = messageRepository.save(message);

        // Update conversation last message time
        conversation.setLastMessageAt(LocalDateTime.now());
        conversationRepository.save(conversation);

        log.info("Message sent in conversation {} by user {}", conversation.getId(), userId);

        // Send real-time notification via WebSocket
        MessageDTO messageDTO = toMessageDTO(message, userId);
        messagingTemplate.convertAndSend(
                "/topic/conversation/" + bookingId,
                messageDTO
        );

        return messageDTO;
    }

    @Transactional
    public void markMessagesAsRead(String bookingId, String userId) {
        Conversation conversation = conversationRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new ConversationNotFoundException("Conversation not found for booking: " + bookingId));

        if (!conversation.isParticipant(userId)) {
            throw new ForbiddenException("You are not a participant in this conversation");
        }

        // Get all messages and mark as read
        Pageable pageable = PageRequest.of(0, 100);
        Page<Message> messages = messageRepository.findByConversationIdOrderByTimestampDesc(
                conversation.getId(), pageable);

        messages.getContent().forEach(msg -> {
            if (!msg.getSenderId().equals(userId) && !msg.isReadBy(userId)) {
                msg.markAsReadBy(userId);
                messageRepository.save(msg);
            }
        });

        log.info("Messages marked as read in conversation {} by user {}", conversation.getId(), userId);
    }

    @Transactional
    public void updateConversationStatus(String bookingId, ConversationStatus status) {
        Conversation conversation = conversationRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new ConversationNotFoundException("Conversation not found for booking: " + bookingId));

        conversation.setStatus(status);
        conversationRepository.save(conversation);

        log.info("Conversation {} status updated to {}", conversation.getId(), status);

        // Notify participants via WebSocket
        messagingTemplate.convertAndSend(
                "/topic/conversation/" + bookingId + "/status",
                status.name()
        );
    }

    @Transactional(readOnly = true)
    public List<ConversationDTO> getUserConversations(String userId) {
        List<Conversation> conversations = conversationRepository.findByParticipant(userId);
        return conversations.stream()
                .map(conv -> {
                    ConversationDTO dto = toDTO(conv, userId);
                    dto.setUnreadCount(messageRepository.countUnreadMessages(conv.getId(), userId));
                    return dto;
                })
                .collect(Collectors.toList());
    }

    private ConversationDTO toDTO(Conversation conversation, String userId) {
        return ConversationDTO.builder()
                .id(conversation.getId())
                .bookingId(conversation.getBookingId())
                .renterId(conversation.getRenterId())
                .ownerId(conversation.getOwnerId())
                .status(conversation.getStatus())
                .createdAt(conversation.getCreatedAt())
                .lastMessageAt(conversation.getLastMessageAt())
                .messagingAllowed(conversation.isMessagingAllowed())
                .build();
    }

    private MessageDTO toMessageDTO(Message message, String userId) {
        return MessageDTO.builder()
                .id(message.getId())
                .conversationId(message.getConversationId())
                .senderId(message.getSenderId())
                .content(message.getContent())
                .timestamp(message.getTimestamp())
                .readBy(message.getReadBy())
                .mediaUrl(message.getMediaUrl())
                .isOwnMessage(message.getSenderId().equals(userId))
                .build();
    }
}

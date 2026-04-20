package org.example.chatservice.service;

import org.example.chatservice.dto.MessageDTO;
import org.example.chatservice.dto.SendMessageRequest;
import org.example.chatservice.exception.ForbiddenException;
import org.example.chatservice.exception.MessagingNotAllowedException;
import org.example.chatservice.model.Conversation;
import org.example.chatservice.model.ConversationStatus;
import org.example.chatservice.model.Message;
import org.example.chatservice.repository.ConversationRepository;
import org.example.chatservice.repository.MessageRepository;
import org.example.chatservice.security.ContentModerationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for ChatService messaging behavior, focusing on:
 * - A1: CLOSED conversations must reject messages
 * - Participant authorization on sendMessage
 * - mediaUrl validation
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatService Messaging Tests")
class ChatServiceMessagingTest {

    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private BackendApiClient backendApiClient;
    @Mock
    private ConversationEnrichmentService enrichmentService;
    @Mock
    private ReadReceiptService readReceiptService;
    @Mock
    private ContentModerationFilter contentModerationFilter;

    @InjectMocks
    private ChatService chatService;

    private static final Long BOOKING_ID = 100L;
    private static final Long RENTER_ID = 10L;
    private static final Long OWNER_ID = 20L;
    private static final Long ATTACKER_ID = 999L;

    @Nested
    @DisplayName("A1: CLOSED conversation messaging guard")
    class ClosedConversationGuard {

        @Test
        @DisplayName("sendMessage() must throw MessagingNotAllowedException for CLOSED conversation")
        void sendMessageBlockedOnClosedConversation() {
            Conversation closed = buildConversation(ConversationStatus.CLOSED);
            when(conversationRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(closed));

            SendMessageRequest request = SendMessageRequest.builder()
                    .content("Hello after close")
                    .build();

            assertThatThrownBy(() -> chatService.sendMessage(BOOKING_ID, RENTER_ID, request))
                    .isInstanceOf(MessagingNotAllowedException.class)
                    .hasMessageContaining("not allowed");

            verify(messageRepository, never()).save(any());
        }

        @Test
        @DisplayName("sendMessage() should succeed for ACTIVE conversation")
        void sendMessageAllowedOnActiveConversation() {
            Conversation active = buildConversation(ConversationStatus.ACTIVE);
            when(conversationRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(active));

            Message savedMsg = Message.builder()
                    .id(1L)
                    .conversationId(active.getId())
                    .senderId(RENTER_ID)
                    .content("Hello")
                    .build();
            when(messageRepository.save(any(Message.class))).thenReturn(savedMsg);
            when(backendApiClient.sendNewMessageNotification(any(), any(), any(), any()))
                    .thenReturn(reactor.core.publisher.Mono.empty());

            SendMessageRequest request = SendMessageRequest.builder()
                    .content("Hello")
                    .build();

            MessageDTO result = chatService.sendMessage(BOOKING_ID, RENTER_ID, request);

            assertThat(result).isNotNull();
            verify(messageRepository, atLeastOnce()).save(any(Message.class));
        }

        @Test
        @DisplayName("sendMessage() should succeed for PENDING conversation")
        void sendMessageAllowedOnPendingConversation() {
            Conversation pending = buildConversation(ConversationStatus.PENDING);
            when(conversationRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(pending));

            Message savedMsg = Message.builder()
                    .id(1L)
                    .conversationId(pending.getId())
                    .senderId(RENTER_ID)
                    .content("Hey")
                    .build();
            when(messageRepository.save(any(Message.class))).thenReturn(savedMsg);
            when(backendApiClient.sendNewMessageNotification(any(), any(), any(), any()))
                    .thenReturn(reactor.core.publisher.Mono.empty());

            SendMessageRequest request = SendMessageRequest.builder()
                    .content("Hey")
                    .build();

            MessageDTO result = chatService.sendMessage(BOOKING_ID, RENTER_ID, request);

            assertThat(result).isNotNull();
            verify(messageRepository, atLeastOnce()).save(any(Message.class));
        }
    }

    @Nested
    @DisplayName("Participant authorization on sendMessage")
    class SendMessageAuthorization {

        @Test
        @DisplayName("Non-participant should be rejected")
        void nonParticipantRejected() {
            Conversation conv = buildConversation(ConversationStatus.ACTIVE);
            when(conversationRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(conv));

            SendMessageRequest request = SendMessageRequest.builder()
                    .content("Sneaky message")
                    .build();

            assertThatThrownBy(() -> chatService.sendMessage(BOOKING_ID, ATTACKER_ID, request))
                    .isInstanceOf(ForbiddenException.class);

            verify(messageRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("mediaUrl validation")
    class MediaUrlValidation {

        @Test
        @DisplayName("Invalid mediaUrl should be rejected")
        void invalidMediaUrlRejected() {
            Conversation conv = buildConversation(ConversationStatus.ACTIVE);
            when(conversationRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(conv));

            SendMessageRequest request = SendMessageRequest.builder()
                    .content("Check this")
                    .mediaUrl("https://evil.com/malware.jpg")
                    .build();

            assertThatThrownBy(() -> chatService.sendMessage(BOOKING_ID, RENTER_ID, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid media URL");
        }

        @Test
        @DisplayName("Valid platform mediaUrl should be accepted")
        void validMediaUrlAccepted() {
            Conversation conv = buildConversation(ConversationStatus.ACTIVE);
            when(conversationRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(conv));

            Message savedMsg = Message.builder()
                    .id(1L)
                    .conversationId(conv.getId())
                    .senderId(RENTER_ID)
                    .content("Photo")
                    .mediaUrl("/api/attachments/booking-100/uuid.jpg")
                    .build();
            when(messageRepository.save(any(Message.class))).thenReturn(savedMsg);
            when(backendApiClient.sendNewMessageNotification(any(), any(), any(), any()))
                    .thenReturn(reactor.core.publisher.Mono.empty());

            SendMessageRequest request = SendMessageRequest.builder()
                    .content("Photo")
                    .mediaUrl("/api/attachments/booking-100/uuid.jpg")
                    .build();

            MessageDTO result = chatService.sendMessage(BOOKING_ID, RENTER_ID, request);

            assertThat(result).isNotNull();
        }
    }

    private Conversation buildConversation(ConversationStatus status) {
        return Conversation.builder()
                .id(1L)
                .bookingId(BOOKING_ID)
                .renterId(RENTER_ID)
                .ownerId(OWNER_ID)
                .status(status)
                .build();
    }
}

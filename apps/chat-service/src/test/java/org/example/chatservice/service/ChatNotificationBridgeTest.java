package org.example.chatservice.service;

import org.example.chatservice.dto.MessageDTO;
import org.example.chatservice.dto.SendMessageRequest;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import reactor.core.publisher.Mono;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Integration-style unit tests for the chat-to-notification bridge.
 *
 * When a message is sent via {@link ChatService#sendMessage}, the service must
 * call {@link BackendApiClient#sendNewMessageNotification} so the main backend
 * can push an offline notification (email, push, etc.) to the OTHER participant.
 *
 * These tests verify:
 * 1. BackendApiClient is called with correct parameters on successful message send
 * 2. The notification recipient is always the OTHER participant (not the sender)
 * 3. Message preview is truncated to 50 chars (substring(0,47) + "...") when too long
 * 4. Notification failure does NOT prevent message delivery (fire-and-forget)
 * 5. The correct bookingId is forwarded to the notification
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Chat -> Notification Bridge Tests")
class ChatNotificationBridgeTest {

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

    private static final Long BOOKING_ID = 500L;
    private static final Long RENTER_ID = 10L;
    private static final Long OWNER_ID = 20L;
    private static final Long CONVERSATION_ID = 1L;

    private Conversation activeConversation;

    @BeforeEach
    void setUp() {
        activeConversation = Conversation.builder()
                .id(CONVERSATION_ID)
                .bookingId(BOOKING_ID)
                .renterId(RENTER_ID)
                .ownerId(OWNER_ID)
                .status(ConversationStatus.ACTIVE)
                .build();
    }

    /**
     * Stubs the common mocks required for a successful sendMessage flow:
     * - conversation lookup
     * - message save (returns a message with an ID)
     * - notification call (returns empty Mono)
     */
    private void stubHappyPath(String content) {
        when(conversationRepository.findByBookingId(BOOKING_ID))
                .thenReturn(Optional.of(activeConversation));

        Message savedMessage = Message.builder()
                .id(42L)
                .conversationId(CONVERSATION_ID)
                .senderId(RENTER_ID)
                .content(content)
                .build();
        when(messageRepository.save(any(Message.class))).thenReturn(savedMessage);

        when(backendApiClient.sendNewMessageNotification(anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(Mono.empty());
    }

    // -------------------------------------------------------------------------
    // 1. Verify BackendApiClient is called with correct parameters
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("1. Notification call on successful message send")
    class NotificationCalledOnSuccess {

        @Test
        @DisplayName("sendNewMessageNotification is invoked exactly once when sendMessage succeeds")
        void notificationCalledOnce() {
            String content = "Hello, is the car available?";
            stubHappyPath(content);

            chatService.sendMessage(BOOKING_ID, RENTER_ID,
                    SendMessageRequest.builder().content(content).build());

            verify(backendApiClient, times(1))
                    .sendNewMessageNotification(anyLong(), anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("Notification includes sender name 'Korisnik' as hardcoded in sendMessage")
        void notificationIncludesSenderName() {
            String content = "When can I pick up?";
            stubHappyPath(content);

            chatService.sendMessage(BOOKING_ID, RENTER_ID,
                    SendMessageRequest.builder().content(content).build());

            verify(backendApiClient).sendNewMessageNotification(
                    eq(OWNER_ID), eq(BOOKING_ID), eq("Korisnik"), eq(content));
        }
    }

    // -------------------------------------------------------------------------
    // 2. Recipient is the OTHER participant (not the sender)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("2. Recipient resolution - always the other participant")
    class RecipientResolution {

        @Test
        @DisplayName("When renter sends, notification goes to OWNER")
        void renterSendsNotificationGoesToOwner() {
            String content = "Hi owner!";
            stubHappyPath(content);

            chatService.sendMessage(BOOKING_ID, RENTER_ID,
                    SendMessageRequest.builder().content(content).build());

            ArgumentCaptor<Long> recipientCaptor = ArgumentCaptor.forClass(Long.class);
            verify(backendApiClient).sendNewMessageNotification(
                    recipientCaptor.capture(), anyLong(), anyString(), anyString());

            assertThat(recipientCaptor.getValue())
                    .as("Recipient should be the OWNER when renter sends")
                    .isEqualTo(OWNER_ID);
        }

        @Test
        @DisplayName("When owner sends, notification goes to RENTER")
        void ownerSendsNotificationGoesToRenter() {
            String content = "Sure, come pick it up!";
            when(conversationRepository.findByBookingId(BOOKING_ID))
                    .thenReturn(Optional.of(activeConversation));

            Message savedMessage = Message.builder()
                    .id(43L)
                    .conversationId(CONVERSATION_ID)
                    .senderId(OWNER_ID)
                    .content(content)
                    .build();
            when(messageRepository.save(any(Message.class))).thenReturn(savedMessage);
            when(backendApiClient.sendNewMessageNotification(anyLong(), anyLong(), anyString(), anyString()))
                    .thenReturn(Mono.empty());

            chatService.sendMessage(BOOKING_ID, OWNER_ID,
                    SendMessageRequest.builder().content(content).build());

            ArgumentCaptor<Long> recipientCaptor = ArgumentCaptor.forClass(Long.class);
            verify(backendApiClient).sendNewMessageNotification(
                    recipientCaptor.capture(), anyLong(), anyString(), anyString());

            assertThat(recipientCaptor.getValue())
                    .as("Recipient should be the RENTER when owner sends")
                    .isEqualTo(RENTER_ID);
        }

        @Test
        @DisplayName("Notification recipient is never the sender themselves")
        void recipientIsNeverTheSender() {
            String content = "Test message";
            stubHappyPath(content);

            // Send as renter
            chatService.sendMessage(BOOKING_ID, RENTER_ID,
                    SendMessageRequest.builder().content(content).build());

            ArgumentCaptor<Long> recipientCaptor = ArgumentCaptor.forClass(Long.class);
            verify(backendApiClient).sendNewMessageNotification(
                    recipientCaptor.capture(), anyLong(), anyString(), anyString());

            assertThat(recipientCaptor.getValue())
                    .as("Notification recipient must never equal the sender")
                    .isNotEqualTo(RENTER_ID);
        }
    }

    // -------------------------------------------------------------------------
    // 3. Message preview truncation (>50 chars -> substring(0,47) + "...")
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("3. Message preview truncation")
    class MessagePreviewTruncation {

        @Test
        @DisplayName("Short message (<= 50 chars) is passed as-is")
        void shortMessageNotTruncated() {
            // Exactly 50 characters
            String content = "12345678901234567890123456789012345678901234567890";
            assertThat(content).hasSize(50);

            stubHappyPath(content);

            chatService.sendMessage(BOOKING_ID, RENTER_ID,
                    SendMessageRequest.builder().content(content).build());

            ArgumentCaptor<String> previewCaptor = ArgumentCaptor.forClass(String.class);
            verify(backendApiClient).sendNewMessageNotification(
                    anyLong(), anyLong(), anyString(), previewCaptor.capture());

            assertThat(previewCaptor.getValue())
                    .as("Message of exactly 50 chars should NOT be truncated")
                    .isEqualTo(content)
                    .hasSize(50);
        }

        @Test
        @DisplayName("Long message (> 50 chars) is truncated to first 47 chars + '...'")
        void longMessageTruncated() {
            // 60 characters
            String content = "123456789012345678901234567890123456789012345678901234567890";
            assertThat(content).hasSize(60);

            stubHappyPath(content);

            chatService.sendMessage(BOOKING_ID, RENTER_ID,
                    SendMessageRequest.builder().content(content).build());

            ArgumentCaptor<String> previewCaptor = ArgumentCaptor.forClass(String.class);
            verify(backendApiClient).sendNewMessageNotification(
                    anyLong(), anyLong(), anyString(), previewCaptor.capture());

            String expectedPreview = content.substring(0, 47) + "...";
            assertThat(previewCaptor.getValue())
                    .as("Message > 50 chars should be truncated to 47 chars + '...'")
                    .isEqualTo(expectedPreview)
                    .hasSize(50);
        }

        @Test
        @DisplayName("Message of exactly 51 chars triggers truncation")
        void boundaryTruncation() {
            // 51 characters: just over the threshold
            String content = "123456789012345678901234567890123456789012345678901";
            assertThat(content).hasSize(51);

            stubHappyPath(content);

            chatService.sendMessage(BOOKING_ID, RENTER_ID,
                    SendMessageRequest.builder().content(content).build());

            ArgumentCaptor<String> previewCaptor = ArgumentCaptor.forClass(String.class);
            verify(backendApiClient).sendNewMessageNotification(
                    anyLong(), anyLong(), anyString(), previewCaptor.capture());

            assertThat(previewCaptor.getValue())
                    .as("Message of 51 chars should be truncated")
                    .isEqualTo(content.substring(0, 47) + "...")
                    .hasSize(50)
                    .endsWith("...");
        }

        @Test
        @DisplayName("Very long message (2000 chars) is truncated correctly")
        void veryLongMessageTruncated() {
            String content = "A".repeat(2000);

            stubHappyPath(content);

            chatService.sendMessage(BOOKING_ID, RENTER_ID,
                    SendMessageRequest.builder().content(content).build());

            ArgumentCaptor<String> previewCaptor = ArgumentCaptor.forClass(String.class);
            verify(backendApiClient).sendNewMessageNotification(
                    anyLong(), anyLong(), anyString(), previewCaptor.capture());

            assertThat(previewCaptor.getValue())
                    .hasSize(50)
                    .startsWith("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") // 47 A's
                    .endsWith("...");
        }
    }

    // -------------------------------------------------------------------------
    // 4. Notification failure does NOT prevent message delivery (fire-and-forget)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("4. Notification failure resilience - fire-and-forget")
    class NotificationFailureResilience {

        @Test
        @DisplayName("Message is still delivered when notification Mono errors")
        void messageDeliveredDespiteNotificationError() {
            String content = "Important message";
            when(conversationRepository.findByBookingId(BOOKING_ID))
                    .thenReturn(Optional.of(activeConversation));

            Message savedMessage = Message.builder()
                    .id(44L)
                    .conversationId(CONVERSATION_ID)
                    .senderId(RENTER_ID)
                    .content(content)
                    .build();
            when(messageRepository.save(any(Message.class))).thenReturn(savedMessage);

            // Notification call returns an error Mono
            when(backendApiClient.sendNewMessageNotification(anyLong(), anyLong(), anyString(), anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Backend notification service unavailable")));

            // sendMessage should NOT throw even though notification fails
            MessageDTO result = chatService.sendMessage(BOOKING_ID, RENTER_ID,
                    SendMessageRequest.builder().content(content).build());

            // Message was saved and returned successfully
            assertThat(result).isNotNull();
            assertThat(result.getContent()).isEqualTo(content);

            // Message was persisted (save called at least twice: initial save + read receipt save)
            verify(messageRepository, atLeastOnce()).save(any(Message.class));

            // Conversation was updated
            verify(conversationRepository).save(any(Conversation.class));

            // WebSocket broadcast still happened
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/conversation/" + BOOKING_ID), any(MessageDTO.class));
        }

        @Test
        @DisplayName("Message is still delivered when notification returns Mono.empty()")
        void messageDeliveredWhenNotificationReturnsEmpty() {
            String content = "Another message";
            when(conversationRepository.findByBookingId(BOOKING_ID))
                    .thenReturn(Optional.of(activeConversation));

            Message savedMessage = Message.builder()
                    .id(45L)
                    .conversationId(CONVERSATION_ID)
                    .senderId(RENTER_ID)
                    .content(content)
                    .build();
            when(messageRepository.save(any(Message.class))).thenReturn(savedMessage);

            // Notification returns empty (simulates backend returning no body)
            when(backendApiClient.sendNewMessageNotification(anyLong(), anyLong(), anyString(), anyString()))
                    .thenReturn(Mono.empty());

            MessageDTO result = chatService.sendMessage(BOOKING_ID, RENTER_ID,
                    SendMessageRequest.builder().content(content).build());

            assertThat(result).isNotNull();
            assertThat(result.getContent()).isEqualTo(content);
            verify(messageRepository, atLeastOnce()).save(any(Message.class));
        }

        @Test
        @DisplayName("Return value of sendMessage is independent of notification outcome")
        void returnValueIndependentOfNotification() {
            String content = "Check my booking";
            when(conversationRepository.findByBookingId(BOOKING_ID))
                    .thenReturn(Optional.of(activeConversation));

            Message savedMessage = Message.builder()
                    .id(46L)
                    .conversationId(CONVERSATION_ID)
                    .senderId(RENTER_ID)
                    .content(content)
                    .build();
            when(messageRepository.save(any(Message.class))).thenReturn(savedMessage);

            // Notification errors with a network-level exception
            when(backendApiClient.sendNewMessageNotification(anyLong(), anyLong(), anyString(), anyString()))
                    .thenReturn(Mono.error(new java.net.ConnectException("Connection refused")));

            MessageDTO result = chatService.sendMessage(BOOKING_ID, RENTER_ID,
                    SendMessageRequest.builder().content(content).build());

            // The returned DTO should reflect the saved message, not the notification outcome
            assertThat(result).isNotNull();
            assertThat(result.getSenderId()).isEqualTo(RENTER_ID);
            assertThat(result.isOwnMessage()).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // 5. Correct bookingId is passed to the notification
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("5. BookingId forwarded correctly to notification")
    class BookingIdForwarding {

        @Test
        @DisplayName("Notification receives the same bookingId as the conversation")
        void notificationReceivesCorrectBookingId() {
            String content = "About booking 500";
            stubHappyPath(content);

            chatService.sendMessage(BOOKING_ID, RENTER_ID,
                    SendMessageRequest.builder().content(content).build());

            ArgumentCaptor<Long> bookingCaptor = ArgumentCaptor.forClass(Long.class);
            verify(backendApiClient).sendNewMessageNotification(
                    anyLong(), bookingCaptor.capture(), anyString(), anyString());

            assertThat(bookingCaptor.getValue())
                    .as("BookingId passed to notification must match the conversation's bookingId")
                    .isEqualTo(BOOKING_ID);
        }

        @Test
        @DisplayName("Different bookingId conversations route notifications correctly")
        void differentBookingIdsRouteCorrectly() {
            Long otherBookingId = 999L;
            Conversation otherConversation = Conversation.builder()
                    .id(2L)
                    .bookingId(otherBookingId)
                    .renterId(RENTER_ID)
                    .ownerId(OWNER_ID)
                    .status(ConversationStatus.ACTIVE)
                    .build();

            when(conversationRepository.findByBookingId(otherBookingId))
                    .thenReturn(Optional.of(otherConversation));

            Message savedMessage = Message.builder()
                    .id(47L)
                    .conversationId(2L)
                    .senderId(RENTER_ID)
                    .content("Different booking")
                    .build();
            when(messageRepository.save(any(Message.class))).thenReturn(savedMessage);
            when(backendApiClient.sendNewMessageNotification(anyLong(), anyLong(), anyString(), anyString()))
                    .thenReturn(Mono.empty());

            chatService.sendMessage(otherBookingId, RENTER_ID,
                    SendMessageRequest.builder().content("Different booking").build());

            verify(backendApiClient).sendNewMessageNotification(
                    eq(OWNER_ID), eq(otherBookingId), eq("Korisnik"), eq("Different booking"));
        }
    }

    // -------------------------------------------------------------------------
    // Combined verification: all parameters in a single assertion
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Combined parameter verification")
    class CombinedParameterVerification {

        @Test
        @DisplayName("All notification parameters are correct for renter sending a short message")
        void allParamsCorrectRenterShortMessage() {
            String content = "When can I get the car?";
            stubHappyPath(content);

            chatService.sendMessage(BOOKING_ID, RENTER_ID,
                    SendMessageRequest.builder().content(content).build());

            verify(backendApiClient).sendNewMessageNotification(
                    eq(OWNER_ID),       // recipient = other participant
                    eq(BOOKING_ID),     // bookingId forwarded
                    eq("Korisnik"),     // hardcoded sender name
                    eq(content)         // short message not truncated
            );
        }

        @Test
        @DisplayName("All notification parameters are correct for owner sending a long message")
        void allParamsCorrectOwnerLongMessage() {
            String content = "Sure, the car is available for pickup tomorrow morning at the agreed location near the airport";
            assertThat(content.length()).isGreaterThan(50);

            when(conversationRepository.findByBookingId(BOOKING_ID))
                    .thenReturn(Optional.of(activeConversation));

            Message savedMessage = Message.builder()
                    .id(48L)
                    .conversationId(CONVERSATION_ID)
                    .senderId(OWNER_ID)
                    .content(content)
                    .build();
            when(messageRepository.save(any(Message.class))).thenReturn(savedMessage);
            when(backendApiClient.sendNewMessageNotification(anyLong(), anyLong(), anyString(), anyString()))
                    .thenReturn(Mono.empty());

            chatService.sendMessage(BOOKING_ID, OWNER_ID,
                    SendMessageRequest.builder().content(content).build());

            String expectedPreview = content.substring(0, 47) + "...";

            verify(backendApiClient).sendNewMessageNotification(
                    eq(RENTER_ID),       // recipient = renter (since owner sent)
                    eq(BOOKING_ID),      // bookingId forwarded
                    eq("Korisnik"),      // hardcoded sender name
                    eq(expectedPreview)  // long message truncated
            );
        }
    }
}

package org.example.chatservice.security;

import org.example.chatservice.model.Conversation;
import org.example.chatservice.model.ConversationStatus;
import org.example.chatservice.repository.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Unit tests for WebSocketSecurityInterceptor.
 *
 * Tests the subscription authorization logic that ensures users
 * can only subscribe to conversations they are participants in.
 *
 * All IDs are Long (BIGINT) matching production models.
 */
@ExtendWith(MockitoExtension.class)
class WebSocketSecurityInterceptorTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private MessageChannel messageChannel;

    @InjectMocks
    private WebSocketSecurityInterceptor interceptor;

    private static final Long BOOKING_ID = 123L;
    private static final Long RENTER_ID = 456L;
    private static final Long OWNER_ID = 789L;
    private static final Long STRANGER_ID = 999L;

    private Conversation testConversation;

    @BeforeEach
    void setUp() {
        testConversation = Conversation.builder()
                .id(1L)
                .bookingId(BOOKING_ID)
                .renterId(RENTER_ID)
                .ownerId(OWNER_ID)
                .status(ConversationStatus.ACTIVE)
                .build();
    }

    private Message<?> createSubscribeMessage(String destination, String userId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        if (userId != null) {
            Principal principal = () -> userId;
            accessor.setUser(principal);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> createConnectMessage() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Nested
    @DisplayName("Non-SUBSCRIBE commands")
    class NonSubscribeCommands {

        @Test
        @DisplayName("Should allow CONNECT command")
        void allowsConnect() {
            Message<?> message = createConnectMessage();

            Message<?> result = interceptor.preSend(message, messageChannel);

            assertThat(result).isSameAs(message);
            verifyNoInteractions(conversationRepository);
        }
    }

    @Nested
    @DisplayName("Authorized subscriptions")
    class AuthorizedSubscriptions {

        @Test
        @DisplayName("Should allow renter to subscribe to conversation")
        void allowsRenterSubscription() {
            when(conversationRepository.findByBookingId(BOOKING_ID))
                    .thenReturn(Optional.of(testConversation));

            Message<?> message = createSubscribeMessage(
                    "/topic/conversation/" + BOOKING_ID, String.valueOf(RENTER_ID));

            Message<?> result = interceptor.preSend(message, messageChannel);

            assertThat(result).isSameAs(message);
        }

        @Test
        @DisplayName("Should allow owner to subscribe to conversation")
        void allowsOwnerSubscription() {
            when(conversationRepository.findByBookingId(BOOKING_ID))
                    .thenReturn(Optional.of(testConversation));

            Message<?> message = createSubscribeMessage(
                    "/topic/conversation/" + BOOKING_ID, String.valueOf(OWNER_ID));

            Message<?> result = interceptor.preSend(message, messageChannel);

            assertThat(result).isSameAs(message);
        }

        @Test
        @DisplayName("Should allow subscription to conversation status topic")
        void allowsStatusTopicSubscription() {
            when(conversationRepository.findByBookingId(BOOKING_ID))
                    .thenReturn(Optional.of(testConversation));

            Message<?> message = createSubscribeMessage(
                    "/topic/conversation/" + BOOKING_ID + "/status", String.valueOf(RENTER_ID));

            Message<?> result = interceptor.preSend(message, messageChannel);

            assertThat(result).isSameAs(message);
        }

        @Test
        @DisplayName("Should allow subscription to conversation typing topic")
        void allowsTypingTopicSubscription() {
            when(conversationRepository.findByBookingId(BOOKING_ID))
                    .thenReturn(Optional.of(testConversation));

            Message<?> message = createSubscribeMessage(
                    "/topic/conversation/" + BOOKING_ID + "/typing", String.valueOf(OWNER_ID));

            Message<?> result = interceptor.preSend(message, messageChannel);

            assertThat(result).isSameAs(message);
        }

        @Test
        @DisplayName("Should allow subscription to non-conversation topics")
        void allowsNonConversationTopics() {
            Message<?> message = createSubscribeMessage("/topic/notifications", "123");

            Message<?> result = interceptor.preSend(message, messageChannel);

            assertThat(result).isSameAs(message);
            verifyNoInteractions(conversationRepository);
        }
    }

    @Nested
    @DisplayName("Unauthorized subscriptions")
    class UnauthorizedSubscriptions {

        @Test
        @DisplayName("Should reject non-participant subscription")
        void rejectsNonParticipant() {
            when(conversationRepository.findByBookingId(BOOKING_ID))
                    .thenReturn(Optional.of(testConversation));

            Message<?> message = createSubscribeMessage(
                    "/topic/conversation/" + BOOKING_ID, String.valueOf(STRANGER_ID));

            assertThatThrownBy(() -> interceptor.preSend(message, messageChannel))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Not authorized");
        }

        @Test
        @DisplayName("Should reject subscription to non-existent conversation")
        void rejectsNonExistentConversation() {
            when(conversationRepository.findByBookingId(9999L))
                    .thenReturn(Optional.empty());

            Message<?> message = createSubscribeMessage(
                    "/topic/conversation/9999", String.valueOf(RENTER_ID));

            assertThatThrownBy(() -> interceptor.preSend(message, messageChannel))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("Should reject unauthenticated subscription to conversation topic")
        void rejectsUnauthenticatedSubscription() {
            Message<?> message = createSubscribeMessage(
                    "/topic/conversation/" + BOOKING_ID, null);

            assertThatThrownBy(() -> interceptor.preSend(message, messageChannel))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Authentication required");
        }
    }
}

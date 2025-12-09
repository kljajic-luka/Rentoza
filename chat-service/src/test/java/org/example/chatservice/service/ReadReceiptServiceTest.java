package org.example.chatservice.service;

import org.example.chatservice.model.MessageReadReceipt;
import org.example.chatservice.repository.MessageReadReceiptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ReadReceiptService.
 */
@ExtendWith(MockitoExtension.class)
class ReadReceiptServiceTest {

    @Mock
    private MessageReadReceiptRepository readReceiptRepository;

    @InjectMocks
    private ReadReceiptService readReceiptService;

    @Captor
    private ArgumentCaptor<List<MessageReadReceipt>> receiptsCaptor;

    private static final Long CONVERSATION_ID = 1L;
    private static final String USER_ID = "user-123";
    private static final Long MESSAGE_ID_1 = 100L;
    private static final Long MESSAGE_ID_2 = 101L;

    @Nested
    @DisplayName("markMessagesAsRead - Batch Operation")
    class MarkMessagesAsRead {

        @Test
        @DisplayName("Should mark all unread messages as read in batch")
        void marksBatchOfMessages() {
            when(readReceiptRepository.findUnreadMessageIdsInConversation(CONVERSATION_ID, USER_ID))
                    .thenReturn(Arrays.asList(MESSAGE_ID_1, MESSAGE_ID_2));

            int count = readReceiptService.markMessagesAsRead(CONVERSATION_ID, USER_ID);

            assertThat(count).isEqualTo(2);
            verify(readReceiptRepository).saveAll(receiptsCaptor.capture());
            
            List<MessageReadReceipt> savedReceipts = receiptsCaptor.getValue();
            assertThat(savedReceipts).hasSize(2);
            assertThat(savedReceipts).extracting(MessageReadReceipt::getMessageId)
                    .containsExactlyInAnyOrder(MESSAGE_ID_1, MESSAGE_ID_2);
            assertThat(savedReceipts).extracting(MessageReadReceipt::getUserId)
                    .containsOnly(USER_ID);
        }

        @Test
        @DisplayName("Should return 0 when no unread messages")
        void returnsZeroWhenNoUnread() {
            when(readReceiptRepository.findUnreadMessageIdsInConversation(CONVERSATION_ID, USER_ID))
                    .thenReturn(Collections.emptyList());

            int count = readReceiptService.markMessagesAsRead(CONVERSATION_ID, USER_ID);

            assertThat(count).isEqualTo(0);
            verify(readReceiptRepository, never()).saveAll(anyList());
        }
    }

    @Nested
    @DisplayName("markMessageAsRead - Single Message")
    class MarkSingleMessageAsRead {

        @Test
        @DisplayName("Should mark single message as read")
        void marksSingleMessage() {
            when(readReceiptRepository.existsByMessageIdAndUserId(MESSAGE_ID_1, USER_ID))
                    .thenReturn(false);

            boolean result = readReceiptService.markMessageAsRead(MESSAGE_ID_1, USER_ID);

            assertThat(result).isTrue();
            verify(readReceiptRepository).save(any(MessageReadReceipt.class));
        }

        @Test
        @DisplayName("Should return false if already read")
        void returnsFalseIfAlreadyRead() {
            when(readReceiptRepository.existsByMessageIdAndUserId(MESSAGE_ID_1, USER_ID))
                    .thenReturn(true);

            boolean result = readReceiptService.markMessageAsRead(MESSAGE_ID_1, USER_ID);

            assertThat(result).isFalse();
            verify(readReceiptRepository, never()).save(any(MessageReadReceipt.class));
        }
    }

    @Nested
    @DisplayName("getUnreadCount")
    class GetUnreadCount {

        @Test
        @DisplayName("Should return unread count from repository")
        void returnsUnreadCount() {
            when(readReceiptRepository.countUnreadMessagesInConversation(CONVERSATION_ID, USER_ID))
                    .thenReturn(5L);

            long count = readReceiptService.getUnreadCount(CONVERSATION_ID, USER_ID);

            assertThat(count).isEqualTo(5);
        }

        @Test
        @DisplayName("Should return 0 when all messages read")
        void returnsZeroWhenAllRead() {
            when(readReceiptRepository.countUnreadMessagesInConversation(CONVERSATION_ID, USER_ID))
                    .thenReturn(0L);

            long count = readReceiptService.getUnreadCount(CONVERSATION_ID, USER_ID);

            assertThat(count).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("getReadByUsers")
    class GetReadByUsers {

        @Test
        @DisplayName("Should return users who read message")
        void returnsUserIds() {
            when(readReceiptRepository.findUserIdsWhoReadMessage(MESSAGE_ID_1))
                    .thenReturn(Set.of("user-1", "user-2"));

            Set<String> users = readReceiptService.getReadByUsers(MESSAGE_ID_1);

            assertThat(users).containsExactlyInAnyOrder("user-1", "user-2");
        }
    }

    @Nested
    @DisplayName("hasUserReadMessage")
    class HasUserReadMessage {

        @Test
        @DisplayName("Should return true if user read message")
        void returnsTrueIfRead() {
            when(readReceiptRepository.existsByMessageIdAndUserId(MESSAGE_ID_1, USER_ID))
                    .thenReturn(true);

            boolean result = readReceiptService.hasUserReadMessage(MESSAGE_ID_1, USER_ID);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should return false if user has not read message")
        void returnsFalseIfNotRead() {
            when(readReceiptRepository.existsByMessageIdAndUserId(MESSAGE_ID_1, USER_ID))
                    .thenReturn(false);

            boolean result = readReceiptService.hasUserReadMessage(MESSAGE_ID_1, USER_ID);

            assertThat(result).isFalse();
        }
    }
}

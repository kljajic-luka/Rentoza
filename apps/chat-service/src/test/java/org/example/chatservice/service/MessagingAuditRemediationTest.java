package org.example.chatservice.service;

import org.example.chatservice.dto.SendMessageRequest;
import org.example.chatservice.model.ConversationStatus;
import org.example.chatservice.controller.ChatController;
import org.example.chatservice.config.RateLimitConfig;
import org.example.chatservice.model.AdminAuditEntry;
import org.example.chatservice.model.Message;
import org.example.chatservice.repository.AdminAuditRepository;
import org.example.chatservice.repository.MessageRepository;
import org.example.chatservice.security.ContentModerationFilter;
import org.example.chatservice.dto.ConversationDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessagingAuditRemediationTest {

    @Mock
    private ChatService chatService;
    @Mock
    private ContentModerationFilter contentModerationFilter;
    @Mock
    private RateLimitConfig rateLimitConfig;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private AdminAuditRepository adminAuditRepository;

    @Nested
    @DisplayName("C2 - transactional sendMessage")
    class C2_TransactionalSendMessage {

        @Test
        @DisplayName("four-argument sendMessage is annotated with Transactional")
        void fourArgumentSendMessageIsTransactional() throws Exception {
            Method method = ChatService.class.getMethod(
                    "sendMessage",
                    Long.class,
                    Long.class,
                    SendMessageRequest.class,
                    List.class);

            assertThat(method.isAnnotationPresent(Transactional.class)).isTrue();
        }
    }

    @Nested
    @DisplayName("H4 - close conversation restricted to internal API")
    class H4_CloseConversationRestriction {

        @Test
        @DisplayName("user endpoint rejects CLOSED status with 403")
        void userEndpointRejectsClosedStatus() {
            ChatController controller = new ChatController(
                    chatService,
                    contentModerationFilter,
                    rateLimitConfig,
                    fileStorageService,
                    idempotencyService,
                    messageRepository,
                    adminAuditRepository);

            var authentication = new UsernamePasswordAuthenticationToken("123", null);
            var response = controller.updateConversationStatus("55", ConversationStatus.CLOSED, authentication);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verifyNoInteractions(chatService);
        }
    }

    @Nested
    @DisplayName("M3 - admin audit trail")
    class M3_AdminAuditTrail {

        @Test
        @DisplayName("dismissFlags writes a review audit entry")
        void dismissFlagsWritesAuditEntry() {
            ChatController controller = new ChatController(
                    chatService,
                    contentModerationFilter,
                    rateLimitConfig,
                    fileStorageService,
                    idempotencyService,
                    messageRepository,
                    adminAuditRepository);
            Message message = Message.builder().id(99L).moderationFlags("URL_DETECTED").build();
            var authentication = new UsernamePasswordAuthenticationToken("123", null);

            when(messageRepository.findById(99L)).thenReturn(Optional.of(message));

            controller.dismissFlags(99L, authentication);

            verify(adminAuditRepository).save(any(AdminAuditEntry.class));
        }

        @Test
        @DisplayName("getAdminTranscript writes a conversation view audit entry")
        void getAdminTranscriptWritesAuditEntry() {
            ChatController controller = new ChatController(
                    chatService,
                    contentModerationFilter,
                    rateLimitConfig,
                    fileStorageService,
                    idempotencyService,
                    messageRepository,
                    adminAuditRepository);
            ConversationDTO transcript = new ConversationDTO();
            transcript.setMessages(List.of());
            var authentication = new UsernamePasswordAuthenticationToken("123", null);

            when(chatService.getConversationForAdmin(55L)).thenReturn(transcript);

            controller.getAdminTranscript("55", authentication);

            verify(adminAuditRepository).save(any(AdminAuditEntry.class));
        }
    }
}
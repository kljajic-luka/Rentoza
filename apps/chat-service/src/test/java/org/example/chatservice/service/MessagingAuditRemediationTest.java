package org.example.chatservice.service;

import org.example.chatservice.dto.SendMessageRequest;
import org.example.chatservice.model.ConversationStatus;
import org.example.chatservice.controller.ChatController;
import org.example.chatservice.config.RateLimitConfig;
import org.example.chatservice.dto.MessageDTO;
import org.example.chatservice.model.AdminAuditEntry;
import org.example.chatservice.model.Message;
import org.example.chatservice.repository.AdminAuditRepository;
import org.example.chatservice.repository.MessageRepository;
import org.example.chatservice.security.ContentModerationFilter;
import org.example.chatservice.dto.ConversationDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Captor
    private ArgumentCaptor<AdminAuditEntry> auditEntryCaptor;

    private ChatController controller;

    @BeforeEach
    void setUp() {
        controller = new ChatController(
                chatService,
                contentModerationFilter,
                rateLimitConfig,
                fileStorageService,
                idempotencyService,
                messageRepository,
                adminAuditRepository);
    }

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
            var authentication = new UsernamePasswordAuthenticationToken("123", null);
            var response = controller.updateConversationStatus("55", ConversationStatus.CLOSED, authentication);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verifyNoInteractions(chatService);
        }
    }

    @Nested
    @DisplayName("H2 - admin oversight audit trail")
    class H2_AdminOversightAuditTrail {

        @Test
        @DisplayName("getAdminConversations writes structured audit entry")
        void getAdminConversationsWritesStructuredAuditEntry() {
            MockHttpServletRequest request = buildRequest();
            ConversationDTO conversation = new ConversationDTO();

            when(chatService.getAllConversationsForAdmin()).thenReturn(List.of(conversation));

            var response = controller.getAdminConversations(new UsernamePasswordAuthenticationToken("123", null), request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(adminAuditRepository).save(auditEntryCaptor.capture());

            AdminAuditEntry auditEntry = auditEntryCaptor.getValue();
            assertThat(auditEntry.getAction()).isEqualTo(AdminAuditEntry.Action.CONVERSATIONS_LISTED);
            assertThat(auditEntry.getTargetType()).isEqualTo(AdminAuditEntry.TargetType.CONVERSATION);
            assertThat(auditEntry.getTargetId()).isEqualTo("ALL");
            assertThat(auditEntry.getResult()).isEqualTo("LISTED");
            assertThat(auditEntry.getIpAddress()).isEqualTo("203.0.113.10");
            assertThat(auditEntry.getUserAgent()).isEqualTo("JUnit-Agent/1.0");
            assertThat(auditEntry.getJustification()).isNull();
        }

        @Test
        @DisplayName("getAdminTranscript rejects missing justification")
        void getAdminTranscriptRejectsMissingJustification() {
            MockHttpServletRequest request = buildRequest();

            var response = controller.getAdminTranscript(
                    "55",
                    "   ",
                    new UsernamePasswordAuthenticationToken("123", null),
                    request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(chatService);
            verifyNoInteractions(adminAuditRepository);
        }

        @Test
        @DisplayName("getAdminTranscript writes audit entry with ip user-agent and justification")
        void getAdminTranscriptWritesAuditEntryWithForensics() {
            MockHttpServletRequest request = buildRequest();
            ConversationDTO transcript = new ConversationDTO();
            transcript.setMessages(List.of(new MessageDTO()));
            var authentication = new UsernamePasswordAuthenticationToken("123", null);

            when(chatService.getConversationForAdmin(55L)).thenReturn(transcript);

            var response = controller.getAdminTranscript("55", "Chargeback review #A-12", authentication, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(adminAuditRepository).save(auditEntryCaptor.capture());

            AdminAuditEntry auditEntry = auditEntryCaptor.getValue();
            assertThat(auditEntry.getAction()).isEqualTo(AdminAuditEntry.Action.CONVERSATION_VIEWED);
            assertThat(auditEntry.getTargetId()).isEqualTo("55");
            assertThat(auditEntry.getJustification()).isEqualTo("Chargeback review #A-12");
            assertThat(auditEntry.getIpAddress()).isEqualTo("203.0.113.10");
            assertThat(auditEntry.getUserAgent()).isEqualTo("JUnit-Agent/1.0");
            assertThat(auditEntry.getResult()).isEqualTo("VIEWED");
            assertThat(auditEntry.getMetadata()).contains("\"messageCount\":1");
        }

        @Test
        @DisplayName("getFlaggedMessages writes structured queue audit entry")
        void getFlaggedMessagesWritesQueueAuditEntry() {
            MockHttpServletRequest request = buildRequest();
            Message message = Message.builder().id(99L).moderationFlags("URL_DETECTED").build();
            when(messageRepository.findFlaggedMessages(any())).thenReturn(new PageImpl<>(List.of(message)));

            var response = controller.getFlaggedMessages(0, 20,
                    new UsernamePasswordAuthenticationToken("123", null), request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(adminAuditRepository).save(auditEntryCaptor.capture());

            AdminAuditEntry auditEntry = auditEntryCaptor.getValue();
            assertThat(auditEntry.getAction()).isEqualTo(AdminAuditEntry.Action.FLAGGED_MESSAGES_VIEWED);
            assertThat(auditEntry.getTargetType()).isEqualTo(AdminAuditEntry.TargetType.MESSAGE);
            assertThat(auditEntry.getTargetId()).isEqualTo("FLAGGED_QUEUE");
            assertThat(auditEntry.getResult()).isEqualTo("VIEWED");
            assertThat(auditEntry.getMetadata()).contains("\"returned\":1");
        }

        @Test
        @DisplayName("dismissFlags rejects missing justification")
        void dismissFlagsRejectsMissingJustification() {
            MockHttpServletRequest request = buildRequest();

            var response = controller.dismissFlags(
                    99L,
                    "\t",
                    new UsernamePasswordAuthenticationToken("123", null),
                    request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(messageRepository);
            verifyNoInteractions(adminAuditRepository);
        }

        @Test
        @DisplayName("dismissFlags writes a review audit entry with justification")
        void dismissFlagsWritesAuditEntryWithJustification() {
            MockHttpServletRequest request = buildRequest();
            Message message = Message.builder().id(99L).moderationFlags("URL_DETECTED").build();
            var authentication = new UsernamePasswordAuthenticationToken("123", null);

            when(messageRepository.findById(99L)).thenReturn(Optional.of(message));

            var response = controller.dismissFlags(99L, "False positive after review", authentication, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(adminAuditRepository).save(auditEntryCaptor.capture());

            AdminAuditEntry auditEntry = auditEntryCaptor.getValue();
            assertThat(auditEntry.getAction()).isEqualTo(AdminAuditEntry.Action.REVIEW_DISMISSED);
            assertThat(auditEntry.getTargetType()).isEqualTo(AdminAuditEntry.TargetType.MESSAGE);
            assertThat(auditEntry.getTargetId()).isEqualTo("99");
            assertThat(auditEntry.getJustification()).isEqualTo("False positive after review");
            assertThat(auditEntry.getResult()).isEqualTo("DISMISSED");
            assertThat(auditEntry.getIpAddress()).isEqualTo("203.0.113.10");
            assertThat(auditEntry.getUserAgent()).isEqualTo("JUnit-Agent/1.0");
        }

        private MockHttpServletRequest buildRequest() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.1");
            request.addHeader("User-Agent", "JUnit-Agent/1.0");
            request.setRemoteAddr("10.0.0.20");
            return request;
        }
    }
}
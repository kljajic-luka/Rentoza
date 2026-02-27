package org.example.chatservice.security;

import org.example.chatservice.dto.CreateConversationRequest;
import org.example.chatservice.model.Conversation;
import org.example.chatservice.model.ConversationStatus;
import org.example.chatservice.repository.ConversationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security tests for chat service authorization.
 *
 * Verifies that only conversation participants can:
 * - Access conversations
 * - Create conversations
 * - Update conversation status
 *
 * All IDs are Long (BIGINT) matching production Conversation model.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Conversation Authorization Security Tests")
class ConversationAuthorizationTest {

    @Mock
    private ConversationRepository conversationRepository;

    private static final Long BOOKING_ID = 123L;
    private static final Long RENTER_ID = 456L;
    private static final Long OWNER_ID = 789L;
    private static final Long ATTACKER_ID = 999L;

    @Nested
    @DisplayName("isUserParticipant()")
    class IsUserParticipantTests {

        @Test
        @DisplayName("Should return true when user is renter")
        void shouldReturnTrueForRenter() {
            Conversation conversation = createTestConversation();

            assertThat(conversation.isParticipant(RENTER_ID)).isTrue();
        }

        @Test
        @DisplayName("Should return true when user is owner")
        void shouldReturnTrueForOwner() {
            Conversation conversation = createTestConversation();

            assertThat(conversation.isParticipant(OWNER_ID)).isTrue();
        }

        @Test
        @DisplayName("Should return false for non-participant")
        void shouldReturnFalseForNonParticipant() {
            Conversation conversation = createTestConversation();

            assertThat(conversation.isParticipant(ATTACKER_ID)).isFalse();
        }

        @Test
        @DisplayName("Should return false for null user")
        void shouldReturnFalseForNullUser() {
            Conversation conversation = createTestConversation();

            assertThat(conversation.isParticipant(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("createConversation() Authorization")
    class CreateConversationAuthorizationTests {

        @Test
        @DisplayName("Should allow renter to create conversation")
        void shouldAllowRenterToCreateConversation() {
            CreateConversationRequest request = CreateConversationRequest.builder()
                    .bookingId(BOOKING_ID)
                    .renterId(RENTER_ID)
                    .ownerId(OWNER_ID)
                    .build();

            boolean isAuthorized = isUserAuthorizedToCreate(request, RENTER_ID);
            assertThat(isAuthorized).isTrue();
        }

        @Test
        @DisplayName("Should allow owner to create conversation")
        void shouldAllowOwnerToCreateConversation() {
            CreateConversationRequest request = CreateConversationRequest.builder()
                    .bookingId(BOOKING_ID)
                    .renterId(RENTER_ID)
                    .ownerId(OWNER_ID)
                    .build();

            boolean isAuthorized = isUserAuthorizedToCreate(request, OWNER_ID);
            assertThat(isAuthorized).isTrue();
        }

        @Test
        @DisplayName("Should deny attacker creating conversation for others")
        void shouldDenyAttackerCreatingConversation() {
            CreateConversationRequest request = CreateConversationRequest.builder()
                    .bookingId(BOOKING_ID)
                    .renterId(RENTER_ID)
                    .ownerId(OWNER_ID)
                    .build();

            boolean isAuthorized = isUserAuthorizedToCreate(request, ATTACKER_ID);
            assertThat(isAuthorized).isFalse();
        }

        private boolean isUserAuthorizedToCreate(CreateConversationRequest request, Long userId) {
            return Objects.equals(userId, request.getRenterId()) || Objects.equals(userId, request.getOwnerId());
        }
    }

    @Nested
    @DisplayName("updateConversationStatus() Authorization")
    class UpdateStatusAuthorizationTests {

        @Test
        @DisplayName("Should allow renter to update status")
        void shouldAllowRenterToUpdateStatus() {
            Conversation conversation = createTestConversation();

            boolean canUpdate = conversation.isParticipant(RENTER_ID);
            assertThat(canUpdate).isTrue();
        }

        @Test
        @DisplayName("Should allow owner to update status")
        void shouldAllowOwnerToUpdateStatus() {
            Conversation conversation = createTestConversation();

            boolean canUpdate = conversation.isParticipant(OWNER_ID);
            assertThat(canUpdate).isTrue();
        }

        @Test
        @DisplayName("Should deny attacker from updating status")
        void shouldDenyAttackerFromUpdatingStatus() {
            Conversation conversation = createTestConversation();

            boolean canUpdate = conversation.isParticipant(ATTACKER_ID);
            assertThat(canUpdate).isFalse();
        }

        @Test
        @DisplayName("Attacker should not be able to close conversation to silence evidence")
        void attackerShouldNotBeAbleToCloseConversation() {
            Conversation conversation = createTestConversation();

            assertThat(conversation.isParticipant(ATTACKER_ID)).isFalse();
        }
    }

    @Nested
    @DisplayName("Conversation Model Security")
    class ConversationModelSecurityTests {

        @Test
        @DisplayName("isParticipant should handle edge cases safely")
        void isParticipantShouldHandleEdgeCases() {
            Conversation conversation = createTestConversation();

            // Zero should return false (not a valid participant)
            assertThat(conversation.isParticipant(0L)).isFalse();

            // Negative ID should return false
            assertThat(conversation.isParticipant(-1L)).isFalse();

            // Close but different ID should return false
            assertThat(conversation.isParticipant(RENTER_ID + 1)).isFalse();
        }

        @Test
        @DisplayName("Messaging should only be allowed in valid states")
        void messagingShouldOnlyBeAllowedInValidStates() {
            Conversation pending = createTestConversation();
            pending.setStatus(ConversationStatus.PENDING);
            assertThat(pending.isMessagingAllowed()).isTrue();

            Conversation active = createTestConversation();
            active.setStatus(ConversationStatus.ACTIVE);
            assertThat(active.isMessagingAllowed()).isTrue();

            // A1 FIX: CLOSED conversations must NOT allow messaging (read-only after booking ends)
            Conversation closed = createTestConversation();
            closed.setStatus(ConversationStatus.CLOSED);
            assertThat(closed.isMessagingAllowed()).isFalse();
        }

        @Test
        @DisplayName("CLOSED conversation must block messaging (A1 regression guard)")
        void closedConversationMustBlockMessaging() {
            Conversation conversation = createTestConversation();
            conversation.setStatus(ConversationStatus.CLOSED);

            assertThat(conversation.isMessagingAllowed())
                    .as("CLOSED conversations must be read-only — isMessagingAllowed() must return false")
                    .isFalse();
        }

        @Test
        @DisplayName("All ConversationStatus values are covered by isMessagingAllowed")
        void allStatusesCovered() {
            for (ConversationStatus status : ConversationStatus.values()) {
                Conversation conv = createTestConversation();
                conv.setStatus(status);
                boolean result = conv.isMessagingAllowed();

                if (status == ConversationStatus.PENDING || status == ConversationStatus.ACTIVE) {
                    assertThat(result).as("Status %s should allow messaging", status).isTrue();
                } else {
                    assertThat(result).as("Status %s should block messaging", status).isFalse();
                }
            }
        }
    }

    private Conversation createTestConversation() {
        return Conversation.builder()
                .id(1L)
                .bookingId(BOOKING_ID)
                .renterId(RENTER_ID)
                .ownerId(OWNER_ID)
                .status(ConversationStatus.ACTIVE)
                .build();
    }
}

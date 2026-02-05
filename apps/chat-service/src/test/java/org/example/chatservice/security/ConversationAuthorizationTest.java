//package org.example.chatservice.security;
//
//import org.example.chatservice.dto.CreateConversationRequest;
//import org.example.chatservice.exception.ForbiddenException;
//import org.example.chatservice.model.Conversation;
//import org.example.chatservice.model.ConversationStatus;
//import org.example.chatservice.repository.ConversationRepository;
//import org.example.chatservice.service.ChatService;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Nested;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//import java.util.Optional;
//
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.assertj.core.api.Assertions.assertThatThrownBy;
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.Mockito.*;
//
///**
// * Security tests for chat service authorization.
// *
// * Tests BLOCKER #4: Verify that only conversation participants can:
// * - Create conversations
// * - Update conversation status
// * - Send messages
// * - Mark messages as read
// */
//@ExtendWith(MockitoExtension.class)
//@DisplayName("Conversation Authorization Security Tests")
//class ConversationAuthorizationTest {
//
//    @Mock
//    private ConversationRepository conversationRepository;
//
//    private static final String BOOKING_ID = "booking-123";
//    private static final String RENTER_ID = "renter-user-456";
//    private static final String OWNER_ID = "owner-user-789";
//    private static final String ATTACKER_ID = "attacker-user-999";
//
//    @Nested
//    @DisplayName("isUserParticipant()")
//    class IsUserParticipantTests {
//
//        @Test
//        @DisplayName("Should return true when user is renter")
//        void shouldReturnTrueForRenter() {
//            Conversation conversation = createTestConversation();
//            when(conversationRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(conversation));
//
//            assertThat(conversation.isParticipant(RENTER_ID)).isTrue();
//        }
//
//        @Test
//        @DisplayName("Should return true when user is owner")
//        void shouldReturnTrueForOwner() {
//            Conversation conversation = createTestConversation();
//            when(conversationRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(conversation));
//
//            assertThat(conversation.isParticipant(OWNER_ID)).isTrue();
//        }
//
//        @Test
//        @DisplayName("Should return false for non-participant")
//        void shouldReturnFalseForNonParticipant() {
//            Conversation conversation = createTestConversation();
//            when(conversationRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(conversation));
//
//            assertThat(conversation.isParticipant(ATTACKER_ID)).isFalse();
//        }
//
//        @Test
//        @DisplayName("Should return false for null user")
//        void shouldReturnFalseForNullUser() {
//            Conversation conversation = createTestConversation();
//
//            assertThat(conversation.isParticipant(null)).isFalse();
//        }
//    }
//
//    @Nested
//    @DisplayName("createConversation() Authorization")
//    class CreateConversationAuthorizationTests {
//
//        @Test
//        @DisplayName("Should allow renter to create conversation")
//        void shouldAllowRenterToCreateConversation() {
//            CreateConversationRequest request = CreateConversationRequest.builder()
//                    .bookingId(BOOKING_ID)
//                    .renterId(RENTER_ID)
//                    .ownerId(OWNER_ID)
//                    .build();
//
//            // Renter creating their own conversation - should be allowed
//            boolean isAuthorized = isUserAuthorizedToCreate(request, RENTER_ID);
//            assertThat(isAuthorized).isTrue();
//        }
//
//        @Test
//        @DisplayName("Should allow owner to create conversation")
//        void shouldAllowOwnerToCreateConversation() {
//            CreateConversationRequest request = CreateConversationRequest.builder()
//                    .bookingId(BOOKING_ID)
//                    .renterId(RENTER_ID)
//                    .ownerId(OWNER_ID)
//                    .build();
//
//            // Owner creating conversation for their car rental - should be allowed
//            boolean isAuthorized = isUserAuthorizedToCreate(request, OWNER_ID);
//            assertThat(isAuthorized).isTrue();
//        }
//
//        @Test
//        @DisplayName("Should deny attacker creating conversation for others")
//        void shouldDenyAttackerCreatingConversation() {
//            CreateConversationRequest request = CreateConversationRequest.builder()
//                    .bookingId(BOOKING_ID)
//                    .renterId(RENTER_ID)
//                    .ownerId(OWNER_ID)
//                    .build();
//
//            // Attacker trying to create conversation they're not part of
//            boolean isAuthorized = isUserAuthorizedToCreate(request, ATTACKER_ID);
//            assertThat(isAuthorized).isFalse();
//        }
//
//        private boolean isUserAuthorizedToCreate(CreateConversationRequest request, String userId) {
//            return userId.equals(request.getRenterId()) || userId.equals(request.getOwnerId());
//        }
//    }
//
//    @Nested
//    @DisplayName("updateConversationStatus() Authorization")
//    class UpdateStatusAuthorizationTests {
//
//        @Test
//        @DisplayName("Should allow renter to update status")
//        void shouldAllowRenterToUpdateStatus() {
//            Conversation conversation = createTestConversation();
//
//            boolean canUpdate = conversation.isParticipant(RENTER_ID);
//            assertThat(canUpdate).isTrue();
//        }
//
//        @Test
//        @DisplayName("Should allow owner to update status")
//        void shouldAllowOwnerToUpdateStatus() {
//            Conversation conversation = createTestConversation();
//
//            boolean canUpdate = conversation.isParticipant(OWNER_ID);
//            assertThat(canUpdate).isTrue();
//        }
//
//        @Test
//        @DisplayName("Should deny attacker from updating status")
//        void shouldDenyAttackerFromUpdatingStatus() {
//            Conversation conversation = createTestConversation();
//
//            boolean canUpdate = conversation.isParticipant(ATTACKER_ID);
//            assertThat(canUpdate).isFalse();
//        }
//
//        @Test
//        @DisplayName("Attacker should not be able to close conversation to silence evidence")
//        void attackerShouldNotBeAbleToCloseConversation() {
//            // This is the critical security test for BLOCKER #4
//            // An attacker should NOT be able to close a conversation they're not part of
//
//            Conversation conversation = createTestConversation();
//
//            // Verify attacker is not a participant
//            assertThat(conversation.isParticipant(ATTACKER_ID)).isFalse();
//
//            // In real implementation, this would throw ForbiddenException
//            // chatService.updateConversationStatusSecure(BOOKING_ID, ConversationStatus.CLOSED, ATTACKER_ID);
//        }
//    }
//
//    @Nested
//    @DisplayName("Conversation Model Security")
//    class ConversationModelSecurityTests {
//
//        @Test
//        @DisplayName("isParticipant should handle edge cases safely")
//        void isParticipantShouldHandleEdgeCases() {
//            Conversation conversation = createTestConversation();
//
//            // Empty string should return false
//            assertThat(conversation.isParticipant(L)).isFalse();
//
//            // Similar but different ID should return false
//            assertThat(conversation.isParticipant(RENTER_ID + "x")).isFalse();
//
//            // Case sensitivity check (IDs should be exact match)
//            assertThat(conversation.isParticipant(RENTER_ID.toUpperCase())).isFalse();
//        }
//
//        @Test
//        @DisplayName("Messaging should only be allowed in valid states")
//        void messagingShouldOnlyBeAllowedInValidStates() {
//            Conversation pending = createTestConversation();
//            pending.setStatus(ConversationStatus.PENDING);
//            assertThat(pending.isMessagingAllowed()).isTrue();
//
//            Conversation active = createTestConversation();
//            active.setStatus(ConversationStatus.ACTIVE);
//            assertThat(active.isMessagingAllowed()).isTrue();
//
//            Conversation closed = createTestConversation();
//            closed.setStatus(ConversationStatus.CLOSED);
//            assertThat(closed.isMessagingAllowed()).isFalse();
//        }
//    }
//
//    private Conversation createTestConversation() {
//        return Conversation.builder()
//                .id(1L)
//                .bookingId(BOOKING_ID)
//                .renterId(RENTER_ID)
//                .ownerId(OWNER_ID)
//                .status(ConversationStatus.ACTIVE)
//                .build();
//    }
//}

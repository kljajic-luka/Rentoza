package org.example.rentoza.notification;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.dispute.DamageClaim;
import org.example.rentoza.car.Car;
import org.example.rentoza.notification.channel.NotificationChannel;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Audit remediation tests for NotificationService.
 *
 * Covers:
 *   C4 - Escalation notification uses DISPUTE_ESCALATED (not DISPUTE_RESOLVED)
 *   C5 - Device token unregistration ownership check
 *   C1 - Outbox entry creation on durable-channel failure
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceAuditTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserDeviceTokenRepository deviceTokenRepository;

    @Mock
    private SchedulerIdempotencyService lockService;

    @Mock
    private NotificationDeliveryOutboxRepository deliveryOutboxRepository;

    /**
     * Service under test — constructed manually in setUp() so we can inject
     * a real (empty) channel list instead of a Mockito-mocked List, which
     * avoids NPE when sendThroughChannels iterates the collection.
     */
    private NotificationService notificationService;

    // ---- shared fixtures ----

    private User renter;
    private User owner;
    private Car car;
    private Booking booking;
    private DamageClaim claim;

    @BeforeEach
    void setUp() {
        renter = new User();
        renter.setId(1L);
        renter.setEmail("renter@test.com");

        owner = new User();
        owner.setId(2L);
        owner.setEmail("owner@test.com");

        car = new Car();
        car.setOwner(owner);

        booking = new Booking();
        booking.setId(100L);
        booking.setRenter(renter);
        booking.setCar(car);

        claim = DamageClaim.builder()
                .id(200L)
                .booking(booking)
                .build();

        // Construct with a real empty channel list (no channels to iterate)
        notificationService = new NotificationService(
                notificationRepository,
                userRepository,
                deviceTokenRepository,
                List.of(),           // empty — no channels fire for C4/C5 tests
                lockService,
                deliveryOutboxRepository
        );
    }

    // =========================================================================
    // C4: Escalation must use DISPUTE_ESCALATED, not DISPUTE_RESOLVED
    // =========================================================================

    @Nested
    @DisplayName("C4 - escalateCheckInDisputeToSeniorAdmin uses DISPUTE_ESCALATED")
    class C4_EscalationNotificationType {

        @Test
        @DisplayName("should create notifications with type DISPUTE_ESCALATED for both renter and owner")
        void escalation_uses_dispute_escalated_type() {
            // Arrange: stub userRepository.findById so createNotification succeeds
            when(userRepository.findById(renter.getId())).thenReturn(Optional.of(renter));
            when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));

            Notification savedNotification = Notification.builder()
                    .id(1L)
                    .recipient(renter)
                    .type(NotificationType.DISPUTE_ESCALATED)
                    .message("stub")
                    .build();
            when(notificationRepository.save(any(Notification.class))).thenReturn(savedNotification);

            // Act
            notificationService.escalateCheckInDisputeToSeniorAdmin(booking, claim);

            // Assert: createNotification is called twice (renter + owner)
            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository, times(2)).save(captor.capture());

            // Both saved notifications must carry DISPUTE_ESCALATED
            List<Notification> saved = captor.getAllValues();
            assertThat(saved).hasSize(2);
            assertThat(saved).allSatisfy(n ->
                    assertThat(n.getType()).isEqualTo(NotificationType.DISPUTE_ESCALATED));
        }
    }

    // =========================================================================
    // C5: Device token unregistration ownership check
    // =========================================================================

    @Nested
    @DisplayName("C5 - unregisterDeviceToken ownership verification")
    class C5_DeviceTokenOwnership {

        @Test
        @DisplayName("should throw IllegalStateException when token belongs to another user")
        void unregister_token_owned_by_different_user_throws() {
            // Arrange: token belongs to owner (id=2), caller is renter (id=1)
            UserDeviceToken token = UserDeviceToken.builder()
                    .id(10L)
                    .user(owner)
                    .deviceToken("fcm-token-abc")
                    .build();
            when(deviceTokenRepository.findByDeviceToken("fcm-token-abc"))
                    .thenReturn(Optional.of(token));

            // Act & Assert
            assertThatThrownBy(() ->
                    notificationService.unregisterDeviceToken("fcm-token-abc", renter.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot unregister another user's device token");

            // token must NOT be deleted
            verify(deviceTokenRepository, never()).delete(any(UserDeviceToken.class));
        }

        @Test
        @DisplayName("should succeed when token belongs to the requesting user")
        void unregister_own_token_succeeds() {
            // Arrange: token belongs to renter (id=1), caller is also renter (id=1)
            UserDeviceToken token = UserDeviceToken.builder()
                    .id(11L)
                    .user(renter)
                    .deviceToken("fcm-token-xyz")
                    .build();
            when(deviceTokenRepository.findByDeviceToken("fcm-token-xyz"))
                    .thenReturn(Optional.of(token));

            // Act (no exception expected)
            notificationService.unregisterDeviceToken("fcm-token-xyz", renter.getId());

            // Assert: token IS deleted
            verify(deviceTokenRepository).delete(token);
        }
    }

    // =========================================================================
    // C1: Outbox entry created when a durable channel fails
    // =========================================================================

    @Nested
    @DisplayName("C1 - outbox entry on durable-channel failure")
    class C1_OutboxOnChannelFailure {

        @Test
        @DisplayName("should save outbox entry when Email channel throws")
        void outbox_entry_saved_when_email_channel_throws() {
            verifyOutboxCreatedForFailingChannel("Email");
        }

        @Test
        @DisplayName("should save outbox entry when Firebase Push channel throws")
        void outbox_entry_saved_when_fcm_channel_throws() {
            verifyOutboxCreatedForFailingChannel("Firebase Push");
        }

        /**
         * Helper: builds a NotificationService with a single enabled channel
         * that throws on send(), then verifies an outbox entry is persisted.
         */
        private void verifyOutboxCreatedForFailingChannel(String channelName) {
            // Arrange: build a real channel list with one failing channel
            NotificationChannel failingChannel = mock(NotificationChannel.class);
            when(failingChannel.isEnabled()).thenReturn(true);
            when(failingChannel.getChannelName()).thenReturn(channelName);
            doThrow(new RuntimeException("channel down"))
                    .when(failingChannel).send(any(Notification.class));

            // Build service with the failing channel
            NotificationService serviceWithChannels = new NotificationService(
                    notificationRepository,
                    userRepository,
                    deviceTokenRepository,
                    List.of(failingChannel),
                    lockService,
                    deliveryOutboxRepository
            );

            // Stub repository calls for createNotification flow
            when(userRepository.findById(renter.getId())).thenReturn(Optional.of(renter));

            Notification savedNotification = Notification.builder()
                    .id(42L)
                    .recipient(renter)
                    .type(NotificationType.BOOKING_CONFIRMED)
                    .message("test")
                    .build();
            when(notificationRepository.save(any(Notification.class))).thenReturn(savedNotification);

            CreateNotificationRequestDTO request = CreateNotificationRequestDTO.builder()
                    .recipientId(renter.getId())
                    .type(NotificationType.BOOKING_CONFIRMED)
                    .message("test notification")
                    .relatedEntityId("99")
                    .build();

            // Act
            serviceWithChannels.createNotification(request);

            // Assert: outbox entry written for the failing durable channel
            ArgumentCaptor<NotificationDeliveryOutbox> outboxCaptor =
                    ArgumentCaptor.forClass(NotificationDeliveryOutbox.class);
            verify(deliveryOutboxRepository).save(outboxCaptor.capture());

            NotificationDeliveryOutbox outbox = outboxCaptor.getValue();
            assertThat(outbox.getNotificationId()).isEqualTo(42L);
            assertThat(outbox.getChannelName()).isEqualTo(channelName);
            assertThat(outbox.getStatus())
                    .isEqualTo(NotificationDeliveryOutbox.DeliveryStatus.PENDING);
            assertThat(outbox.getLastError()).contains("channel down");
        }
    }
}

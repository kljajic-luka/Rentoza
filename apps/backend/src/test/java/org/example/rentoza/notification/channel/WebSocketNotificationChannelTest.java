package org.example.rentoza.notification.channel;

import org.example.rentoza.notification.Notification;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WebSocketNotificationChannelTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Test
    @DisplayName("send routes notifications via convertAndSendToUser")
    void sendRoutesViaUserDestination() {
        User recipient = new User();
        recipient.setId(42L);

        Notification notification = Notification.builder()
                .id(10L)
                .recipient(recipient)
                .type(NotificationType.BOOKING_CONFIRMED)
                .message("Poruka")
                .build();

        WebSocketNotificationChannel channel = new WebSocketNotificationChannel(messagingTemplate);

        channel.send(notification);

        verify(messagingTemplate).convertAndSendToUser(eq("42"), eq("/queue/notifications"), any());
    }
}
package org.example.rentoza.user.verification.event;

import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.notification.mail.MailService;
import org.example.rentoza.user.User;
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
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for VerificationEventListener.
 * 
 * <p>Verifies that notification events are properly processed and
 * both in-app notifications and emails are sent.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VerificationEventListener Unit Tests")
class VerificationEventListenerTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private TemplateEngine templateEngine;

    @Mock
    private MailService mailService;

    @InjectMocks
    private VerificationEventListener listener;

    @Captor
    private ArgumentCaptor<CreateNotificationRequestDTO> notificationCaptor;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setFirstName("Marko");
        testUser.setLastName("Petrović");
        testUser.setEmail("marko@test.rs");
        
        // Inject the optional MailService
        org.springframework.test.util.ReflectionTestUtils.setField(listener, "mailService", mailService);
    }

    // ==================== APPROVAL EVENT TESTS ====================

    @Nested
    @DisplayName("Verification Approved Event Tests")
    class ApprovedEventTests {

        @Test
        @DisplayName("onVerificationApproved sends in-app notification")
        void onVerificationApproved_SendsInAppNotification() {
            // Arrange
            VerificationApprovedEvent event = new VerificationApprovedEvent(
                this, testUser, LocalDate.now().plusYears(1), "admin@rentoza.rs", "Good document"
            );
            when(templateEngine.process(anyString(), any(Context.class))).thenReturn("<html>Email</html>");

            // Act
            listener.onVerificationApproved(event);

            // Assert
            verify(notificationService).createNotification(notificationCaptor.capture());
            CreateNotificationRequestDTO notification = notificationCaptor.getValue();
            
            assertThat(notification.getRecipientId()).isEqualTo(testUser.getId());
            assertThat(notification.getType()).isEqualTo(NotificationType.LICENSE_VERIFICATION_APPROVED);
            assertThat(notification.getMessage()).contains("verifikovana");
        }

        @Test
        @DisplayName("onVerificationApproved sends email when MailService is available")
        void onVerificationApproved_SendsEmail() {
            // Arrange
            VerificationApprovedEvent event = new VerificationApprovedEvent(
                this, testUser, LocalDate.now().plusYears(1), "admin@rentoza.rs", "Good document"
            );
            when(templateEngine.process(eq("emails/license-approved"), any(Context.class)))
                .thenReturn("<html>Approval Email</html>");

            // Act
            listener.onVerificationApproved(event);

            // Assert
            verify(mailService).sendNotificationEmail(
                eq(testUser.getEmail()),
                contains("verifikovana"),
                anyString(),
                eq(NotificationType.LICENSE_VERIFICATION_APPROVED),
                eq(String.valueOf(testUser.getId()))
            );
        }

        @Test
        @DisplayName("onVerificationApproved handles missing MailService gracefully")
        void onVerificationApproved_NoMailService_NoException() {
            // Arrange - Remove mail service
            org.springframework.test.util.ReflectionTestUtils.setField(listener, "mailService", null);
            
            VerificationApprovedEvent event = new VerificationApprovedEvent(
                this, testUser, LocalDate.now().plusYears(1), "admin@rentoza.rs", "Good document"
            );

            // Act - Should not throw
            assertThatCode(() -> listener.onVerificationApproved(event))
                .doesNotThrowAnyException();

            // Assert - In-app notification still sent
            verify(notificationService).createNotification(any());
        }

        @Test
        @DisplayName("onVerificationApproved includes expiry date in message")
        void onVerificationApproved_IncludesExpiryDate() {
            // Arrange
            LocalDate expiryDate = LocalDate.of(2027, 6, 15);
            VerificationApprovedEvent event = new VerificationApprovedEvent(
                this, testUser, expiryDate, "SYSTEM", null
            );
            when(templateEngine.process(anyString(), any(Context.class))).thenReturn("<html>Email</html>");

            // Act
            listener.onVerificationApproved(event);

            // Assert
            verify(notificationService).createNotification(notificationCaptor.capture());
            assertThat(notificationCaptor.getValue().getMessage()).contains("15.06.2027");
        }
    }

    // ==================== REJECTION EVENT TESTS ====================

    @Nested
    @DisplayName("Verification Rejected Event Tests")
    class RejectedEventTests {

        @Test
        @DisplayName("onVerificationRejected sends in-app notification with reason")
        void onVerificationRejected_SendsInAppNotification() {
            // Arrange
            String rejectionReason = "Slika je mutna, molimo pošaljite jasniju sliku";
            VerificationRejectedEvent event = new VerificationRejectedEvent(
                this, testUser, rejectionReason, "admin@rentoza.rs"
            );
            when(templateEngine.process(anyString(), any(Context.class))).thenReturn("<html>Email</html>");

            // Act
            listener.onVerificationRejected(event);

            // Assert
            verify(notificationService).createNotification(notificationCaptor.capture());
            CreateNotificationRequestDTO notification = notificationCaptor.getValue();
            
            assertThat(notification.getRecipientId()).isEqualTo(testUser.getId());
            assertThat(notification.getType()).isEqualTo(NotificationType.LICENSE_VERIFICATION_REJECTED);
            assertThat(notification.getMessage()).contains(rejectionReason);
        }

        @Test
        @DisplayName("onVerificationRejected sends email with rejection details")
        void onVerificationRejected_SendsEmail() {
            // Arrange
            String rejectionReason = "Document expired";
            VerificationRejectedEvent event = new VerificationRejectedEvent(
                this, testUser, rejectionReason, "admin@rentoza.rs"
            );
            when(templateEngine.process(eq("emails/license-rejected"), any(Context.class)))
                .thenReturn("<html>Rejection Email</html>");

            // Act
            listener.onVerificationRejected(event);

            // Assert
            verify(mailService).sendNotificationEmail(
                eq(testUser.getEmail()),
                contains("ponovna provera"),
                anyString(),
                eq(NotificationType.LICENSE_VERIFICATION_REJECTED),
                eq(String.valueOf(testUser.getId()))
            );
        }
    }

    // ==================== EXPIRING EVENT TESTS ====================

    @Nested
    @DisplayName("License Expiring Event Tests")
    class ExpiringEventTests {

        @Test
        @DisplayName("onLicenseExpiring sends warning notification")
        void onLicenseExpiring_SendsWarningNotification() {
            // Arrange
            LicenseExpiringEvent event = new LicenseExpiringEvent(
                this, testUser, LocalDate.now().plusDays(30), 30
            );
            when(templateEngine.process(anyString(), any(Context.class))).thenReturn("<html>Email</html>");

            // Act
            listener.onLicenseExpiring(event);

            // Assert
            verify(notificationService).createNotification(notificationCaptor.capture());
            CreateNotificationRequestDTO notification = notificationCaptor.getValue();
            
            assertThat(notification.getRecipientId()).isEqualTo(testUser.getId());
            assertThat(notification.getType()).isEqualTo(NotificationType.LICENSE_EXPIRING_SOON);
            assertThat(notification.getMessage()).contains("30 dana");
        }

        @Test
        @DisplayName("onLicenseExpiring sends email with countdown")
        void onLicenseExpiring_SendsEmailWithCountdown() {
            // Arrange
            LicenseExpiringEvent event = new LicenseExpiringEvent(
                this, testUser, LocalDate.now().plusDays(7), 7
            );
            when(templateEngine.process(eq("emails/license-expiring"), any(Context.class)))
                .thenReturn("<html>Expiring Email</html>");

            // Act
            listener.onLicenseExpiring(event);

            // Assert
            verify(mailService).sendNotificationEmail(
                eq(testUser.getEmail()),
                contains("ističe uskoro"),
                anyString(),
                eq(NotificationType.LICENSE_EXPIRING_SOON),
                eq(String.valueOf(testUser.getId()))
            );
        }
    }

    // ==================== ERROR HANDLING TESTS ====================

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Notification service failure does not propagate exception")
        void notificationFailure_DoesNotPropagate() {
            // Arrange
            VerificationApprovedEvent event = new VerificationApprovedEvent(
                this, testUser, LocalDate.now().plusYears(1), "admin@rentoza.rs", null
            );
            doThrow(new RuntimeException("DB connection failed"))
                .when(notificationService).createNotification(any());

            // Act & Assert - Should not throw
            assertThatCode(() -> listener.onVerificationApproved(event))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Email service failure does not propagate exception")
        void emailFailure_DoesNotPropagate() {
            // Arrange
            VerificationApprovedEvent event = new VerificationApprovedEvent(
                this, testUser, LocalDate.now().plusYears(1), "admin@rentoza.rs", null
            );
            doThrow(new RuntimeException("SMTP connection failed"))
                .when(mailService).sendNotificationEmail(anyString(), anyString(), anyString(), any(), anyString());
            when(templateEngine.process(anyString(), any(Context.class))).thenReturn("<html>Email</html>");

            // Act & Assert - Should not throw
            assertThatCode(() -> listener.onVerificationApproved(event))
                .doesNotThrowAnyException();
        }
    }
}

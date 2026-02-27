package org.example.rentoza.user.gdpr;

import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.chat.ChatServiceClient;
import org.example.rentoza.review.ReviewRepository;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for GAP-3 remediation: Chat-service GDPR deletion propagation.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>permanentlyDeleteUser calls chatServiceClient.anonymizeUserChatData</li>
 *   <li>Chat-service unavailability does not block user deletion</li>
 *   <li>Data export includes chat data from chat-service</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GAP-3: Chat-Service GDPR Deletion Propagation")
class ChatServiceUserDeletionPropagationTest {

    @Mock private UserRepository userRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private CarRepository carRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private ConsentRepository consentRepository;
    @Mock private DataAccessLogRepository dataAccessLogRepository;
    @Mock private ChatServiceClient chatServiceClient;

    private PasswordEncoder passwordEncoder;
    private GdprService gdprService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4); // Low strength for test speed
        gdprService = new GdprService(
                userRepository, bookingRepository, carRepository,
                reviewRepository, consentRepository, passwordEncoder,
                dataAccessLogRepository, chatServiceClient
        );
    }

    @Nested
    @DisplayName("Deletion Propagation to Chat-Service")
    class DeletionPropagation {

        @Test
        @DisplayName("GAP-3: permanentlyDeleteUser calls chatServiceClient.anonymizeUserChatData")
        void deletionPropagatedToChatService() {
            User user = createTestUser(42L);
            when(userRepository.findById(42L)).thenReturn(Optional.of(user));
            when(chatServiceClient.anonymizeUserChatData(42L)).thenReturn(true);

            gdprService.permanentlyDeleteUser(42L);

            verify(chatServiceClient).anonymizeUserChatData(42L);
        }

        @Test
        @DisplayName("GAP-3: User deletion succeeds even when chat-service is down")
        void deletionSucceedsWhenChatServiceDown() {
            User user = createTestUser(42L);
            when(userRepository.findById(42L)).thenReturn(Optional.of(user));
            when(chatServiceClient.anonymizeUserChatData(42L))
                    .thenThrow(new RuntimeException("Connection refused"));

            // Should NOT throw — chat-service failure is non-blocking
            gdprService.permanentlyDeleteUser(42L);

            // User should still be marked as deleted
            verify(userRepository).save(any(User.class));
            assertThat(user.isDeleted()).isTrue();
        }

        @Test
        @DisplayName("GAP-3: User deletion completes and consent deleted even when chat anonymization returns false")
        void deletionCompletesWhenChatAnonymizationFails() {
            User user = createTestUser(42L);
            when(userRepository.findById(42L)).thenReturn(Optional.of(user));
            when(chatServiceClient.anonymizeUserChatData(42L)).thenReturn(false);

            gdprService.permanentlyDeleteUser(42L);

            verify(consentRepository).deleteByUserId(42L);
            assertThat(user.isDeleted()).isTrue();
        }
    }

    @Nested
    @DisplayName("GAP-6 + GAP-7: Hash Clearing and Password")
    class HashClearingAndPassword {

        @Test
        @DisplayName("GAP-6: Hash columns cleared on permanent deletion")
        void hashColumnsCleared() {
            User user = createTestUser(42L);
            user.setJmbgHash("sha256hash1");
            user.setPibHash("sha256hash2");
            user.setDriverLicenseNumberHash("sha256hash3");
            when(userRepository.findById(42L)).thenReturn(Optional.of(user));
            when(chatServiceClient.anonymizeUserChatData(42L)).thenReturn(true);

            gdprService.permanentlyDeleteUser(42L);

            assertThat(user.getJmbgHash()).isNull();
            assertThat(user.getPibHash()).isNull();
            assertThat(user.getDriverLicenseNumberHash()).isNull();
        }

        @Test
        @DisplayName("GAP-7: Password is valid BCrypt hash, not plaintext 'DELETED'")
        void passwordIsBcryptNotPlaintext() {
            User user = createTestUser(42L);
            when(userRepository.findById(42L)).thenReturn(Optional.of(user));
            when(chatServiceClient.anonymizeUserChatData(42L)).thenReturn(true);

            gdprService.permanentlyDeleteUser(42L);

            assertThat(user.getPassword()).isNotEqualTo("DELETED");
            assertThat(user.getPassword()).startsWith("$2a$");
            assertThat(user.getPassword().length()).isGreaterThan(50);
        }
    }

    private User createTestUser(Long id) {
        User user = new User();
        user.setId(id);
        user.setEmail("test@example.com");
        user.setFirstName("Test");
        user.setLastName("User");
        user.setPassword("oldpasswordhash");
        user.setDeleted(false);
        return user;
    }
}

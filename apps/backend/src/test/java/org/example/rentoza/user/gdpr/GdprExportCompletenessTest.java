package org.example.rentoza.user.gdpr;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.chat.ChatServiceClient;
import org.example.rentoza.review.Review;
import org.example.rentoza.review.ReviewRepository;
import org.example.rentoza.user.Role;
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

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests for GDPR export completeness — ensures all required data categories
 * are included in the Article 15 data export.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Profile, bookings, reviews, cars, consent history are included</li>
 *   <li>GAP-3: Chat data from chat-service is included when available</li>
 *   <li>Export is gracefully degraded when chat-service is unavailable</li>
 *   <li>Export version is updated to reflect new format</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GDPR Export Completeness")
class GdprExportCompletenessTest {

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
        passwordEncoder = new BCryptPasswordEncoder(4);
        gdprService = new GdprService(
                userRepository, bookingRepository, carRepository,
                reviewRepository, consentRepository, passwordEncoder,
                dataAccessLogRepository, chatServiceClient
        );
    }

    @Nested
    @DisplayName("Export Data Categories")
    class ExportDataCategories {

        @Test
        @DisplayName("Export includes all required data categories")
        void exportIncludesAllCategories() {
            User user = createFullUser(1L);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(bookingRepository.findByRenterId(1L)).thenReturn(Collections.emptyList());
            when(reviewRepository.findByReviewerEmailIgnoreCase(any())).thenReturn(Collections.emptyList());
            when(carRepository.findByOwner(user)).thenReturn(Collections.emptyList());
            when(consentRepository.findByUserIdOrderByTimestampDesc(1L)).thenReturn(Collections.emptyList());
            when(chatServiceClient.exportUserChatData(1L)).thenReturn(Collections.emptyMap());

            UserDataExportDTO export = gdprService.exportUserData(1L);

            assertThat(export.getExportDate()).isNotNull();
            assertThat(export.getDataSubjectId()).isEqualTo(1L);
            assertThat(export.getProfile()).isNotNull();
            assertThat(export.getProfile().getEmail()).isEqualTo("test@example.com");
            assertThat(export.getProfile().getFirstName()).isEqualTo("Test");
            assertThat(export.getBookings()).isNotNull();
            assertThat(export.getReviews()).isNotNull();
            assertThat(export.getCars()).isNotNull();
            assertThat(export.getConsentHistory()).isNotNull();
        }
    }

    @Nested
    @DisplayName("GAP-3: Chat Data in Export")
    class ChatDataInExport {

        @Test
        @DisplayName("GAP-3: Chat data included when chat-service is available")
        void chatDataIncluded() {
            User user = createFullUser(1L);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(bookingRepository.findByRenterId(1L)).thenReturn(Collections.emptyList());
            when(reviewRepository.findByReviewerEmailIgnoreCase(any())).thenReturn(Collections.emptyList());
            when(carRepository.findByOwner(user)).thenReturn(Collections.emptyList());
            when(consentRepository.findByUserIdOrderByTimestampDesc(1L)).thenReturn(Collections.emptyList());

            Map<String, Object> chatData = Map.of(
                    "messages", List.of(Map.of("content", "Hello", "timestamp", "2024-01-01T10:00:00")),
                    "conversations", List.of(Map.of("bookingId", 123L, "role", "RENTER"))
            );
            when(chatServiceClient.exportUserChatData(1L)).thenReturn(chatData);

            UserDataExportDTO export = gdprService.exportUserData(1L);

            assertThat(export.getChatData()).isNotNull();
            assertThat(export.getChatData()).containsKey("messages");
            assertThat(export.getChatData()).containsKey("conversations");
        }

        @Test
        @DisplayName("GAP-3: Export succeeds without chat data when chat-service is down")
        void exportSucceedsWithoutChat() {
            User user = createFullUser(1L);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(bookingRepository.findByRenterId(1L)).thenReturn(Collections.emptyList());
            when(reviewRepository.findByReviewerEmailIgnoreCase(any())).thenReturn(Collections.emptyList());
            when(carRepository.findByOwner(user)).thenReturn(Collections.emptyList());
            when(consentRepository.findByUserIdOrderByTimestampDesc(1L)).thenReturn(Collections.emptyList());
            when(chatServiceClient.exportUserChatData(1L))
                    .thenThrow(new RuntimeException("Connection refused"));

            UserDataExportDTO export = gdprService.exportUserData(1L);

            // Export should succeed, chat data just missing
            assertThat(export).isNotNull();
            assertThat(export.getProfile()).isNotNull();
            assertThat(export.getChatData()).isNull();
        }

        @Test
        @DisplayName("Export version reflects chat data inclusion")
        void exportVersionUpdated() {
            User user = createFullUser(1L);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(bookingRepository.findByRenterId(1L)).thenReturn(Collections.emptyList());
            when(reviewRepository.findByReviewerEmailIgnoreCase(any())).thenReturn(Collections.emptyList());
            when(carRepository.findByOwner(user)).thenReturn(Collections.emptyList());
            when(consentRepository.findByUserIdOrderByTimestampDesc(1L)).thenReturn(Collections.emptyList());
            when(chatServiceClient.exportUserChatData(1L)).thenReturn(Collections.emptyMap());

            UserDataExportDTO export = gdprService.exportUserData(1L);

            assertThat(export.getExportVersion()).isEqualTo("1.1");
        }
    }

    private User createFullUser(Long id) {
        User user = new User();
        user.setId(id);
        user.setEmail("test@example.com");
        user.setFirstName("Test");
        user.setLastName("User");
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return user;
    }
}

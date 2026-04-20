package org.example.rentoza.user.gdpr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests for GAP-5 remediation: Real data access log replacing fake stub.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>getDataAccessLog returns data from persisted DataAccessLog table</li>
 *   <li>No hardcoded/fabricated data is returned</li>
 *   <li>logDataAccess persists entries correctly</li>
 *   <li>Empty result is returned when no access log entries exist</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GAP-5: Data Access Log Realness")
class DataAccessLogRealnessTest {

    @Mock private org.example.rentoza.user.UserRepository userRepository;
    @Mock private org.example.rentoza.booking.BookingRepository bookingRepository;
    @Mock private org.example.rentoza.car.CarRepository carRepository;
    @Mock private org.example.rentoza.review.ReviewRepository reviewRepository;
    @Mock private ConsentRepository consentRepository;
    @Mock private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Mock private DataAccessLogRepository dataAccessLogRepository;
    @Mock private org.example.rentoza.chat.ChatServiceClient chatServiceClient;

    private GdprService gdprService;

    @BeforeEach
    void setUp() {
        gdprService = new GdprService(
                userRepository, bookingRepository, carRepository,
                reviewRepository, consentRepository, passwordEncoder,
                dataAccessLogRepository, chatServiceClient
        );
    }

    @Nested
    @DisplayName("Real Data Queries")
    class RealDataQueries {

        @Test
        @DisplayName("GAP-5: Returns empty list when no access logs exist (not hardcoded data)")
        void returnsEmptyWhenNoLogs() {
            when(dataAccessLogRepository.findByUserIdAndTimestampAfter(eq(1L), any()))
                    .thenReturn(Collections.emptyList());

            List<DataAccessLogEntry> result = gdprService.getDataAccessLog(1L, 30);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("GAP-5: Returns real persisted access log entries")
        void returnsRealPersistedEntries() {
            DataAccessLog entry1 = DataAccessLog.of(1L, 99L, "ADMIN",
                    "VIEW_PROFILE", "Admin viewed user profile", "Admin Panel", "10.0.0.1");
            DataAccessLog entry2 = DataAccessLog.of(1L, 1L, "USER",
                    "EXPORT_DATA", "User exported their data", "Web App", "192.168.1.10");

            when(dataAccessLogRepository.findByUserIdAndTimestampAfter(eq(1L), any()))
                    .thenReturn(List.of(entry1, entry2));

            List<DataAccessLogEntry> result = gdprService.getDataAccessLog(1L, 30);

            assertThat(result).hasSize(2);

            assertThat(result.get(0).getAction()).isEqualTo("VIEW_PROFILE");
            assertThat(result.get(0).getDescription()).isEqualTo("Admin viewed user profile");
            assertThat(result.get(0).getSource()).isEqualTo("Admin Panel");

            assertThat(result.get(1).getAction()).isEqualTo("EXPORT_DATA");
            assertThat(result.get(1).getDescription()).isEqualTo("User exported their data");
            assertThat(result.get(1).getSource()).isEqualTo("Web App");
        }

        @Test
        @DisplayName("GAP-5: Access log entries are user-specific (not shared across users)")
        void entriesAreUserSpecific() {
            DataAccessLog user1Entry = DataAccessLog.of(1L, null, "SYSTEM",
                    "BACKUP", "System backup processed", "System", null);

            when(dataAccessLogRepository.findByUserIdAndTimestampAfter(eq(1L), any()))
                    .thenReturn(List.of(user1Entry));
            when(dataAccessLogRepository.findByUserIdAndTimestampAfter(eq(2L), any()))
                    .thenReturn(Collections.emptyList());

            List<DataAccessLogEntry> user1Result = gdprService.getDataAccessLog(1L, 30);
            List<DataAccessLogEntry> user2Result = gdprService.getDataAccessLog(2L, 30);

            assertThat(user1Result).hasSize(1);
            assertThat(user2Result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Audit Trail Persistence")
    class AuditTrailPersistence {

        @Test
        @DisplayName("GAP-5: logDataAccess saves entry to repository")
        void logDataAccessSavesEntry() {
            gdprService.logDataAccess(1L, 99L, "ADMIN",
                    "VIEW_PROFILE", "Admin viewed profile", "Admin Panel", "10.0.0.1");

            org.mockito.ArgumentCaptor<DataAccessLog> captor =
                    org.mockito.ArgumentCaptor.forClass(DataAccessLog.class);
            org.mockito.Mockito.verify(dataAccessLogRepository).save(captor.capture());

            DataAccessLog saved = captor.getValue();
            assertThat(saved.getUserId()).isEqualTo(1L);
            assertThat(saved.getAccessorId()).isEqualTo(99L);
            assertThat(saved.getAccessorType()).isEqualTo("ADMIN");
            assertThat(saved.getAction()).isEqualTo("VIEW_PROFILE");
            assertThat(saved.getIpAddress()).isEqualTo("10.0.0.1");
            assertThat(saved.getTimestamp()).isNotNull();
        }
    }
}

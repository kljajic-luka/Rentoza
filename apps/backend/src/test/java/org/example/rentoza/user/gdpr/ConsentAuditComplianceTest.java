package org.example.rentoza.user.gdpr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for GAP-4 remediation: Consent provenance with real IP and User-Agent.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Consent records capture real client IP (not "0.0.0.0")</li>
 *   <li>Consent records capture User-Agent header</li>
 *   <li>All four consent types are saved with provenance data</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GAP-4: Consent Audit Compliance — Real IP/UserAgent")
class ConsentAuditComplianceTest {

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
    @DisplayName("Consent Provenance Capture")
    class ConsentProvenance {

        @Test
        @DisplayName("GAP-4: Consent records store real client IP, not '0.0.0.0'")
        void consentRecordsHaveRealIp() {
            String realIp = "192.168.1.100";
            String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)";

            when(consentRepository.findLatestByUserId(1L)).thenReturn(Collections.emptyList());

            ConsentPreferencesDTO prefs = new ConsentPreferencesDTO();
            prefs.setMarketingEmails(true);
            prefs.setSmsNotifications(false);
            prefs.setAnalyticsTracking(true);
            prefs.setThirdPartySharing(false);

            gdprService.updateConsentPreferences(1L, prefs, realIp, userAgent);

            ArgumentCaptor<UserConsent> captor = ArgumentCaptor.forClass(UserConsent.class);
            verify(consentRepository, times(4)).save(captor.capture());

            for (UserConsent saved : captor.getAllValues()) {
                assertThat(saved.getIpAddress())
                        .as("IP should be real, not stub")
                        .isEqualTo(realIp)
                        .isNotEqualTo("0.0.0.0");

                assertThat(saved.getUserAgent())
                        .as("User-Agent should be captured")
                        .isEqualTo(userAgent);
            }
        }

        @Test
        @DisplayName("GAP-4: All four consent types are saved")
        void allFourConsentTypesSaved() {
            String ip = "10.0.0.1";
            String ua = "TestAgent/1.0";

            when(consentRepository.findLatestByUserId(1L)).thenReturn(Collections.emptyList());

            ConsentPreferencesDTO prefs = new ConsentPreferencesDTO();
            prefs.setMarketingEmails(true);
            prefs.setSmsNotifications(true);
            prefs.setAnalyticsTracking(false);
            prefs.setThirdPartySharing(false);

            gdprService.updateConsentPreferences(1L, prefs, ip, ua);

            ArgumentCaptor<UserConsent> captor = ArgumentCaptor.forClass(UserConsent.class);
            verify(consentRepository, times(4)).save(captor.capture());

            var types = captor.getAllValues().stream()
                    .map(UserConsent::getConsentType)
                    .toList();

            assertThat(types).containsExactlyInAnyOrder(
                    "MARKETING_EMAILS",
                    "SMS_NOTIFICATIONS",
                    "ANALYTICS_TRACKING",
                    "THIRD_PARTY_SHARING"
            );
        }

        @Test
        @DisplayName("GAP-4: Consent values are correctly saved")
        void consentValuesCorrect() {
            when(consentRepository.findLatestByUserId(1L)).thenReturn(Collections.emptyList());

            ConsentPreferencesDTO prefs = new ConsentPreferencesDTO();
            prefs.setMarketingEmails(true);
            prefs.setSmsNotifications(false);
            prefs.setAnalyticsTracking(true);
            prefs.setThirdPartySharing(false);

            gdprService.updateConsentPreferences(1L, prefs, "1.2.3.4", "TestAgent");

            ArgumentCaptor<UserConsent> captor = ArgumentCaptor.forClass(UserConsent.class);
            verify(consentRepository, times(4)).save(captor.capture());

            for (UserConsent saved : captor.getAllValues()) {
                if ("MARKETING_EMAILS".equals(saved.getConsentType())) {
                    assertThat(saved.isGranted()).isTrue();
                }
                if ("SMS_NOTIFICATIONS".equals(saved.getConsentType())) {
                    assertThat(saved.isGranted()).isFalse();
                }
            }
        }
    }
}

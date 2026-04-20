package org.example.rentoza.booking.checkin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ExifValidationService} with focus on location validation.
 * 
 * <p>Tests the complete EXIF validation flow including:
 * <ul>
 *   <li>Timestamp validation (photo age checks)</li>
 *   <li>GPS location validation (fraud prevention)</li>
 *   <li>Haversine distance calculation accuracy</li>
 *   <li>Edge cases (missing coordinates, border cases)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExifValidationService Location Validation Tests")
class ExifValidationServiceTest {

    private ExifValidationService service;

    @BeforeEach
    void setUp() {
        service = new ExifValidationService();
        
        // Configure service with default test values
        ReflectionTestUtils.setField(service, "maxAgeMinutes", 10);
        ReflectionTestUtils.setField(service, "clientTimestampToleranceSeconds", 300);
        ReflectionTestUtils.setField(service, "requireGps", false);
        ReflectionTestUtils.setField(service, "maxDistanceMeters", 1000); // 1km threshold
        ReflectionTestUtils.setField(service, "clientTimestampMaxAgeMinutes", 5); // SECURITY FIX
    }

    // NOTE: LocationValidationTests removed in Phase 2 - validateLocation() method deprecated
    // Car location is now derived from first valid photo EXIF, not validated against submitted location

    @Nested
    @DisplayName("Haversine Distance Calculation Tests")
    class HaversineDistanceTests {

        @Test
        @DisplayName("Should calculate distance of 0m for identical coordinates")
        void shouldCalculateZeroDistanceForIdenticalCoordinates() {
            double lat = 44.816268;
            double lon = 20.458953;
            
            double distance = invokeHaversineDistance(lat, lon, lat, lon);
            
            assertThat(distance)
                .as("Distance between identical coordinates should be 0m")
                .isLessThan(1.0); // Allow floating point tolerance
        }

        @Test
        @DisplayName("Should calculate approximately 1000m for 0.009 degree latitude difference")
        void shouldCalculateApproximately1000MetersForSmallLatDifference() {
            double lat1 = 44.816268;
            double lon = 20.458953;
            double lat2 = 44.825268; // ~1000m north
            
            double distance = invokeHaversineDistance(lat1, lon, lat2, lon);
            
            assertThat(distance)
                .as("Distance for ~0.009 degree latitude should be ~1000m")
                .isBetween(950.0, 1050.0); // Allow 5% tolerance
        }

        @Test
        @DisplayName("Should calculate long distance correctly (Belgrade to Novi Sad ~70km)")
        void shouldCalculateLongDistanceCorrectly() {
            // Belgrade Republic Square
            double belgradeLat = 44.816268;
            double belgradeLon = 20.458953;
            
            // Novi Sad Freedom Square
            double noviSadLat = 45.254458;
            double noviSadLon = 19.844549;
            
            double distance = invokeHaversineDistance(belgradeLat, belgradeLon, noviSadLat, noviSadLon);
            
            assertThat(distance)
                .as("Distance Belgrade-Novi Sad should be ~69km (straight line)")
                .isBetween(65000.0, 72000.0); // 65-72km (straight line, not road distance)
        }

        @Test
        @DisplayName("Should handle negative longitude correctly (Western hemisphere)")
        void shouldHandleNegativeLongitude() {
            // Test with Western hemisphere coordinates (e.g., New York)
            double lat1 = 40.7128;
            double lon1 = -74.0060;
            double lat2 = 40.7128;
            double lon2 = -74.0060;
            
            double distance = invokeHaversineDistance(lat1, lon1, lat2, lon2);
            
            assertThat(distance)
                .as("Distance for negative longitude should calculate correctly")
                .isLessThan(1.0);
        }

        @Test
        @DisplayName("Should handle negative latitude correctly (Southern hemisphere)")
        void shouldHandleNegativeLatitude() {
            // Test with Southern hemisphere coordinates (e.g., Sydney)
            double lat1 = -33.8688;
            double lon = 151.2093;
            double lat2 = -33.8688;
            
            double distance = invokeHaversineDistance(lat1, lon, lat2, lon);
            
            assertThat(distance)
                .as("Distance for negative latitude should calculate correctly")
                .isLessThan(1.0);
        }

        /**
         * Helper to invoke private haversineDistance method via reflection.
         */
        private double invokeHaversineDistance(double lat1, double lon1, double lat2, double lon2) {
            try {
                var method = ExifValidationService.class.getDeclaredMethod(
                    "haversineDistance", double.class, double.class, double.class, double.class);
                method.setAccessible(true);
                return (double) method.invoke(service, lat1, lon1, lat2, lon2);
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke haversineDistance", e);
            }
        }
    }

    // NOTE: ConfigurationTests removed in Phase 2 - validateLocation() tests no longer needed
    // NOTE: EdgeCaseTests removed in Phase 2 - validateLocation() tests no longer needed

    @Nested
    @DisplayName("HEIC Fallback Security Tests (Critical Fix)")
    class HeicFallbackSecurityTests {

        @Test
        @DisplayName("Should reject HEIC photo with stale client timestamp (> 5 minutes)")
        void shouldRejectHeicWithStaleClientTimestamp() throws Exception {
            // Simulate HEIC photo (no EXIF, triggers IOException)
            byte[] mockHeicBytes = new byte[]{0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70}; // HEIC header
            
            // Client timestamp 10 minutes ago (exceeds 5-minute threshold)
            Instant staleTimestamp = Instant.now().minusSeconds(600);
            
            ExifValidationService.ExifValidationResult result = service.validate(
                mockHeicBytes, staleTimestamp
            );
            
            assertThat(result.getStatus())
                .as("HEIC photo with 10-minute-old client timestamp should be REJECTED_TOO_OLD")
                .isEqualTo(ExifValidationStatus.REJECTED_TOO_OLD);
            
            assertThat(result.getMessage())
                .as("Rejection message should mention stale upload time")
                .contains("previše staro")
                .contains("10");
        }

        @Test
        @DisplayName("Should accept HEIC photo with fresh client timestamp (< 5 minutes)")
        void shouldAcceptHeicWithFreshClientTimestamp() throws Exception {
            // Simulate HEIC photo (no EXIF, triggers IOException)
            byte[] mockHeicBytes = new byte[]{0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70};
            
            // Client timestamp 30 seconds ago (well within 5-minute threshold)
            Instant freshTimestamp = Instant.now().minusSeconds(30);
            
            ExifValidationService.ExifValidationResult result = service.validate(
                mockHeicBytes, freshTimestamp
            );
            
            assertThat(result.getStatus())
                .as("HEIC photo with 30-second-old client timestamp should be VALID_WITH_WARNINGS")
                .isEqualTo(ExifValidationStatus.VALID_WITH_WARNINGS);
            
            assertThat(result.isClientTimestampUsed())
                .as("Client timestamp should be used for HEIC fallback")
                .isTrue();
            
            assertThat(result.getPhotoAgeMinutes())
                .as("Photo age should be calculated from client timestamp")
                .isEqualTo(0);
        }

        @Test
        @DisplayName("Should reject HEIC photo with future client timestamp (clock manipulation)")
        void shouldRejectHeicWithFutureClientTimestamp() throws Exception {
            // Simulate HEIC photo
            byte[] mockHeicBytes = new byte[]{0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70};
            
            // Client timestamp 10 minutes in the future (exceeds tolerance)
            Instant futureTimestamp = Instant.now().plusSeconds(600);
            
            ExifValidationService.ExifValidationResult result = service.validate(
                mockHeicBytes, futureTimestamp
            );
            
            assertThat(result.getStatus())
                .as("HEIC photo with future client timestamp should be REJECTED_FUTURE_TIMESTAMP")
                .isEqualTo(ExifValidationStatus.REJECTED_FUTURE_TIMESTAMP);
            
            assertThat(result.getMessage())
                .as("Rejection message should mention future timestamp")
                .contains("budućnosti");
        }

        @Test
        @DisplayName("Should reject HEIC photo with 27-hour-old client timestamp (fraud scenario)")
        void shouldRejectHeicWith27HourOldClientTimestamp() throws Exception {
            // Simulate HEIC photo
            byte[] mockHeicBytes = new byte[]{0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70};
            
            // Client timestamp from yesterday (27 hours ago)
            // This is the actual bug scenario: user uploads 27-hour-old photo
            Instant yesterdayTimestamp = Instant.now().minusSeconds(27 * 3600);
            
            ExifValidationService.ExifValidationResult result = service.validate(
                mockHeicBytes, yesterdayTimestamp
            );
            
            assertThat(result.getStatus())
                .as("HEIC photo with 27-hour-old client timestamp should be REJECTED_TOO_OLD")
                .isEqualTo(ExifValidationStatus.REJECTED_TOO_OLD);
            
            assertThat(result.getMessage())
                .as("Rejection message should show actual age in minutes")
                .contains("1620"); // 27 hours * 60 minutes
        }

        @Test
        @DisplayName("Should reject HEIC photo without client timestamp (no sidecar)")
        void shouldRejectHeicWithoutClientTimestamp() throws Exception {
            // Simulate HEIC photo
            byte[] mockHeicBytes = new byte[]{0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70};
            
            // No client timestamp provided
            ExifValidationService.ExifValidationResult result = service.validate(
                mockHeicBytes, null
            );
            
            assertThat(result.getStatus())
                .as("HEIC photo without client timestamp should be REJECTED_NO_EXIF")
                .isEqualTo(ExifValidationStatus.REJECTED_NO_EXIF);
            
            assertThat(result.getMessage())
                .as("Rejection message should mention error reading metadata")
                .containsAnyOf("Greška pri čitanju", "metapodataka");
        }

        @Test
        @DisplayName("Should accept HEIC photo at exactly 5-minute boundary")
        void shouldAcceptHeicAtExactBoundary() throws Exception {
            // Simulate HEIC photo
            byte[] mockHeicBytes = new byte[]{0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70};
            
            // Client timestamp exactly 5 minutes ago (at boundary)
            Instant boundaryTimestamp = Instant.now().minusSeconds(300);
            
            ExifValidationService.ExifValidationResult result = service.validate(
                mockHeicBytes, boundaryTimestamp
            );
            
            assertThat(result.getStatus())
                .as("HEIC photo at 5-minute boundary should be VALID_WITH_WARNINGS")
                .isEqualTo(ExifValidationStatus.VALID_WITH_WARNINGS);
        }

        @Test
        @DisplayName("Should reject HEIC photo just over 5-minute boundary")
        void shouldRejectHeicJustOverBoundary() throws Exception {
            // Simulate HEIC photo
            byte[] mockHeicBytes = new byte[]{0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70};
            
            // Client timestamp 6 minutes ago (clearly over 5-minute boundary)
            Instant overBoundaryTimestamp = Instant.now().minusSeconds(360);
            
            ExifValidationService.ExifValidationResult result = service.validate(
                mockHeicBytes, overBoundaryTimestamp
            );
            
            assertThat(result.getStatus())
                .as("HEIC photo at 6 minutes should be REJECTED_TOO_OLD")
                .isEqualTo(ExifValidationStatus.REJECTED_TOO_OLD);
        }
    }
}

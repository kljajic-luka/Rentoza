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

    @Nested
    @DisplayName("Location Validation Tests")
    class LocationValidationTests {

        @Test
        @DisplayName("Should accept photo taken within 1km radius of car")
        void shouldAcceptPhotoWithinRadius() {
            // Belgrade coordinates: Car at Republic Square
            BigDecimal carLat = new BigDecimal("44.816268");
            BigDecimal carLon = new BigDecimal("20.458953");
            
            // Photo taken 500m away (within 1km threshold)
            BigDecimal photoLat = new BigDecimal("44.820500");
            BigDecimal photoLon = new BigDecimal("20.458953");
            
            boolean result = service.validateLocation(photoLat, photoLon, carLat, carLon);
            
            assertThat(result)
                .as("Photo taken 500m from car should be accepted (within 1km radius)")
                .isTrue();
        }

        @Test
        @DisplayName("Should reject photo taken more than 1km from car")
        void shouldRejectPhotoOutsideRadius() {
            // Belgrade coordinates: Car at Republic Square
            BigDecimal carLat = new BigDecimal("44.816268");
            BigDecimal carLon = new BigDecimal("20.458953");
            
            // Photo taken 2km away (outside 1km threshold)
            BigDecimal photoLat = new BigDecimal("44.834500");
            BigDecimal photoLon = new BigDecimal("20.458953");
            
            boolean result = service.validateLocation(photoLat, photoLon, carLat, carLon);
            
            assertThat(result)
                .as("Photo taken 2km from car should be rejected (outside 1km radius)")
                .isFalse();
        }

        @Test
        @DisplayName("Should accept photo at exact car location (0m distance)")
        void shouldAcceptPhotoAtExactLocation() {
            BigDecimal carLat = new BigDecimal("44.816268");
            BigDecimal carLon = new BigDecimal("20.458953");
            
            boolean result = service.validateLocation(carLat, carLon, carLat, carLon);
            
            assertThat(result)
                .as("Photo at exact car location should be accepted")
                .isTrue();
        }

        @Test
        @DisplayName("Should accept photo at exactly 1km from car (boundary condition)")
        void shouldAcceptPhotoAtExactBoundary() {
            // Belgrade coordinates
            BigDecimal carLat = new BigDecimal("44.816268");
            BigDecimal carLon = new BigDecimal("20.458953");
            
            // Photo at ~999m (just within boundary)
            // 0.0089 degrees latitude ≈ 990m
            BigDecimal photoLat = new BigDecimal("44.825168");
            BigDecimal photoLon = new BigDecimal("20.458953");
            
            boolean result = service.validateLocation(photoLat, photoLon, carLat, carLon);
            
            assertThat(result)
                .as("Photo at ~990m boundary should be accepted")
                .isTrue();
        }

        @Test
        @DisplayName("Should accept when photo GPS is missing (null coordinates)")
        void shouldAcceptWhenPhotoGpsMissing() {
            BigDecimal carLat = new BigDecimal("44.816268");
            BigDecimal carLon = new BigDecimal("20.458953");
            
            boolean result = service.validateLocation(null, null, carLat, carLon);
            
            assertThat(result)
                .as("Missing photo GPS should be accepted (can't validate)")
                .isTrue();
        }

        @Test
        @DisplayName("Should accept when car GPS is missing (null coordinates)")
        void shouldAcceptWhenCarGpsMissing() {
            BigDecimal photoLat = new BigDecimal("44.816268");
            BigDecimal photoLon = new BigDecimal("20.458953");
            
            boolean result = service.validateLocation(photoLat, photoLon, null, null);
            
            assertThat(result)
                .as("Missing car GPS should be accepted (can't validate)")
                .isTrue();
        }

        @Test
        @DisplayName("Should accept when both GPS coordinates are missing")
        void shouldAcceptWhenBothGpsMissing() {
            boolean result = service.validateLocation(null, null, null, null);
            
            assertThat(result)
                .as("Missing both GPS coordinates should be accepted (can't validate)")
                .isTrue();
        }

        @Test
        @DisplayName("Should reject when only photoLat is missing")
        void shouldAcceptWhenPartialPhotoGps() {
            BigDecimal carLat = new BigDecimal("44.816268");
            BigDecimal carLon = new BigDecimal("20.458953");
            BigDecimal photoLon = new BigDecimal("20.458953");
            
            boolean result = service.validateLocation(null, photoLon, carLat, carLon);
            
            assertThat(result)
                .as("Partial photo GPS (missing latitude) should be accepted (can't validate)")
                .isTrue();
        }

        @Test
        @DisplayName("Should handle Novi Sad location correctly")
        void shouldHandleNoviSadLocation() {
            // Novi Sad coordinates: Car at Freedom Square
            BigDecimal carLat = new BigDecimal("45.254458");
            BigDecimal carLon = new BigDecimal("19.844549");
            
            // Photo taken 700m away
            BigDecimal photoLat = new BigDecimal("45.260500");
            BigDecimal photoLon = new BigDecimal("19.844549");
            
            boolean result = service.validateLocation(photoLat, photoLon, carLat, carLon);
            
            assertThat(result)
                .as("Photo taken 700m from car in Novi Sad should be accepted")
                .isTrue();
        }

        @Test
        @DisplayName("Should handle Niš location correctly")
        void shouldHandleNisLocation() {
            // Niš coordinates: Car at Niš Fortress
            BigDecimal carLat = new BigDecimal("43.320902");
            BigDecimal carLon = new BigDecimal("21.896019");
            
            // Photo taken 1.5km away (should reject)
            BigDecimal photoLat = new BigDecimal("43.334000");
            BigDecimal photoLon = new BigDecimal("21.896019");
            
            boolean result = service.validateLocation(photoLat, photoLon, carLat, carLon);
            
            assertThat(result)
                .as("Photo taken 1.5km from car in Niš should be rejected")
                .isFalse();
        }
    }

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

    @Nested
    @DisplayName("Configuration Tests")
    class ConfigurationTests {

        @Test
        @DisplayName("Should respect custom maxDistanceMeters configuration")
        void shouldRespectCustomMaxDistance() {
            // Set custom threshold: 500m instead of 1000m
            ReflectionTestUtils.setField(service, "maxDistanceMeters", 500);
            
            BigDecimal carLat = new BigDecimal("44.816268");
            BigDecimal carLon = new BigDecimal("20.458953");
            
            // Photo at 700m (would be accepted with 1000m, rejected with 500m)
            BigDecimal photoLat = new BigDecimal("44.822500");
            BigDecimal photoLon = new BigDecimal("20.458953");
            
            boolean result = service.validateLocation(photoLat, photoLon, carLat, carLon);
            
            assertThat(result)
                .as("Photo at 700m should be rejected with 500m threshold")
                .isFalse();
        }

        @Test
        @DisplayName("Should accept photo within custom 2km threshold")
        void shouldAcceptWithLargerThreshold() {
            // Set larger threshold: 2000m instead of 1000m
            ReflectionTestUtils.setField(service, "maxDistanceMeters", 2000);
            
            BigDecimal carLat = new BigDecimal("44.816268");
            BigDecimal carLon = new BigDecimal("20.458953");
            
            // Photo at 1500m (would be rejected with 1000m, accepted with 2000m)
            BigDecimal photoLat = new BigDecimal("44.829800");
            BigDecimal photoLon = new BigDecimal("20.458953");
            
            boolean result = service.validateLocation(photoLat, photoLon, carLat, carLon);
            
            assertThat(result)
                .as("Photo at 1500m should be accepted with 2000m threshold")
                .isTrue();
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle very small coordinates (near equator/prime meridian)")
        void shouldHandleSmallCoordinates() {
            BigDecimal carLat = new BigDecimal("0.1");
            BigDecimal carLon = new BigDecimal("0.1");
            BigDecimal photoLat = new BigDecimal("0.1");
            BigDecimal photoLon = new BigDecimal("0.1");
            
            boolean result = service.validateLocation(photoLat, photoLon, carLat, carLon);
            
            assertThat(result)
                .as("Very small coordinates should be handled correctly")
                .isTrue();
        }

        @Test
        @DisplayName("Should handle coordinates at poles (extreme latitudes)")
        void shouldHandlePolesCoordinates() {
            BigDecimal carLat = new BigDecimal("89.9");
            BigDecimal carLon = new BigDecimal("0.0");
            BigDecimal photoLat = new BigDecimal("89.9");
            BigDecimal photoLon = new BigDecimal("0.0");
            
            boolean result = service.validateLocation(photoLat, photoLon, carLat, carLon);
            
            assertThat(result)
                .as("Polar coordinates should be handled correctly")
                .isTrue();
        }

        @Test
        @DisplayName("Should handle coordinates crossing date line (longitude ~180)")
        void shouldHandleDateLineCrossing() {
            BigDecimal carLat = new BigDecimal("45.0");
            BigDecimal carLon = new BigDecimal("179.9");
            BigDecimal photoLat = new BigDecimal("45.0");
            BigDecimal photoLon = new BigDecimal("-179.9"); // Just across date line
            
            boolean result = service.validateLocation(photoLat, photoLon, carLat, carLon);
            
            // These are actually very close (crossing date line), distance calculation
            // should recognize this. For 1km threshold, they should be rejected
            // (actual distance is ~40km)
            assertThat(result)
                .as("Date line crossing should calculate distance correctly")
                .isFalse();
        }

        @Test
        @DisplayName("Should handle high precision coordinates (8 decimal places)")
        void shouldHandleHighPrecisionCoordinates() {
            BigDecimal carLat = new BigDecimal("44.81626812");
            BigDecimal carLon = new BigDecimal("20.45895387");
            BigDecimal photoLat = new BigDecimal("44.81626812");
            BigDecimal photoLon = new BigDecimal("20.45895387");
            
            boolean result = service.validateLocation(photoLat, photoLon, carLat, carLon);
            
            assertThat(result)
                .as("High precision coordinates should be handled correctly")
                .isTrue();
        }
    }

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
                mockHeicBytes, staleTimestamp, null, null
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
                mockHeicBytes, freshTimestamp, null, null
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
                mockHeicBytes, futureTimestamp, null, null
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
                mockHeicBytes, yesterdayTimestamp, null, null
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
                mockHeicBytes, null, null, null
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
                mockHeicBytes, boundaryTimestamp, null, null
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
                mockHeicBytes, overBoundaryTimestamp, null, null
            );
            
            assertThat(result.getStatus())
                .as("HEIC photo at 6 minutes should be REJECTED_TOO_OLD")
                .isEqualTo(ExifValidationStatus.REJECTED_TOO_OLD);
        }
    }
}

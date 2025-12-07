package org.example.rentoza.booking.checkin;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.tiff.TiffField;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.constants.GpsTagConstants;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.taginfos.TagInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Service for validating EXIF metadata in check-in photos.
 * 
 * <h2>Fraud Prevention</h2>
 * <p>This service detects:
 * <ul>
 *   <li><b>Old photos:</b> Camera roll uploads from weeks/months ago</li>
 *   <li><b>Screenshots:</b> No EXIF data present</li>
 *   <li><b>Location spoofing:</b> GPS coordinates too far from car/pickup location</li>
 * </ul>
 * 
 * <h2>Basement Problem Solution</h2>
 * <p>Photos taken in parking garages (no signal) may be uploaded much later.
 * We compare EXIF timestamp against the <b>client's upload start time</b>,
 * not the server receipt time, to handle delayed uploads gracefully.
 * 
 * <h2>Regional Context: Serbia</h2>
 * <p>Timestamps are compared in {@code Europe/Belgrade} timezone.
 * GPS coordinates are validated against Serbia's bounding box.
 * 
 * <h2>Resilience (Phase 1 Critical Fix)</h2>
 * <p>Protected by circuit breaker pattern:
 * <ul>
 *   <li>If EXIF parsing fails repeatedly, circuit opens</li>
 *   <li>Fallback returns PENDING status (async re-validation)</li>
 *   <li>Prevents single photo failures from crashing entire check-in</li>
 * </ul>
 *
 * @see CheckInPhoto
 * @see ExifValidationStatus
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExifValidationService {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");
    
    // EXIF date format: "2025:11:29 14:30:00"
    private static final DateTimeFormatter EXIF_DATE_FORMAT = 
        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

    // Serbia's approximate bounding box for GPS plausibility check
    private static final double SERBIA_MIN_LAT = 42.23;
    private static final double SERBIA_MAX_LAT = 46.19;
    private static final double SERBIA_MIN_LON = 18.81;
    private static final double SERBIA_MAX_LON = 23.00;

    @Value("${app.checkin.exif.max-age-minutes:120}")
    private int maxAgeMinutes;
    
    @Value("${app.checkin.exif.client-timestamp-tolerance-seconds:300}")
    private int clientTimestampToleranceSeconds;

    @Value("${app.checkin.exif.require-gps:false}")
    private boolean requireGps;

    @Value("${app.checkin.exif.max-distance-meters:1000}")
    private int maxDistanceMeters;

    @Value("${app.checkin.exif.client-timestamp-max-age-minutes:5}")
    private int clientTimestampMaxAgeMinutes;

    /**
     * Validate EXIF metadata for a photo.
     * 
     * <p>Validation checks:
     * <ol>
     *   <li>EXIF data exists (rejects screenshots, downloaded images)</li>
     *   <li>Photo timestamp is within allowed age relative to client upload start</li>
     *   <li>GPS coordinates are plausible (if present)</li>
     *   <li>Photo location is within allowed radius of car (if car coordinates provided)</li>
     * </ol>
     * 
     * <h3>Basement Problem Handling</h3>
     * <p>If {@code clientUploadStarted} is provided, photo age is calculated as:
     * {@code clientUploadStarted - exifTimestamp}. This handles the scenario where
     * photos are taken in a garage (no signal), then uploaded when the user
     * gets connectivity. Without this, server receipt time would falsely reject
     * valid photos.
     * 
     * <h3>Location Validation (Fraud Prevention)</h3>
     * <p>If {@code carLatitude} and {@code carLongitude} are provided, validates
     * that the photo GPS is within {@code maxDistanceMeters} of the car location
     * (default 1km). Rejects photos taken at different locations to prevent fraud.
     * 
     * <h3>Circuit Breaker Protection</h3>
     * <p>If EXIF parsing fails consistently (circuit breaker opens), fallback
     * returns VALIDATION_PENDING status allowing check-in to proceed with
     * async re-validation later.
     *
     * @param photoBytes          Raw photo bytes
     * @param clientUploadStarted When the client started the upload (from frontend)
     *                            If null, falls back to server time (less accurate)
     * @param carLatitude         Expected car latitude (nullable - if null, location not validated)
     * @param carLongitude        Expected car longitude (nullable - if null, location not validated)
     * @return Validation result with extracted metadata
     */
    @CircuitBreaker(name = "exifValidation", fallbackMethod = "validateFallback")
    @Retry(name = "exifValidation")
    public ExifValidationResult validate(byte[] photoBytes, Instant clientUploadStarted, 
            BigDecimal carLatitude, BigDecimal carLongitude) {
        // Fall back to server time if client timestamp not provided
        Instant referenceTime = clientUploadStarted != null 
            ? clientUploadStarted 
            : Instant.now();
        
        try {
            ImageMetadata metadata = Imaging.getMetadata(new ByteArrayInputStream(photoBytes), null);
            
            if (metadata == null) {
                // No EXIF found - check if we have sidecar data to fall back on
                return handleMissingOrUnreadableExif(clientUploadStarted, 
                    "Fotografija nema EXIF podatke - koristite kameru, ne galeriju ili snimke ekrana");
            }
            
            TiffImageMetadata tiffMetadata = null;
            
            if (metadata instanceof JpegImageMetadata jpegMetadata) {
                tiffMetadata = jpegMetadata.getExif();
            } else if (metadata instanceof TiffImageMetadata) {
                tiffMetadata = (TiffImageMetadata) metadata;
            }
            
            if (tiffMetadata == null) {
                return handleMissingOrUnreadableExif(clientUploadStarted, 
                    "Nije moguće pročitati EXIF metapodatke");
            }
            
            // Extract timestamp
            LocalDateTime photoTime = extractPhotoTimestamp(tiffMetadata);
            
            if (photoTime == null) {
                return ExifValidationResult.rejected(
                    ExifValidationStatus.REJECTED_NO_EXIF,
                    "Fotografija nema vremenski žig - nije moguće proveriti da li je nova"
                );
            }
            
            // Check photo age against client upload start time (not server time)
            Instant photoInstant = photoTime.atZone(SERBIA_ZONE).toInstant();
            Duration photoAge = Duration.between(photoInstant, referenceTime);
            
            // Sanity check: photo timestamp shouldn't be in the future
            // Allow small tolerance for clock drift
            if (photoAge.isNegative() && photoAge.abs().getSeconds() > clientTimestampToleranceSeconds) {
                return ExifValidationResult.rejected(
                    ExifValidationStatus.REJECTED_FUTURE_TIMESTAMP,
                    "Vremenski žig fotografije je u budućnosti. Proverite podešavanja sata na uređaju."
                );
            }
            
            // Check if photo is too old
            Duration maxAge = Duration.ofMinutes(maxAgeMinutes);
            if (photoAge.compareTo(maxAge) > 0) {
                long ageMinutes = photoAge.toMinutes();
                return ExifValidationResult.rejected(
                    ExifValidationStatus.REJECTED_TOO_OLD,
                    String.format("Fotografija je stara %d minuta. Morate napraviti novu fotografiju (max %d minuta).",
                        ageMinutes, maxAgeMinutes)
                );
            }
            
            // Extract GPS if available
            BigDecimal latitude = null;
            BigDecimal longitude = null;
            TiffImageMetadata.GPSInfo gpsInfo = tiffMetadata.getGPS();
            
            if (gpsInfo != null) {
                try {
                    latitude = BigDecimal.valueOf(gpsInfo.getLatitudeAsDegreesNorth());
                    longitude = BigDecimal.valueOf(gpsInfo.getLongitudeAsDegreesEast());
                    
                    // Plausibility check - is it within Serbia?
                    if (!isWithinSerbia(latitude.doubleValue(), longitude.doubleValue())) {
                        log.warn("[EXIF] GPS coordinates outside Serbia: {}, {}", latitude, longitude);
                        // Don't reject, just flag - could be border areas or GPS drift
                    }
                    
                    // ========== LOCATION VALIDATION (Fraud Prevention) ==========
                    // If car coordinates provided, validate photo location is within allowed radius
                    if (carLatitude != null && carLongitude != null) {
                        boolean locationValid = validateLocation(latitude, longitude, carLatitude, carLongitude);
                        
                        if (!locationValid) {
                            double distance = haversineDistance(
                                latitude.doubleValue(), longitude.doubleValue(),
                                carLatitude.doubleValue(), carLongitude.doubleValue()
                            );
                            int distanceMeters = (int) Math.round(distance);
                            
                            log.warn("[EXIF] Location mismatch: photo taken {}m from car (max {}m). Photo GPS: {},{} Car GPS: {},{}",
                                distanceMeters, maxDistanceMeters, latitude, longitude, carLatitude, carLongitude);
                            
                            return ExifValidationResult.rejected(
                                ExifValidationStatus.REJECTED_LOCATION_MISMATCH,
                                String.format("Fotografija je napravljena na drugom mestu (%dm od automobila)", distanceMeters)
                            );
                        }
                        
                        log.info("[EXIF] Location validation passed: photo within {}m of car", maxDistanceMeters);
                    }
                } catch (Exception e) {
                    log.debug("[EXIF] Could not parse GPS info", e);
                }
            } else if (requireGps) {
                return ExifValidationResult.rejected(
                    ExifValidationStatus.REJECTED_NO_GPS,
                    "Fotografija mora sadržati GPS lokaciju. Omogućite lokaciju u podešavanjima kamere."
                );
            }
            
            // Extract device info
            String deviceMake = extractStringTag(tiffMetadata, TiffTagConstants.TIFF_TAG_MAKE);
            String deviceModel = extractStringTag(tiffMetadata, TiffTagConstants.TIFF_TAG_MODEL);
            
            // All checks passed
            ExifValidationStatus status = (latitude != null) 
                ? ExifValidationStatus.VALID 
                : ExifValidationStatus.VALID_NO_GPS;
            
            log.info("[EXIF] Validation passed: age={}min, hasGPS={}, device={} {}", 
                photoAge.toMinutes(), latitude != null, deviceMake, deviceModel);
            
            return ExifValidationResult.builder()
                .status(status)
                .message("EXIF validacija uspešna")
                .photoTimestamp(photoInstant)
                .latitude(latitude)
                .longitude(longitude)
                .deviceMake(deviceMake)
                .deviceModel(deviceModel)
                .photoAgeMinutes((int) photoAge.toMinutes())
                .clientTimestampUsed(clientUploadStarted != null)
                .build();
            
        } catch (IOException e) {
            // HEIC Trap: Apache Commons Imaging throws IOException for HEIC/modern formats
            // "Not a Valid TIFF File" - This is NOT a real error, just unsupported format
            log.warn("[EXIF] Cannot read image format (likely HEIC/modern format): {}", e.getMessage());
            return handleUnsupportedImageFormat(clientUploadStarted, e.getMessage());
        } catch (Exception e) {
            log.error("[EXIF] Validation failed with unexpected error", e);
            return handleMissingOrUnreadableExif(clientUploadStarted, 
                "Greška pri čitanju metapodataka slike: " + e.getMessage());
        }
    }

    /**
     * Validate photo location against expected car location.
     * 
     * @param photoLat    Photo GPS latitude
     * @param photoLon    Photo GPS longitude
     * @param carLat      Expected car latitude
     * @param carLon      Expected car longitude
     * @return true if within allowed radius
     */
    public boolean validateLocation(
            BigDecimal photoLat, BigDecimal photoLon,
            BigDecimal carLat, BigDecimal carLon) {
        
        if (photoLat == null || photoLon == null || carLat == null || carLon == null) {
            return true; // Can't validate without coordinates
        }
        
        double distance = haversineDistance(
            photoLat.doubleValue(), photoLon.doubleValue(),
            carLat.doubleValue(), carLon.doubleValue()
        );
        
        return distance <= maxDistanceMeters;
    }

    // ========== HELPER METHODS ==========

    private LocalDateTime extractPhotoTimestamp(TiffImageMetadata metadata) {
        // Try DateTimeOriginal first (when photo was taken)
        String dateTimeOriginal = extractExifDateTag(metadata, ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL);
        if (dateTimeOriginal != null) {
            LocalDateTime parsed = parseExifDate(dateTimeOriginal);
            if (parsed != null) {
                return parsed;
            }
        }
        
        // Fall back to DateTimeDigitized
        String dateTimeDigitized = extractExifDateTag(metadata, ExifTagConstants.EXIF_TAG_DATE_TIME_DIGITIZED);
        if (dateTimeDigitized != null) {
            LocalDateTime parsed = parseExifDate(dateTimeDigitized);
            if (parsed != null) {
                return parsed;
            }
        }
        
        // Last resort: TIFF DateTime
        String dateTime = extractStringTag(metadata, TiffTagConstants.TIFF_TAG_DATE_TIME);
        if (dateTime != null) {
            LocalDateTime parsed = parseExifDate(dateTime);
            if (parsed != null) {
                return parsed;
            }
        }
        
        log.warn("[EXIF] No valid timestamp found in any tag");
        return null;
    }

    private String extractExifDateTag(TiffImageMetadata metadata, TagInfo tagInfo) {
        try {
            TiffField field = metadata.findField(tagInfo);
            if (field != null) {
                return field.getStringValue();
            }
        } catch (Exception e) {
            log.debug("[EXIF] Could not extract tag {}: {}", tagInfo.name, e.getMessage());
        }
        return null;
    }

    private String extractStringTag(TiffImageMetadata metadata, TagInfo tagInfo) {
        try {
            TiffField field = metadata.findField(tagInfo);
            if (field != null) {
                return field.getStringValue();
            }
        } catch (Exception e) {
            log.debug("[EXIF] Could not extract tag {}: {}", tagInfo.name, e.getMessage());
        }
        return null;
    }

    private LocalDateTime parseExifDate(String dateString) {
        if (dateString == null || dateString.isBlank()) {
            return null;
        }
        
        try {
            // Remove any null characters (common in some EXIF implementations)
            String cleaned = dateString.trim().replace("\u0000", "");
            return LocalDateTime.parse(cleaned, EXIF_DATE_FORMAT);
        } catch (DateTimeParseException e) {
            log.debug("[EXIF] Failed to parse date '{}': {}", dateString, e.getMessage());
            return null;
        }
    }

    private boolean isWithinSerbia(double lat, double lon) {
        return lat >= SERBIA_MIN_LAT && lat <= SERBIA_MAX_LAT &&
               lon >= SERBIA_MIN_LON && lon <= SERBIA_MAX_LON;
    }

    /**
     * Handle HEIC or other unsupported image formats that Apache Commons Imaging cannot read.
     * Falls back to client sidecar timestamp if available.
     * 
     * <p><b>SECURITY FIX:</b> Validates client timestamp age to prevent photo validation bypass.
     * Without this check, users could upload old HEIC photos by manipulating client timestamp.
     * 
     * @param clientUploadStarted Client-provided timestamp from sidecar data
     * @param errorDetail         Technical error details for logging
     * @return VALID_WITH_WARNINGS if sidecar available and fresh, REJECTED otherwise
     */
    private ExifValidationResult handleUnsupportedImageFormat(Instant clientUploadStarted, String errorDetail) {
        if (clientUploadStarted != null) {
            // ========== CRITICAL SECURITY FIX: Validate client timestamp freshness ==========
            // Client timestamp should be very recent (within last 5 minutes by default)
            // If upload started long ago, either network issues or fraud attempt
            Instant now = Instant.now();
            Duration timeSinceUploadStarted = Duration.between(clientUploadStarted, now);
            
            // Reject if client timestamp is too old
            if (timeSinceUploadStarted.toMinutes() > clientTimestampMaxAgeMinutes) {
                log.warn("[EXIF] HEIC fallback REJECTED - client timestamp too old: {} minutes ago. "
                        + "This prevents uploading old photos in HEIC format to bypass age validation.", 
                        timeSinceUploadStarted.toMinutes());
                return ExifValidationResult.rejected(
                    ExifValidationStatus.REJECTED_TOO_OLD,
                    String.format("Vreme otpremanja je previše staro (%d minuta). Pokušajte ponovo sa novom fotografijom.",
                        timeSinceUploadStarted.toMinutes())
                );
            }
            
            // Reject if client timestamp is in the future (clock manipulation)
            if (timeSinceUploadStarted.isNegative() && 
                timeSinceUploadStarted.abs().getSeconds() > clientTimestampToleranceSeconds) {
                log.warn("[EXIF] HEIC fallback REJECTED - client timestamp is in future: {} seconds", 
                        timeSinceUploadStarted.abs().getSeconds());
                return ExifValidationResult.rejected(
                    ExifValidationStatus.REJECTED_FUTURE_TIMESTAMP,
                    "Vreme otpremanja je u budućnosti. Proverite podešavanja sata na uređaju."
                );
            }
            
            log.info("[EXIF] HEIC/unsupported format - APPROVED via sidecar with timestamp validation. "
                    + "Client timestamp age: {} seconds", timeSinceUploadStarted.getSeconds());
            return ExifValidationResult.builder()
                .status(ExifValidationStatus.VALID_WITH_WARNINGS)
                .message("Format slike (HEIC/moderni format) - prihvaćeno bez EXIF validacije")
                .photoTimestamp(clientUploadStarted)
                .clientTimestampUsed(true)
                .photoAgeMinutes((int) timeSinceUploadStarted.toMinutes())
                .build();
        }
        
        log.warn("[EXIF] Unsupported format AND no sidecar - REJECTED. Error: {}", errorDetail);
        return ExifValidationResult.rejected(
            ExifValidationStatus.REJECTED_NO_EXIF,
            "Format slike nije podržan i nije moguće proveriti autentičnost fotografije"
        );
    }

    /**
     * Handle missing or unreadable EXIF metadata with sidecar fallback.
     * 
     * <p><b>SECURITY FIX:</b> Validates client timestamp age to prevent photo validation bypass.
     * Without this check, users could upload old photos without EXIF by manipulating client timestamp.
     * 
     * @param clientUploadStarted Client-provided timestamp from sidecar data
     * @param userMessage         User-facing error message
     * @return VALID_WITH_WARNINGS if sidecar available and fresh, REJECTED otherwise
     */
    private ExifValidationResult handleMissingOrUnreadableExif(Instant clientUploadStarted, String userMessage) {
        if (clientUploadStarted != null) {
            // ========== CRITICAL SECURITY FIX: Validate client timestamp freshness ==========
            // Same validation as HEIC fallback - prevent uploading old photos without EXIF
            Instant now = Instant.now();
            Duration timeSinceUploadStarted = Duration.between(clientUploadStarted, now);
            
            // Reject if client timestamp is too old
            if (timeSinceUploadStarted.toMinutes() > clientTimestampMaxAgeMinutes) {
                log.warn("[EXIF] Missing EXIF fallback REJECTED - client timestamp too old: {} minutes ago. "
                        + "This prevents uploading old photos without EXIF to bypass age validation.", 
                        timeSinceUploadStarted.toMinutes());
                return ExifValidationResult.rejected(
                    ExifValidationStatus.REJECTED_TOO_OLD,
                    String.format("Vreme otpremanja je previše staro (%d minuta). Pokušajte ponovo sa novom fotografijom.",
                        timeSinceUploadStarted.toMinutes())
                );
            }
            
            // Reject if client timestamp is in the future (clock manipulation)
            if (timeSinceUploadStarted.isNegative() && 
                timeSinceUploadStarted.abs().getSeconds() > clientTimestampToleranceSeconds) {
                log.warn("[EXIF] Missing EXIF fallback REJECTED - client timestamp is in future: {} seconds", 
                        timeSinceUploadStarted.abs().getSeconds());
                return ExifValidationResult.rejected(
                    ExifValidationStatus.REJECTED_FUTURE_TIMESTAMP,
                    "Vreme otpremanja je u budućnosti. Proverite podešavanja sata na uređaju."
                );
            }
            
            log.info("[EXIF] Missing EXIF - APPROVED via sidecar with timestamp validation. "
                    + "Client timestamp age: {} seconds", timeSinceUploadStarted.getSeconds());
            return ExifValidationResult.builder()
                .status(ExifValidationStatus.VALID_WITH_WARNINGS)
                .message("EXIF podaci nedostaju - prihvaćeno na osnovu vremena prijave klijenta")
                .photoTimestamp(clientUploadStarted)
                .clientTimestampUsed(true)
                .photoAgeMinutes((int) timeSinceUploadStarted.toMinutes())
                .build();
        }
        
        log.warn("[EXIF] Missing EXIF AND no sidecar - REJECTED: {}", userMessage);
        return ExifValidationResult.rejected(ExifValidationStatus.REJECTED_NO_EXIF, userMessage);
    }

    /**
     * Calculate distance between two GPS coordinates using Haversine formula.
     * 
     * @return Distance in meters
     */
    private double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        final double EARTH_RADIUS_METERS = 6371000;
        
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        
        return EARTH_RADIUS_METERS * c;
    }

    /**
     * Result of EXIF validation.
     */
    @Data
    @Builder
    public static class ExifValidationResult {
        private ExifValidationStatus status;
        private String message;
        private Instant photoTimestamp;
        private BigDecimal latitude;
        private BigDecimal longitude;
        private String deviceMake;
        private String deviceModel;
        
        /** Age of photo in minutes (relative to client upload start or server time) */
        private int photoAgeMinutes;
        
        /** Whether client-provided timestamp was used for age calculation */
        private boolean clientTimestampUsed;

        public static ExifValidationResult rejected(ExifValidationStatus status, String message) {
            return ExifValidationResult.builder()
                .status(status)
                .message(message)
                .build();
        }

        public boolean isAccepted() {
            return status == ExifValidationStatus.VALID || 
                   status == ExifValidationStatus.VALID_NO_GPS ||
                   status == ExifValidationStatus.VALID_WITH_WARNINGS ||
                   status == ExifValidationStatus.VALIDATION_PENDING;
        }
    }

    // ========== CIRCUIT BREAKER FALLBACK ==========

    /**
     * Fallback method when circuit breaker is open or validation repeatedly fails.
     * 
     * <p>Returns VALIDATION_PENDING status, allowing the photo upload to succeed
     * with async re-validation later. This prevents a failing EXIF library from
     * blocking all check-ins.
     * 
     * @param photoBytes          Original photo bytes (unused in fallback)
     * @param clientUploadStarted Client timestamp (preserved for later validation)
     * @param carLatitude         Car latitude (unused in fallback)
     * @param carLongitude        Car longitude (unused in fallback)
     * @param throwable           Exception that triggered the fallback
     * @return VALIDATION_PENDING result
     */
    @SuppressWarnings("unused")
    private ExifValidationResult validateFallback(byte[] photoBytes, Instant clientUploadStarted, 
            BigDecimal carLatitude, BigDecimal carLongitude, Throwable throwable) {
        log.warn("[EXIF] Circuit breaker fallback triggered: {} - allowing upload with PENDING validation", 
                throwable.getMessage());
        
        return ExifValidationResult.builder()
                .status(ExifValidationStatus.VALIDATION_PENDING)
                .message("EXIF validacija odložena - biće izvršena naknadno")
                .photoTimestamp(clientUploadStarted) // Use client timestamp as best-effort
                .clientTimestampUsed(clientUploadStarted != null)
                .build();
    }
}

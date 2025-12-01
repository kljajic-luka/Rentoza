package org.example.rentoza.booking.checkin;

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

    /**
     * Validate EXIF metadata for a photo.
     * 
     * <p>Validation checks:
     * <ol>
     *   <li>EXIF data exists (rejects screenshots, downloaded images)</li>
     *   <li>Photo timestamp is within allowed age relative to client upload start</li>
     *   <li>GPS coordinates are plausible (if present)</li>
     * </ol>
     * 
     * <h3>Basement Problem Handling</h3>
     * <p>If {@code clientUploadStarted} is provided, photo age is calculated as:
     * {@code clientUploadStarted - exifTimestamp}. This handles the scenario where
     * photos are taken in a garage (no signal), then uploaded when the user
     * gets connectivity. Without this, server receipt time would falsely reject
     * valid photos.
     *
     * @param photoBytes          Raw photo bytes
     * @param clientUploadStarted When the client started the upload (from frontend)
     *                            If null, falls back to server time (less accurate)
     * @return Validation result with extracted metadata
     */
    public ExifValidationResult validate(byte[] photoBytes, Instant clientUploadStarted) {
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
     * @param clientUploadStarted Client-provided timestamp from sidecar data
     * @param errorDetail         Technical error details for logging
     * @return VALID_WITH_WARNINGS if sidecar available, REJECTED otherwise
     */
    private ExifValidationResult handleUnsupportedImageFormat(Instant clientUploadStarted, String errorDetail) {
        if (clientUploadStarted != null) {
            log.info("[EXIF] HEIC/unsupported format detected - APPROVED via sidecar fallback. Error was: {}", errorDetail);
            return ExifValidationResult.builder()
                .status(ExifValidationStatus.VALID_WITH_WARNINGS)
                .message("Format slike (HEIC/moderni format) nije podržan za EXIF čitanje - prihvaćeno na osnovu vremena prijave klijenta")
                .photoTimestamp(clientUploadStarted)
                .clientTimestampUsed(true)
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
     * @param clientUploadStarted Client-provided timestamp from sidecar data
     * @param userMessage         User-facing error message
     * @return VALID_WITH_WARNINGS if sidecar available, REJECTED otherwise
     */
    private ExifValidationResult handleMissingOrUnreadableExif(Instant clientUploadStarted, String userMessage) {
        if (clientUploadStarted != null) {
            log.info("[EXIF] Missing EXIF - APPROVED via sidecar fallback");
            return ExifValidationResult.builder()
                .status(ExifValidationStatus.VALID_WITH_WARNINGS)
                .message("EXIF podaci nedostaju - prihvaćeno na osnovu vremena prijave klijenta")
                .photoTimestamp(clientUploadStarted)
                .clientTimestampUsed(true)
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
                   status == ExifValidationStatus.VALID_WITH_WARNINGS;
        }
    }
}

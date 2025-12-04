package org.example.rentoza.booking.checkin.photo;

import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.checkin.CheckInPhoto;
import org.example.rentoza.booking.checkin.CheckInPhotoRepository;
import org.example.rentoza.booking.checkin.ExifValidationService;
import org.example.rentoza.booking.checkin.ExifValidationStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Optional;

/**
 * Fallback async photo validation using Spring @Async.
 * 
 * <p>Used when RabbitMQ is not available (development mode).
 * Provides same async validation behavior using local thread pool.
 * 
 * @see PhotoValidationWorker for RabbitMQ-based implementation
 */
@Component
@ConditionalOnProperty(name = "app.rabbitmq.enabled", havingValue = "false", matchIfMissing = true)
@Slf4j
public class PhotoValidationFallback {

    private final CheckInPhotoRepository photoRepository;
    private final ExifValidationService exifValidationService;

    @Value("${app.checkin.photo.upload-dir:uploads/checkin}")
    private String uploadDir;

    public PhotoValidationFallback(
            CheckInPhotoRepository photoRepository,
            ExifValidationService exifValidationService) {
        this.photoRepository = photoRepository;
        this.exifValidationService = exifValidationService;
        
        log.info("[PhotoValidation] Using fallback async validation (RabbitMQ disabled)");
    }

    /**
     * Queue a photo for async validation using local thread pool.
     */
    @Async("photoProcessingExecutor")
    @Transactional
    public void queueForValidation(CheckInPhoto photo, Double carLatitude, Double carLongitude,
                                   Instant clientUploadStartedAt) {
        log.debug("[PhotoValidation-Fallback] Processing photo {} asynchronously", photo.getId());

        try {
            // Re-fetch photo to ensure fresh data in new transaction
            Optional<CheckInPhoto> photoOpt = photoRepository.findById(photo.getId());

            if (photoOpt.isEmpty()) {
                log.warn("[PhotoValidation-Fallback] Photo not found: {}", photo.getId());
                return;
            }

            CheckInPhoto freshPhoto = photoOpt.get();

            // Skip if already validated
            if (freshPhoto.getExifValidationStatus() != ExifValidationStatus.VALIDATION_PENDING) {
                log.debug("[PhotoValidation-Fallback] Photo {} already validated", photo.getId());
                return;
            }

            // Perform validation
            // Read photo file from storage
            byte[] photoBytes = readPhotoFromStorage(freshPhoto);
            
            // Validate EXIF data
            ExifValidationService.ExifValidationResult result = exifValidationService.validate(
                    photoBytes,
                    clientUploadStartedAt
            );
            
            // Validate location if car coordinates provided
            if (carLatitude != null && carLongitude != null && 
                result.getLatitude() != null && result.getLongitude() != null) {
                boolean locationValid = exifValidationService.validateLocation(
                        result.getLatitude(),
                        result.getLongitude(),
                        BigDecimal.valueOf(carLatitude),
                        BigDecimal.valueOf(carLongitude)
                );
                if (!locationValid) {
                    // Update result to reflect location validation failure
                    result = ExifValidationService.ExifValidationResult.builder()
                            .status(ExifValidationStatus.VALID_WITH_WARNINGS)
                            .message("Lokacija fotografije je daleko od vozila")
                            .photoTimestamp(result.getPhotoTimestamp())
                            .latitude(result.getLatitude())
                            .longitude(result.getLongitude())
                            .deviceMake(result.getDeviceMake())
                            .deviceModel(result.getDeviceModel())
                            .photoAgeMinutes(result.getPhotoAgeMinutes())
                            .clientTimestampUsed(result.isClientTimestampUsed())
                            .build();
                }
            }

            // Update photo with results
            freshPhoto.setExifValidationStatus(result.getStatus());
            freshPhoto.setExifValidationMessage(result.getMessage());
            freshPhoto.setExifTimestamp(result.getPhotoTimestamp());
            freshPhoto.setExifLatitude(result.getLatitude());
            freshPhoto.setExifLongitude(result.getLongitude());
            freshPhoto.setExifDeviceModel(result.getDeviceModel());

            photoRepository.save(freshPhoto);

            log.info("[PhotoValidation-Fallback] Completed validation for photo {}: {}",
                    photo.getId(), result.getStatus());

        } catch (Exception e) {
            log.error("[PhotoValidation-Fallback] Error validating photo {}: {}",
                    photo.getId(), e.getMessage(), e);
            // Mark as pending for manual review
            markAsPending(photo.getId());
        }
    }

    private byte[] readPhotoFromStorage(CheckInPhoto photo) throws IOException {
        // Extract session ID and filename from storage key
        // Format: checkin/{sessionId}/{filename} or checkout/{sessionId}/{filename}
        String storageKey = photo.getStorageKey();
        String[] parts = storageKey.split("/");
        
        if (parts.length < 3) {
            throw new IOException("Invalid storage key format: " + storageKey);
        }
        
        // Determine base directory (checkin or checkout)
        String baseDir = parts[0].equals("checkout") 
            ? uploadDir.replace("checkin", "checkout")
            : uploadDir;
        
        // Build file path: {baseDir}/{sessionId}/{filename}
        String sessionId = parts[1];
        String filename = parts[2];
        Path filePath = Paths.get(baseDir, sessionId, filename);
        
        if (!Files.exists(filePath)) {
            throw new IOException("Photo file not found: " + filePath);
        }
        
        return Files.readAllBytes(filePath);
    }

    private void markAsPending(Long photoId) {
        try {
            Optional<CheckInPhoto> photoOpt = photoRepository.findById(photoId);
            if (photoOpt.isPresent()) {
                CheckInPhoto photo = photoOpt.get();
                photo.setExifValidationStatus(ExifValidationStatus.VALIDATION_PENDING);
                photo.setExifValidationMessage("Validation error - pending manual review");
                photoRepository.save(photo);
            }
        } catch (Exception e) {
            log.error("[PhotoValidation-Fallback] Failed to mark photo {} as pending: {}",
                    photoId, e.getMessage());
        }
    }
}

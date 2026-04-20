package org.example.rentoza.booking.checkin.photo;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.checkin.CheckInPhoto;
import org.example.rentoza.booking.checkin.CheckInPhotoRepository;
import org.example.rentoza.booking.checkin.ExifValidationService;
import org.example.rentoza.booking.checkin.ExifValidationService.ExifValidationResult;
import org.example.rentoza.booking.checkin.ExifValidationStatus;
import org.example.rentoza.booking.checkin.cqrs.CheckInDomainEvent;
import org.example.rentoza.config.RabbitMQConfig;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Async photo validation worker using RabbitMQ.
 * 
 * <h2>Phase 2 Architecture Improvement</h2>
 * <p>Offloads CPU-intensive EXIF validation to background workers,
 * preventing blocking of HTTP request threads.
 * 
 * <h2>Processing Flow</h2>
 * <pre>
 * Photo Upload (HTTP) ───────────────┐
 *     │                              │
 *     │ saves photo                  │
 *     │ status=PENDING               │
 *     ▼                              │
 * Send to Queue ◄────────────────────┘
 *     │
 *     │ async
 *     ▼
 * PhotoValidationWorker
 *     │
 *     ├──► Load photo metadata
 *     │
 *     ├──► Validate EXIF data
 *     │    - Timestamp freshness
 *     │    - GPS proximity
 *     │    - Device fingerprint
 *     │
 *     ├──► Update photo status
 *     │
 *     └──► Publish event for view sync
 * </pre>
 * 
 * <h2>Error Handling</h2>
 * <p>Failed validations:
 * <ul>
 *   <li>Retry 3 times with exponential backoff</li>
 *   <li>On exhaustion, send to DLQ</li>
 *   <li>Mark photo as VALIDATION_PENDING for manual review</li>
 * </ul>
 * 
 * @see PhotoValidationMessage for message payload
 * @see ExifValidationService for validation logic
 */
@Component
@ConditionalOnProperty(name = "app.rabbitmq.enabled", havingValue = "true")
@Slf4j
public class PhotoValidationWorker {

    @Value("${upload.dir:uploads}")
    private String uploadDir;

    private final CheckInPhotoRepository photoRepository;
    private final ExifValidationService exifValidationService;
    private final RabbitTemplate rabbitTemplate;
    private final ApplicationEventPublisher eventPublisher;

    // Metrics
    private final Counter validationSuccessCounter;
    private final Counter validationFailureCounter;
    private final Counter validationErrorCounter;
    private final Timer validationTimer;

    public PhotoValidationWorker(
            CheckInPhotoRepository photoRepository,
            ExifValidationService exifValidationService,
            RabbitTemplate rabbitTemplate,
            ApplicationEventPublisher eventPublisher,
            MeterRegistry meterRegistry) {
        this.photoRepository = photoRepository;
        this.exifValidationService = exifValidationService;
        this.rabbitTemplate = rabbitTemplate;
        this.eventPublisher = eventPublisher;

        this.validationSuccessCounter = Counter.builder("photo.validation.success")
                .description("Successful photo validations")
                .register(meterRegistry);

        this.validationFailureCounter = Counter.builder("photo.validation.failure")
                .description("Failed photo validations (fraud detected)")
                .register(meterRegistry);

        this.validationErrorCounter = Counter.builder("photo.validation.error")
                .description("Photo validation errors (processing failed)")
                .register(meterRegistry);

        this.validationTimer = Timer.builder("photo.validation.duration")
                .description("Photo validation duration")
                .register(meterRegistry);
    }

    /**
     * Process photo validation message from queue.
     * 
     * <p>Uses dedicated listener factory for higher concurrency.
     */
    @RabbitListener(
            queues = RabbitMQConfig.QUEUE_PHOTO_VALIDATION,
            containerFactory = "photoValidationListenerFactory"
    )
    @Transactional
    public void processValidation(PhotoValidationMessage message) {
        log.info("[PhotoWorker] Processing validation for photo {} (booking {})",
                message.getPhotoId(), message.getBookingId());

        validationTimer.record(() -> {
            try {
                // Load photo entity
                Optional<CheckInPhoto> photoOpt = photoRepository.findById(message.getPhotoId());

                if (photoOpt.isEmpty()) {
                    log.warn("[PhotoWorker] Photo not found: {}", message.getPhotoId());
                    return;
                }

                CheckInPhoto photo = photoOpt.get();

                // Skip if already validated
                if (photo.getExifValidationStatus() != ExifValidationStatus.VALIDATION_PENDING) {
                    log.debug("[PhotoWorker] Photo {} already validated: {}",
                            message.getPhotoId(), photo.getExifValidationStatus());
                    return;
                }

                // Load photo bytes from storage
                byte[] photoBytes = loadPhotoBytes(photo.getStorageKey());

                // Phase 2: Car location validation removed from EXIF validation.
                // Location is now derived from photos AFTER upload (at check-in submission).
                // This fixes the chicken-and-egg problem: we can't validate GPS against car location
                // when car location isn't known until photos are uploaded.
                ExifValidationResult result = exifValidationService.validate(
                        photoBytes,
                        message.getClientUploadStartedAt()
                );

                // Update photo with results
                photo.setExifValidationStatus(result.getStatus());
                photo.setExifValidationMessage(result.getMessage());
                photo.setExifTimestamp(result.getPhotoTimestamp());
                photo.setExifLatitude(result.getLatitude());
                photo.setExifLongitude(result.getLongitude());
                photo.setExifDeviceModel(result.getDeviceModel());

                photoRepository.save(photo);

                // Update metrics
                if (result.getStatus() == ExifValidationStatus.VALID || 
                    result.getStatus() == ExifValidationStatus.VALID_NO_GPS ||
                    result.getStatus() == ExifValidationStatus.VALID_WITH_WARNINGS) {
                    validationSuccessCounter.increment();
                } else if (result.getStatus().isRejected()) {
                    validationFailureCounter.increment();
                }

                // Publish event for view sync
                eventPublisher.publishEvent(new CheckInDomainEvent.PhotoValidationCompleted(
                        message.getBookingId(),
                        UUID.fromString(photo.getBooking().getCheckInSessionId()),
                        message.getPhotoId(),
                        result.getStatus().name(),
                        result.getMessage(),
                        Instant.now()
                ));

                log.info("[PhotoWorker] Validation complete for photo {}: {}",
                        message.getPhotoId(), result.getStatus());

            } catch (IOException e) {
                validationErrorCounter.increment();
                log.error("[PhotoWorker] IO error loading photo {}: {}",
                        message.getPhotoId(), e.getMessage(), e);
                // Wrap in RuntimeException for RabbitMQ listener
                throw new RuntimeException("Failed to load photo from storage", e);
            } catch (Exception e) {
                validationErrorCounter.increment();
                log.error("[PhotoWorker] Validation error for photo {}: {}",
                        message.getPhotoId(), e.getMessage(), e);

                // Retry logic handled by listener factory
                throw e;  // Re-throw to trigger retry/DLQ
            }
        });
    }

    /**
     * Queue a photo for async validation.
     * 
     * <p>Called by PhotoService after initial upload.
     */
    public void queueForValidation(CheckInPhoto photo, Double carLatitude, Double carLongitude,
                                   Instant clientUploadStartedAt) {
        PhotoValidationMessage message = PhotoValidationMessage.builder()
                .photoId(photo.getId())
                .bookingId(photo.getBooking().getId())
                .storageKey(photo.getStorageKey())
                .photoType(photo.getPhotoType().name())
                .requestedAt(Instant.now())
                .retryCount(0)
                .carLatitude(carLatitude)
                .carLongitude(carLongitude)
                .clientUploadStartedAt(clientUploadStartedAt)
                .build();

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE_DIRECT,
                RabbitMQConfig.ROUTING_PHOTO_VALIDATE,
                message
        );

        log.debug("[PhotoWorker] Queued photo {} for validation", photo.getId());
    }

    // ========== HELPER METHODS ==========

    /**
     * Load photo bytes from local file storage.
     * 
     * @param storageKey The storage key (e.g., "/uploads/uuid_filename.jpg")
     * @return Photo bytes
     * @throws IOException if file cannot be read
     */
    private byte[] loadPhotoBytes(String storageKey) throws IOException {
        // storageKey is like "/uploads/uuid_filename.jpg" - extract filename
        String filename = storageKey;
        if (storageKey.startsWith("/uploads/")) {
            filename = storageKey.substring("/uploads/".length());
        } else if (storageKey.startsWith("uploads/")) {
            filename = storageKey.substring("uploads/".length());
        }
        
        Path path = Paths.get(uploadDir, filename);
        
        if (!Files.exists(path)) {
            log.error("[PhotoWorker] Photo file not found: {}", path);
            throw new IOException("Photo file not found: " + storageKey);
        }
        
        return Files.readAllBytes(path);
    }
}

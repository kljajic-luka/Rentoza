package org.example.rentoza.booking.checkin;

import lombok.RequiredArgsConstructor;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.ExifValidationService.ExifValidationResult;
import org.example.rentoza.booking.checkin.cqrs.CheckInDomainEvent;
import org.example.rentoza.booking.checkin.dto.CheckInPhotoDTO;
import org.example.rentoza.booking.checkin.dto.PhotoRejectionInfo;
import org.example.rentoza.booking.checkin.dto.PhotoUploadResponse;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.security.LockboxEncryptionService;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.example.rentoza.storage.SupabaseStorageService;
import org.example.rentoza.booking.photo.PiiPhotoStorageService;
import org.example.rentoza.booking.photo.PhotoUrlService;
import org.example.rentoza.util.ExifStrippingService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.List;

/**
 * Service for handling check-in photo uploads and storage.
 * 
 * <p>Implements zero-storage policy for rejected photos:
 * <ul>
 *   <li>Accepted photos: stored to DB and filesystem</li>
 *   <li>Rejected photos: NOT stored, only event logged for audit</li>
 * </ul>
 * 
 * @see PhotoRejectionService
 * @see PhotoUploadResponse
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckInPhotoService {

    private enum UploadReconciliationOutcome {
        FINALIZED,
        TERMINAL_FAILURE,
        RETRYABLE_PENDING,
        NO_ACTION
    }

    private enum FinalizeFailureDisposition {
        TERMINAL,
        RETRYABLE
    }

    private static final class PhotoUploadFinalizeException extends IllegalStateException {
        private final FinalizeFailureDisposition disposition;

        private PhotoUploadFinalizeException(String message,
                                            FinalizeFailureDisposition disposition,
                                            Throwable cause) {
            super(message, cause);
            this.disposition = disposition;
        }

        static PhotoUploadFinalizeException terminal(String message, Throwable cause) {
            return new PhotoUploadFinalizeException(message, FinalizeFailureDisposition.TERMINAL, cause);
        }

        static PhotoUploadFinalizeException retryable(String message, Throwable cause) {
            return new PhotoUploadFinalizeException(message, FinalizeFailureDisposition.RETRYABLE, cause);
        }

        boolean isTerminal() {
            return disposition == FinalizeFailureDisposition.TERMINAL;
        }
    }

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");
    
    /**
     * P0 FIX: Hard geofence limit for lockbox reveal (meters).
     * Turo standard requires 50m. No dynamic adjustment - this is a security boundary.
     */
    private static final int LOCKBOX_REVEAL_MAX_DISTANCE_METERS = 50;
    
    /**
     * P1 FIX: Maximum photos allowed per booking (host + guest combined).
     * Prevents DoS via mass photo upload. 8 required + up to 12 optional/damage = 20 max.
     */
    private static final int MAX_PHOTOS_PER_BOOKING = 20;

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final CheckInPhotoRepository photoRepository;
    private final CheckInEventService eventService;
    private final ExifValidationService exifValidationService;
    private final LockboxEncryptionService lockboxEncryptionService;
    private final GeofenceService geofenceService;
    private final PhotoRejectionService photoRejectionService;
    private final PhotoRejectionBudgetService photoRejectionBudgetService;
    private final ApplicationEventPublisher eventPublisher;
    private final SupabaseStorageService supabaseStorageService;
    private final PiiPhotoStorageService piiPhotoStorageService;  // P0-2: PII enforcement
    private final ExifStrippingService exifStrippingService;      // VAL-001: EXIF privacy
    private final CheckInValidationService validationService;     // Upload timing gate
    private final PhotoUrlService photoUrlService;                // H-6: Signed URL generation
    private final TransactionOperations transactionOperations;

    @Value("${app.checkin.photo.max-size-mb:10}")
    private int maxSizeMb;
    
    // ========== VAL-001: EXIF AUDIT BACKUP CONFIGURATION ==========
    
    @Value("${app.checkin.photos.audit-backup.enabled:true}")
    private boolean auditBackupEnabled;
    
    // ========== PHASE 4E: PHOTO DEADLINE CONFIGURATION ==========
    
    @Value("${app.checkin.photo-upload-deadline-hours:24}")
    private int checkinPhotoDeadlineHours;
    
    @Value("${app.checkout.photo-upload-deadline-hours:24}")
    private int checkoutPhotoDeadlineHours;

    /**
     * Upload a check-in photo with EXIF validation and zero-storage policy.
     * 
     * <p><b>Zero-Storage Policy:</b> Rejected photos are NOT stored to the database or filesystem.
     * Only an audit event is logged. This prevents data pollution and storage waste.
     * 
     * <p>GPS coordinates can come from two sources (in order of preference):
     * <ol>
     *   <li>EXIF data embedded in the image (most reliable)</li>
     *   <li>Client-provided GPS as fallback (defense-in-depth)</li>
     * </ol>
     * The client GPS is used when EXIF GPS is missing (e.g., after
     * canvas-based compression that strips EXIF despite piexifjs preservation).
     * 
     * @param clientLatitude  Client-provided latitude (fallback)
     * @param clientLongitude Client-provided longitude (fallback)
     * @return PhotoUploadResponse with accepted=true (HTTP 201) or accepted=false (HTTP 400)
     */
    public PhotoUploadResponse uploadPhoto(
            Long bookingId,
            Long userId,
            MultipartFile file,
            CheckInPhotoType photoType,
            Instant clientTimestamp,
            BigDecimal clientLatitude,
            BigDecimal clientLongitude) throws IOException {
        
        // Validate file
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Fotografija je obavezna");
        }
        
        long maxBytes = maxSizeMb * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException(
                String.format("Fotografija je prevelika. Maksimum: %dMB", maxSizeMb));
        }
        
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Samo slike su dozvoljene");
        }
        
        // D5 Security Fix: Validate file signature (magic bytes) to prevent malicious uploads
        // Content-Type headers can be spoofed; magic bytes cannot
        validateFileSignature(file);
        
        // Get booking
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        validateUploadAccessAndStatus(booking, userId, photoType);

        CheckInActorRole actorRole = resolveActorRole(booking, userId, photoType);
        photoRejectionBudgetService.assertWithinBudget(booking, userId, actorRole, photoType);
        
        // P1 FIX: Server-side per-booking photo cap (prevents DoS via mass upload)
        long existingPhotoCount = photoRepository.countByBookingId(bookingId);
        if (existingPhotoCount >= MAX_PHOTOS_PER_BOOKING) {
            log.warn("[CheckIn] Photo upload blocked - cap exceeded: bookingId={}, count={}, max={}",
                bookingId, existingPhotoCount, MAX_PHOTOS_PER_BOOKING);
            throw new IllegalStateException(
                String.format("Maksimalan broj fotografija dostignut (%d). Obrišite postojeće pre dodavanja novih.",
                    MAX_PHOTOS_PER_BOOKING));
        }
        
        // Get user
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Korisnik nije pronađen"));
        
        // Read file bytes
        byte[] photoBytes = file.getBytes();
        
        // EXIF validation (Phase 2: Simplified)
        // We pass clientTimestamp to:
        // 1. Calculate photo age relative to when user actually took/uploaded it (basement problem fix)
        // 2. Enable "sidecar fallback" for HEIC/no-EXIF images (requires non-null timestamp)
        //
        // PHASE 2 CHANGE: Car location params REMOVED from validation
        // - Old: Validated photo GPS against car location during upload (chicken-and-egg problem)
        // - New: GPS extracted but not validated. Car location derived post-upload from first valid photo.
        // - See CheckInService.completeHostCheckIn() for derivation logic
        ExifValidationResult exifResult = exifValidationService.validate(
            photoBytes, 
            clientTimestamp
            // REMOVED Phase 2: booking.getCarLatitude(), booking.getCarLongitude()
        );
        
        // ========== ZERO-STORAGE POLICY: Check for rejection ==========
        if (photoRejectionService.shouldReject(exifResult.getStatus())) {
            return handleRejectedPhoto(booking, userId, photoType, exifResult, file.getSize(), clientTimestamp);
        }
        
        // ========== ACCEPTED PHOTO: Proceed with storage ==========
        return handleAcceptedPhoto(
            booking, user, file, photoBytes, photoType, 
            exifResult, clientLatitude, clientLongitude, clientTimestamp
        );
    }
    
    /**
     * Handle a rejected photo - log event but do NOT store to DB.
     * 
     * @return PhotoUploadResponse with accepted=false for HTTP 400 response
     */
    private PhotoUploadResponse handleRejectedPhoto(
            Booking booking,
            Long userId,
            CheckInPhotoType photoType,
            ExifValidationResult exifResult,
            long fileSize,
            Instant clientTimestamp) {
        
        PhotoRejectionInfo rejectionInfo = photoRejectionService.getRejectionInfo(exifResult.getStatus());
        
        String sessionId = photoType.isCheckoutPhoto() 
            ? booking.getCheckoutSessionId() 
            : booking.getCheckInSessionId();
        
        // Determine actor role
        CheckInActorRole actorRole = photoType.isCheckoutPhoto() 
            ? (photoType.isHostCheckoutPhoto() ? CheckInActorRole.HOST : CheckInActorRole.GUEST)
            : CheckInActorRole.HOST;
        
        // Log rejection event for audit trail (immutable)
        eventService.recordEvent(
            booking,
            sessionId,
            CheckInEventType.HOST_PHOTO_REJECTED,
            userId,
            actorRole,
            clientTimestamp,
            photoRejectionService.createRejectionEventMetadata(
                exifResult.getStatus(), photoType, fileSize
            )
        );
        
        log.info("[CheckIn] Photo REJECTED (zero-storage): booking={}, type={}, status={}, reason={}",
            booking.getId(), photoType, exifResult.getStatus(), 
            rejectionInfo != null ? rejectionInfo.getErrorCode() : "UNKNOWN");

        photoRejectionBudgetService.registerRejection(
            booking,
            userId,
            actorRole,
            photoType,
            rejectionInfo != null ? rejectionInfo.getErrorCode() : "UNKNOWN_REJECTION"
        );
        
        // Build rejection DTO (no photoId, no url - not stored)
        CheckInPhotoDTO rejectionDTO = CheckInPhotoDTO.builder()
            .photoType(photoType)
            .exifValidationStatus(exifResult.getStatus())
            .exifValidationMessage(exifResult.getMessage())
            .accepted(false)
            .rejectionReason(rejectionInfo != null ? rejectionInfo.getRejectionReason() : "Fotografija nije prihvaćena.")
            .remediationHint(rejectionInfo != null ? rejectionInfo.getRemediationHint() : "Pokušajte ponovo sa novom fotografijom.")
            .build();
        
        return PhotoUploadResponse.rejected(
            rejectionDTO, 
            rejectionInfo != null ? rejectionInfo.getErrorCode() : "UNKNOWN_REJECTION"
        );
    }

    private CheckInActorRole resolveActorRole(Booking booking, Long userId, CheckInPhotoType photoType) {
        if (photoType.isCheckoutPhoto()) {
            return photoType.isHostCheckoutPhoto() ? CheckInActorRole.HOST : CheckInActorRole.GUEST;
        }
        return booking.getCar().getOwner().getId().equals(userId) ? CheckInActorRole.HOST : CheckInActorRole.GUEST;
    }
    
    /**
     * Handle an accepted photo - store to filesystem and database.
     * 
     * @return PhotoUploadResponse with accepted=true for HTTP 201 response
     */
    private PhotoUploadResponse handleAcceptedPhoto(
            Booking booking,
            User user,
            MultipartFile file,
            byte[] photoBytes,
            CheckInPhotoType photoType,
            ExifValidationResult exifResult,
            BigDecimal clientLatitude,
            BigDecimal clientLongitude,
            Instant clientTimestamp) throws IOException {
        
        // Use client GPS as fallback when EXIF GPS is missing
        // (defense-in-depth for canvas compression scenarios)
        BigDecimal finalLatitude = exifResult.getLatitude();
        BigDecimal finalLongitude = exifResult.getLongitude();
        boolean usedClientGps = false;
        
        if (finalLatitude == null && clientLatitude != null) {
            finalLatitude = clientLatitude;
            finalLongitude = clientLongitude;
            usedClientGps = true;
            log.debug("[CheckIn] Using client GPS as fallback: ({}, {})", clientLatitude, clientLongitude);
        }
        
        String contentType = file.getContentType();
        
        // Determine storage bucket
        CheckInPhoto.StorageBucket bucket;
        if (photoType.name().contains("ID")) {
            bucket = CheckInPhoto.StorageBucket.CHECKIN_PII;
        } else if (photoType.isCheckoutPhoto()) {
            bucket = CheckInPhoto.StorageBucket.CHECKIN_STANDARD;
        } else {
            bucket = CheckInPhoto.StorageBucket.CHECKIN_STANDARD;
        }
        
        // ========== VAL-001: EXIF GPS Privacy Protection ==========
        // Strip EXIF metadata before public upload to prevent GPS exposure
        // Original with EXIF is stored in admin-only audit bucket for disputes
        String party = booking.getCar().getOwner().getId().equals(user.getId()) ? "host" : "guest";
        String uploadToken = UUID.randomUUID().toString().replace("-", "");
        String reservedStorageKey = buildReservedStorageKey(booking.getId(), party, photoType, uploadToken, contentType, false);
        String reservedAuditStorageKey = auditBackupEnabled
            ? buildReservedStorageKey(booking.getId(), party, photoType, uploadToken, contentType, true)
            : null;
        
        // ========== R1/R2: SHA-256 IMAGE HASH (Trust & Evidence Audit) ==========
        // Compute hash of ORIGINAL bytes BEFORE any storage writes.
        // This creates a verifiable link: DB record ↔ audit bucket blob ↔ public bucket blob.
        // Hash is of the original (pre-EXIF-strip) bytes for chain-of-custody integrity.
        String imageHash = computeSha256(photoBytes);

        // ================================================================
        // PHASE 4E: PHOTO DEADLINE - EVIDENCE WEIGHT DETERMINATION
        // ================================================================
        EvidenceWeightResult evidenceResult = determineEvidenceWeight(booking, photoType);

        CheckInPhoto photo = reservePendingUpload(
            booking.getId(),
            user.getId(),
                CheckInPhoto.builder()
                        .booking(booking)
                        .checkInSessionId(photoType.isCheckoutPhoto() ? booking.getCheckoutSessionId() : booking.getCheckInSessionId())
                        .photoType(photoType)
                        .storageBucket(bucket)
                        .storageKey(reservedStorageKey)
                        .auditStorageKey(reservedAuditStorageKey)
                        .imageHash(imageHash)
                        .originalFilename(file.getOriginalFilename())
                        .mimeType(contentType)
                        .fileSizeBytes((int) file.getSize())
                        .exifTimestamp(exifResult.getPhotoTimestamp())
                        .exifLatitude(finalLatitude)
                        .exifLongitude(finalLongitude)
                        .exifDeviceMake(exifResult.getDeviceMake())
                        .exifDeviceModel(exifResult.getDeviceModel())
                        .exifValidationStatus(exifResult.getStatus())
                        .exifValidationMessage(exifResult.getMessage())
                        .exifValidatedAt(Instant.now())
                        .evidenceWeight(evidenceResult.weight())
                        .evidenceWeightDowngradedAt(evidenceResult.isLate() ? Instant.now() : null)
                        .evidenceWeightDowngradeReason(evidenceResult.isLate() ? evidenceResult.reason() : null)
                        .uploadedBy(user)
                        .clientUploadedAt(clientTimestamp)
                        .build(),
                auditBackupEnabled
        );

        String sessionId = photo.getCheckInSessionId();
        String auditStorageKey = null;
        CheckInPhoto.AuditUploadStatus auditUploadStatus = auditBackupEnabled
                ? CheckInPhoto.AuditUploadStatus.PENDING
                : CheckInPhoto.AuditUploadStatus.NOT_REQUIRED;
        String auditFailureMessage = null;

        // ========== STORAGE: Supabase Storage ==========
        try {
            // Step 1: Upload original to audit bucket (if enabled)
            if (auditBackupEnabled) {
                try {
                    supabaseStorageService.uploadCheckInPhotoToAuditBucketAtPath(
                            photo.getAuditStorageKey(),
                            photoBytes,
                            contentType
                    );
                    auditStorageKey = photo.getAuditStorageKey();
                    auditUploadStatus = CheckInPhoto.AuditUploadStatus.COMPLETED;
                    log.info("[VAL-001] Audit backup uploaded: booking={}, type={}, key={}",
                        booking.getId(), photoType, auditStorageKey);
                } catch (Exception e) {
                    auditUploadStatus = CheckInPhoto.AuditUploadStatus.FAILED;
                    auditFailureMessage = "Audit bucket upload failed: " + e.getMessage();
                    log.error("[R3-INTEGRITY-GAP] Audit backup FAILED — evidence integrity gap recorded. " +
                            "booking={}, type={}, error={}, imageHash={}",
                        booking.getId(), photoType, e.getMessage(), imageHash);
                }
            }
            
            // Step 2: Strip EXIF metadata from photo
            byte[] strippedBytes = exifStrippingService.stripExifMetadata(photoBytes, contentType);
            log.debug("[VAL-001] EXIF stripped: booking={}, original={} bytes, stripped={} bytes",
                booking.getId(), photoBytes.length, strippedBytes.length);
            
            // Step 3: Upload stripped photo to public bucket
            supabaseStorageService.uploadCheckInPhotoBytesAtPath(
                    photo.getStorageKey(),
                    strippedBytes,
                    contentType
            );
            log.info("[CheckIn] Photo uploaded to Supabase (EXIF stripped): booking={}, type={}, key={}",
                booking.getId(), photoType, photo.getStorageKey());
        } catch (IOException e) {
            cleanupPartialStorage(photo.getStorageKey(), auditUploadStatus == CheckInPhoto.AuditUploadStatus.COMPLETED ? auditStorageKey : null);
            markUploadFailedTerminal(photo.getId(), "Standard bucket upload failed: " + e.getMessage());
            eventService.recordEvent(
                    booking,
                    sessionId,
                    CheckInEventType.PHOTO_UPLOAD_STORAGE_FAILED,
                    user.getId(),
                    CheckInActorRole.SYSTEM,
                    Map.of(
                            "photoId", photo.getId(),
                            "photoType", photoType.name(),
                            "stage", "STANDARD_UPLOAD",
                            "error", e.getMessage() != null ? e.getMessage() : "unknown",
                            "uploadStatus", CheckInPhoto.UploadStatus.FAILED_TERMINAL.name()
                    )
            );
            log.error("[CheckIn] Supabase upload failed for booking {}: {}",
                booking.getId(), e.getMessage(), e);
            throw e;
        }

        markPendingFinalize(photo.getId(), auditUploadStatus, auditStorageKey, auditFailureMessage);
        
        if (evidenceResult.isLate()) {
            // Log audit event for late upload
            eventService.recordEvent(
                booking,
                sessionId,
                CheckInEventType.PHOTO_UPLOAD_LATE,
                user.getId(),
                photoType.isHostCheckoutPhoto() ? CheckInActorRole.HOST : 
                    (photoType.isCheckoutPhoto() ? CheckInActorRole.GUEST : CheckInActorRole.HOST),
                Map.of(
                    "photoType", photoType.name(),
                    "deadlineHours", photoType.isCheckoutPhoto() ? checkoutPhotoDeadlineHours : checkinPhotoDeadlineHours,
                    "evidenceWeight", EvidenceWeight.SECONDARY.name(),
                    "reason", evidenceResult.reason()
                )
            );
            
            log.warn("[Phase4E] LATE PHOTO UPLOAD: booking={}, type={}, weight=SECONDARY, reason={}",
                    booking.getId(), photoType, evidenceResult.reason());
        }

        // ================================================================
        // SLOT IDEMPOTENCY: For required types, soft-delete previous photo
        // so retakes replace the prior slot photo instead of appending duplicates.
        // Damage/custom types are multi-entry by design and skip this.
        // ================================================================
        try {
            photo = finalizePendingUpload(photo.getId(), user.getId());
        } catch (PhotoUploadFinalizeException e) {
            CheckInPhoto latestPhoto = photoRepository.findById(photo.getId()).orElse(photo);
            if (e.isTerminal()) {
                cleanupPartialStorage(photo.getStorageKey(), auditUploadStatus == CheckInPhoto.AuditUploadStatus.COMPLETED ? auditStorageKey : null);
            }
            eventService.recordEvent(
                    booking,
                    sessionId,
                    CheckInEventType.PHOTO_UPLOAD_STORAGE_FAILED,
                    user.getId(),
                    CheckInActorRole.SYSTEM,
                    Map.of(
                            "photoId", latestPhoto.getId(),
                            "photoType", photoType.name(),
                            "stage", e.isTerminal() ? "FINALIZE_TERMINAL" : "FINALIZE_RETRYABLE",
                            "uploadStatus", latestPhoto.getUploadStatus().name(),
                            "retryable", !e.isTerminal(),
                            "error", e.getMessage()
                    )
            );
            throw e;
        }

        // ========== R1: FRAUD DETECTION — Cross-booking duplicate hash check ==========
        if (imageHash != null
                && photoRepository.existsByImageHashOnOtherBooking(imageHash, booking.getId())) {
            log.warn("[R1-FRAUD-ALERT] Duplicate image hash detected on check-in! " +
                    "booking={}, photoId={}, hash={} — same image exists on another booking",
                    booking.getId(), photo.getId(), imageHash);

            eventService.recordEvent(
                booking,
                sessionId,
                CheckInEventType.HOST_PHOTO_UPLOADED,
                user.getId(),
                CheckInActorRole.SYSTEM,
                Map.of(
                    "fraudAlert", "DUPLICATE_IMAGE_HASH",
                    "photoId", photo.getId(),
                    "photoType", photoType.name(),
                    "imageHash", imageHash
                )
            );
        }

        // ========== R9 (P2): GPS CROSS-VALIDATION against car listing location ==========
        // Check if the photo's GPS (EXIF or client fallback) is plausible relative to
        // the car's registered/listing location. This catches GPS spoofing more precisely
        // than the Serbia-wide bounding box in ExifValidationService.
        if (finalLatitude != null && finalLongitude != null
                && booking.getCar().hasGeoLocation()) {
            BigDecimal carLat = booking.getCar().getLocationGeoPoint().getLatitude();
            BigDecimal carLon = booking.getCar().getLocationGeoPoint().getLongitude();
            if (carLat != null && carLon != null) {
                double distanceMeters = geofenceService.haversineDistance(
                    finalLatitude.doubleValue(), finalLongitude.doubleValue(),
                    carLat.doubleValue(), carLon.doubleValue()
                );
                // 10 km threshold: cars can be at slightly different spots, but >10km is suspicious
                if (distanceMeters > 10_000) {
                    log.warn("[R9-GPS-ANOMALY] Photo GPS far from car listing location: " +
                            "booking={}, photoType={}, distance={}m, photoGps=({},{}), carGps=({},{}), usedClientGps={}",
                        booking.getId(), photoType, (int) distanceMeters,
                        finalLatitude, finalLongitude, carLat, carLon, usedClientGps);

                    eventService.recordEvent(
                        booking,
                        sessionId,
                        CheckInEventType.HOST_PHOTO_UPLOADED,
                        user.getId(),
                        CheckInActorRole.SYSTEM,
                        Map.of(
                            "gpsAnomaly", true,
                            "photoType", photoType.name(),
                            "distanceMeters", (int) distanceMeters,
                            "usedClientGps", usedClientGps,
                            "photoLat", finalLatitude.doubleValue(),
                            "photoLon", finalLongitude.doubleValue(),
                            "carLat", carLat.doubleValue(),
                            "carLon", carLon.doubleValue()
                        )
                    );
                }
            }
        }

        // Determine event type and actor role based on photo type
        CheckInEventType eventType;
        CheckInActorRole actorRole;
        
        if (photoType.isCheckoutPhoto()) {
            if (photoType.isHostCheckoutPhoto()) {
                eventType = CheckInEventType.CHECKOUT_HOST_CONFIRMED; // Host checkout photo
                actorRole = CheckInActorRole.HOST;
            } else {
                eventType = CheckInEventType.CHECKOUT_GUEST_PHOTO_UPLOADED;
                actorRole = CheckInActorRole.GUEST;
            }
        } else {
            eventType = CheckInEventType.HOST_PHOTO_UPLOADED;
            actorRole = CheckInActorRole.HOST;
        }
        
        // Record event
        eventService.recordEvent(
            booking,
            sessionId,
            eventType,
            user.getId(),
            actorRole,
            clientTimestamp,
            Map.of(
                "photoId", photo.getId(),
                "photoType", photoType.name(),
                "exifValid", exifResult.isAccepted(),
                "exifStatus", exifResult.getStatus().name(),
                "fileSize", file.getSize(),
                "usedClientGps", usedClientGps,
                "imageHash", imageHash != null ? imageHash : "HASH_FAILED",
                "auditIntegrityGap", photo.getAuditUploadStatus() == CheckInPhoto.AuditUploadStatus.FAILED
            )
        );
        
        log.info("[CheckIn] Photo ACCEPTED: booking={}, type={}, photoId={}, exifStatus={}", 
            booking.getId(), photoType, photo.getId(), exifResult.getStatus());
        
        // Publish CQRS event for real-time photo count updates in dashboard
        publishPhotoUploadedEvent(booking, photo, photoType, sessionId);
        
        // Build accepted DTO
        CheckInPhotoDTO acceptedDTO = mapToDTO(photo);
        acceptedDTO.setAccepted(true);
        
        return PhotoUploadResponse.accepted(acceptedDTO);
    }
    
    /**
     * Publish PhotoUploaded event for CQRS read model synchronization.
     * 
     * <p>This event triggers CheckInStatusViewSyncListener to increment the
     * photo_count in the denormalized view, enabling real-time dashboard updates
     * without polling or complex JOINs.
     * 
     * <p><b>Error Handling:</b> Event publishing failures are logged but don't
     * fail the photo upload (degraded mode). The photo is still stored and
     * functional; only the real-time dashboard update is affected.
     * 
     * <p><b>Photo Count Accuracy:</b> Only ACCEPTED photos are counted. Rejected
     * photos are never stored (zero-storage policy) so count stays accurate.
     * Photo deletions publish separate events to decrement count.
     * 
     * @param booking The booking associated with the photo
     * @param photo The saved photo entity (only called for accepted photos)
     * @param photoType The type of photo uploaded
     * @param sessionId The check-in or checkout session ID
     */
    private void publishPhotoUploadedEvent(
            Booking booking,
            CheckInPhoto photo,
            CheckInPhotoType photoType,
            String sessionId) {
        try {
            // Only publish for check-in photos (not checkout)
            // Checkout photos can be added later if needed
            if (!photoType.isCheckoutPhoto()) {
                eventPublisher.publishEvent(new CheckInDomainEvent.PhotoUploaded(
                        booking.getId(),
                        UUID.fromString(sessionId),
                        photo.getId(),
                        photoType.name(),
                        Instant.now()
                ));
                
                log.debug("[CheckIn] Published PhotoUploaded event: booking={}, photoId={}, type={}",
                        booking.getId(), photo.getId(), photoType);
            }
        } catch (Exception e) {
            // Log error but don't fail photo upload (system resilience)
            // Photo is saved; only real-time dashboard update is affected
            log.error("[CheckIn] Failed to publish PhotoUploaded event for booking {} photo {}: {}",
                    booking.getId(), photo.getId(), e.getMessage(), e);
            // In production, increment a metric counter here for monitoring:
            // eventPublishFailureCounter.increment();
        }
    }
    
    /**
     * Delete a photo and update CQRS view count.
     * 
     * <p><b>NOTE:</b> This method should be called when implementing photo deletion.
     * Currently photos are immutable (no deletion endpoint), but this ensures
     * future-proofing if deletion is added.
     * 
     * @param photoId Photo to delete
     * @param userId User requesting deletion
     */
    @Transactional
    public void deletePhoto(Long photoId, Long userId) {
        CheckInPhoto photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new ResourceNotFoundException("Fotografija nije pronađena"));
        
        Booking booking = photo.getBooking();
        
        // Validate access
        if (!booking.getCar().getOwner().getId().equals(userId)) {
            throw new AccessDeniedException("Samo vlasnik vozila može obrisati fotografije");
        }
        
        // Soft delete using entity method (maintains audit trail)
        User deletingUser = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        photo.softDelete(deletingUser, "Deleted by host");
        photoRepository.save(photo);
        
        // Publish event to decrement count in view
        try {
            if (!photo.getPhotoType().isCheckoutPhoto()) {
                // Create PhotoDeleted event (would need to be added to CheckInDomainEvent)
                // For now, trigger a full view refresh by republishing current state
                log.warn("[CheckIn] Photo deleted - view count may be stale until next event");
            }
        } catch (Exception e) {
            log.error("[CheckIn] Failed to update view after photo deletion: {}", e.getMessage());
        }
    }

    /**
     * Reveal lockbox code to guest.
     * 
     * <p><b>P0 SECURITY FIX:</b> Geofence enforcement is now MANDATORY for lockbox reveal.
     * Guest MUST provide GPS coordinates and be within 50m of the car.
     * This prevents remote lockbox code extraction without physical presence.
     */
    @Transactional
    public String revealLockboxCode(
            Long bookingId,
            Long userId,
            BigDecimal latitude,
            BigDecimal longitude) {
        
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        // Validate guest access
        if (!booking.getRenter().getId().equals(userId)) {
            throw new AccessDeniedException("Samo gost može videti šifru lokota");
        }
        
        // Validate lockbox exists
        if (booking.getLockboxCodeEncrypted() == null) {
            throw new IllegalStateException("Ova rezervacija ne koristi lokot za ključeve");
        }
        
        // Validate status — lockbox reveal only allowed during active check-in/trip states
        // Explicitly list allowed statuses instead of fragile ordinal() comparison
        java.util.Set<BookingStatus> allowedForLockbox = java.util.EnumSet.of(
            BookingStatus.CHECK_IN_HOST_COMPLETE,
            BookingStatus.CHECK_IN_COMPLETE,
            BookingStatus.IN_TRIP
        );
        if (!allowedForLockbox.contains(booking.getStatus())) {
            throw new IllegalStateException(
                "Lockbox kod nije dostupan u trenutnom statusu rezervacije: " + booking.getStatus());
        }
        
        // P0 FIX: Mandatory GPS coordinates for lockbox reveal
        if (latitude == null || longitude == null) {
            log.warn("[CheckIn] Lockbox reveal blocked - no GPS coordinates: bookingId={}, userId={}",
                bookingId, userId);
            throw new IllegalArgumentException(
                "GPS lokacija je obavezna za otkrivanje šifre lokota. Omogućite lokaciju na uređaju.");
        }
        
        // P0 FIX: Hard 50m geofence enforcement for lockbox reveal
        // Determine reference location: car EXIF GPS → pickup location → block
        BigDecimal refLat = booking.getCarLatitude();
        BigDecimal refLon = booking.getCarLongitude();
        String locationSource = "CAR_EXIF_GPS";
        
        if (refLat == null || refLon == null) {
            // Fallback to pickup location (or car home location)
            if (booking.getPickupLocation() != null && booking.getPickupLocation().hasCoordinates()) {
                refLat = booking.getPickupLocation().getLatitude();
                refLon = booking.getPickupLocation().getLongitude();
                locationSource = "PICKUP_LOCATION";
                log.info("[CheckIn] Lockbox reveal using pickup location as geofence reference: bookingId={}", bookingId);
            } else if (booking.getCar() != null && booking.getCar().getLocationGeoPoint() != null 
                       && booking.getCar().getLocationGeoPoint().hasCoordinates()) {
                refLat = booking.getCar().getLocationGeoPoint().getLatitude();
                refLon = booking.getCar().getLocationGeoPoint().getLongitude();
                locationSource = "CAR_HOME_LOCATION";
                log.info("[CheckIn] Lockbox reveal using car home location as geofence reference: bookingId={}", bookingId);
            } else {
                log.warn("[CheckIn] Lockbox reveal blocked - no reference location available: bookingId={}", bookingId);
                eventService.recordEvent(
                    booking,
                    booking.getCheckInSessionId(),
                    CheckInEventType.GEOFENCE_CHECK_FAILED,
                    userId,
                    CheckInActorRole.GUEST,
                    Map.of(
                        "context", "LOCKBOX_REVEAL",
                        "reason", "NO_REFERENCE_LOCATION",
                        "guestLatitude", latitude.toString(),
                        "guestLongitude", longitude.toString()
                    )
                );
                throw new IllegalStateException(
                    "Lokacija vozila nije dostupna. Kontaktirajte domaćina ili podršku.");
            }
        }
        
        double distance = geofenceService.haversineDistance(
            refLat.doubleValue(), refLon.doubleValue(),
            latitude.doubleValue(), longitude.doubleValue()
        );
        
        int distanceRounded = (int) Math.round(distance);
        
        // Persist distance for frontend geofenceValid computation
        booking.setGeofenceDistanceMeters(distanceRounded);
        
        if (distanceRounded > LOCKBOX_REVEAL_MAX_DISTANCE_METERS) {
            log.warn("[CheckIn] Lockbox reveal blocked - geofence violation: bookingId={}, distance={}m, threshold={}m, locationSource={}",
                bookingId, distanceRounded, LOCKBOX_REVEAL_MAX_DISTANCE_METERS, locationSource);
            
            eventService.recordEvent(
                booking,
                booking.getCheckInSessionId(),
                CheckInEventType.GEOFENCE_CHECK_FAILED,
                userId,
                CheckInActorRole.GUEST,
                Map.of(
                    "context", "LOCKBOX_REVEAL",
                    "distanceMeters", distanceRounded,
                    "thresholdMeters", LOCKBOX_REVEAL_MAX_DISTANCE_METERS,
                    "locationSource", locationSource,
                    "guestLatitude", latitude.toString(),
                    "guestLongitude", longitude.toString()
                )
            );
            
            throw new IllegalStateException(
                String.format("Morate biti unutar %dm od vozila za pristup šifri lokota. Trenutna udaljenost: %dm",
                    LOCKBOX_REVEAL_MAX_DISTANCE_METERS, distanceRounded));
        }
        
        log.info("[CheckIn] Lockbox reveal geofence passed: bookingId={}, distance={}m, locationSource={}",
            bookingId, distanceRounded, locationSource);
        
        // Record reveal event
        booking.setLockboxCodeRevealedAt(Instant.now());
        
        eventService.recordEvent(
            booking,
            booking.getCheckInSessionId(),
            CheckInEventType.LOCKBOX_CODE_REVEALED,
            userId,
            CheckInActorRole.GUEST,
            Map.of(
                "revealedAt", Instant.now().toString(),
                "guestLatitude", latitude.toString(),
                "guestLongitude", longitude.toString(),
                "geofenceEnforced", true
            )
        );
        
        bookingRepository.save(booking);
        
        // Decrypt the lockbox code using AES-256-GCM
        String decryptedCode = lockboxEncryptionService.decrypt(booking.getLockboxCodeEncrypted());
        log.info("[CheckIn] Lockbox code decrypted and revealed for booking {} to user {}", bookingId, userId);
        
        return decryptedCode;
    }

    /**
     * Stores an identity verification photo securely in Supabase PII bucket.
     * 
     * <p><b>P0-2 FIX:</b> This method ONLY stores to Supabase encrypted PII bucket.
     * No fallback to local filesystem. If Supabase is not configured, throws exception.
     * 
     * <p>This method handles driver's licenses, passports, and selfies used for
     * biometric verification. All uploads are encrypted at rest and restricted
     * to service-role access only.
     * 
     * <p><b>Security Notes:</b>
     * <ul>
     *   <li>Photos ONLY stored in encrypted Supabase bucket (no local filesystem)</li>
     *   <li>Storage keys include session UUID for namespace isolation</li>
     *   <li>Original filenames are NOT preserved (prevent enumeration attacks)</li>
     *   <li>Throws IllegalStateException if Supabase not configured</li>
     * </ul>
     * 
     * @param bookingId Booking identifier (for audit trail)
     * @param sessionId Check-in session UUID (namespace isolation)
     * @param file Multipart file upload (validated upstream)
     * @param photoType ID document type (ID_FRONT, ID_BACK, SELFIE)
     * @return Storage key in format: checkin_pii/{sessionId}/{type}_{timestamp}_{random}.jpg
     * @throws IOException if storage upload fails
     * @throws IllegalArgumentException if file validation fails
     * @throws IllegalStateException if Supabase not configured (P0-2 enforcement)
     */
    public String storeIdPhoto(Long bookingId, String sessionId, MultipartFile file, String photoType) throws IOException {
        // Validate file
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Fotografija je obavezna");
        }
        
        long maxBytes = maxSizeMb * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException(
                String.format("Fotografija je prevelika. Maksimum: %dMB", maxSizeMb));
        }
        
        // Generate storage path in PII bucket
        String filename = String.format("%s_%s_%s.jpg", 
            photoType, 
            System.currentTimeMillis(),
            UUID.randomUUID().toString().substring(0, 8)
        );
        String storageKey = String.format("checkin_pii/%s/%s", sessionId, filename);
        
        // ========== P0-2 FIX: FORCE SUPABASE-ONLY STORAGE FOR PII ==========
        //
        // CRITICAL SECURITY: Identity documents MUST be encrypted.
        // We use PiiPhotoStorageService which:
        // 1. Validates Supabase is enabled
        // 2. Validates bucket is a PII bucket
        // 3. Throws exception if configuration invalid
        // 4. Has NO fallback to insecure local filesystem
        //
        try {
            piiPhotoStorageService.storePiiPhoto(
                "checkin_pii",  // Encrypted bucket for check-in PII
                storageKey,
                file.getBytes(),
                file.getContentType()
            );
            log.info("[CheckIn-PII-P0-2] ID photo securely stored in Supabase: booking={}, session={}, type={}, key={}",
                bookingId, sessionId, photoType, storageKey);
        } catch (IllegalStateException e) {
            // P0-2: Re-throw configuration error with context
            log.error("[CheckIn-PII-P0-2] CRITICAL: PII storage misconfigured: {}", e.getMessage(), e);
            throw e;
        } catch (IOException e) {
            log.error("[CheckIn-PII-P0-2] Failed to store PII photo to Supabase: booking={}, session={}, type={}",
                bookingId, sessionId, photoType, e);
            throw new IOException("Nije moguće pohraniti identifikacijski dokument. Pokušajte ponovo.", e);
        }
        
        return storageKey;
    }

    private CheckInPhotoDTO mapToDTO(CheckInPhoto photo) {
        // H-6 FIX: Generate signed URL instead of returning raw storage key
        String bucketName = (photo.getStorageBucket() == CheckInPhoto.StorageBucket.CHECKIN_PII)
                ? "check-in-pii" : "check-in-photos";
        String signedUrl = resolveSignedUrl(bucketName, photo.getStorageKey(), photo.getId());

        return CheckInPhotoDTO.builder()
                .photoId(photo.getId())
                .photoType(photo.getPhotoType())
                .url(signedUrl)
                .uploadedAt(toLocalDateTime(photo.getUploadedAt()))
                .exifValidationStatus(photo.getExifValidationStatus())
                .exifValidationMessage(photo.getExifValidationMessage())
                .width(photo.getImageWidth())
                .height(photo.getImageHeight())
                .mimeType(photo.getMimeType())
                .exifTimestamp(toLocalDateTime(photo.getExifTimestamp()))
                .exifLatitude(photo.getExifLatitude() != null ? photo.getExifLatitude().doubleValue() : null)
                .exifLongitude(photo.getExifLongitude() != null ? photo.getExifLongitude().doubleValue() : null)
                .deviceModel(photo.getExifDeviceModel())
                .build();
    }

    private String resolveSignedUrl(String bucketName, String storageKey, Long photoId) {
        if (storageKey == null || storageKey.isEmpty()) {
            return "";
        }
        if (storageKey.startsWith("http://") || storageKey.startsWith("https://")) {
            return storageKey;
        }
        try {
            return photoUrlService.generateSignedUrl(bucketName, storageKey, photoId);
        } catch (Exception e) {
            log.error("[CheckIn] Failed to generate signed URL for photo {}: key={}", photoId, storageKey, e);
            return "";
        }
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) return null;
        return LocalDateTime.ofInstant(instant, SERBIA_ZONE);
    }

    public UploadReconciliationResult reconcileStaleUploads(Instant staleBefore) {
        List<Long> stalePhotoIds = photoRepository.findStalePendingUploads(staleBefore).stream()
                .map(CheckInPhoto::getId)
                .toList();

        int finalized = 0;
        int terminalFailures = 0;

        for (Long photoId : stalePhotoIds) {
            UploadReconciliationOutcome outcome = transactionOperations.execute(status -> reconcileSinglePendingUpload(photoId));
            if (outcome == null) {
                continue;
            }
            switch (outcome) {
                case FINALIZED -> finalized++;
                case TERMINAL_FAILURE -> terminalFailures++;
                case RETRYABLE_PENDING, NO_ACTION -> {
                }
            }
        }

        return new UploadReconciliationResult(finalized, terminalFailures);
    }

    public record UploadReconciliationResult(int finalized, int terminalFailures) {}

    private void validateUploadAccessAndStatus(Booking booking, Long userId, CheckInPhotoType photoType) {
        if (photoType.isCheckoutPhoto()) {
            if (photoType.isHostCheckoutPhoto()) {
                if (!booking.getCar().getOwner().getId().equals(userId)) {
                    throw new AccessDeniedException("Samo vlasnik vozila može otpremiti fotografije za checkout");
                }
            } else {
                if (!booking.getRenter().getId().equals(userId)) {
                    throw new AccessDeniedException("Samo gost može otpremiti fotografije za checkout");
                }
            }
        } else if (!booking.getCar().getOwner().getId().equals(userId)) {
            throw new AccessDeniedException("Samo vlasnik vozila može otpremiti fotografije");
        }

        if (photoType.isCheckoutPhoto()) {
            if (photoType.isHostCheckoutPhoto()) {
                if (booking.getStatus() != BookingStatus.CHECKOUT_GUEST_COMPLETE) {
                    throw new IllegalStateException("Checkout nije spreman za otpremanje fotografija od strane domaćina");
                }
            } else if (booking.getStatus() == BookingStatus.CHECKOUT_DAMAGE_DISPUTE
                    && (photoType == CheckInPhotoType.CHECKOUT_DAMAGE_NEW
                    || photoType == CheckInPhotoType.CHECKOUT_CUSTOM)) {
                log.debug("[CheckIn] Allowing dispute-evidence upload: booking={}, type={}", booking.getId(), photoType);
            } else if (booking.getStatus() != BookingStatus.CHECKOUT_OPEN) {
                throw new IllegalStateException("Checkout nije otvoren za otpremanje fotografija");
            }
        } else {
            if (booking.getStatus() != BookingStatus.CHECK_IN_OPEN) {
                throw new IllegalStateException("Prijem nije otvoren za otpremanje fotografija");
            }
            validationService.validateUploadTiming(booking);
        }
    }

    private CheckInPhoto reservePendingUpload(Long bookingId, Long userId, CheckInPhoto draft, boolean auditRequired) {
        return transactionOperations.execute(status -> {
            Booking lockedBooking = bookingRepository.findByIdWithLock(bookingId)
                    .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
            validateUploadAccessAndStatus(lockedBooking, userId, draft.getPhotoType());

            String sessionId = resolveSessionId(lockedBooking, draft.getPhotoType());
            User uploader = userRepository.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Korisnik nije pronađen"));

            draft.setBooking(lockedBooking);
            draft.setUploadedBy(uploader);
            draft.setCheckInSessionId(sessionId);
            draft.markUploadPending(auditRequired);

            return photoRepository.save(draft);
        });
    }

    private void markPendingFinalize(Long photoId,
                                     CheckInPhoto.AuditUploadStatus auditStatus,
                                     String auditStorageKey,
                                     String errorMessage) {
        transactionOperations.execute(status -> {
            CheckInPhoto photo = photoRepository.findById(photoId)
                    .orElseThrow(() -> new ResourceNotFoundException("Fotografija nije pronađena"));
            photo.recordUploadAttempt();
            photo.markPendingFinalize(auditStatus, auditStorageKey, errorMessage);
            photoRepository.save(photo);
            return null;
        });
    }

    private CheckInPhoto finalizePendingUpload(Long photoId, Long userId) {
        return transactionOperations.execute(status -> {
            CheckInPhoto photo = photoRepository.findById(photoId)
                    .orElseThrow(() -> new ResourceNotFoundException("Fotografija nije pronađena"));

            Booking lockedBooking = bookingRepository.findByIdWithLock(photo.getBooking().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
            photo.setBooking(lockedBooking);

            if (photo.isCompletedUpload()) {
                return photo;
            }

            if (photo.getUploadStatus() != CheckInPhoto.UploadStatus.PENDING_FINALIZE) {
                throw new IllegalStateException("Otpremanje fotografije nije spremno za finalizaciju.");
            }

            if (photo.getPhotoType().isRequiredForHost() || photo.getPhotoType().isRequiredForCheckout()
                    || photo.getPhotoType().isRequiredForGuestCheckIn() || photo.getPhotoType().isRequiredForHostCheckout()) {
                List<CheckInPhoto> existing = photoRepository.findCompletedByBookingIdAndPhotoTypeExcludingId(
                        lockedBooking.getId(), photo.getPhotoType(), photo.getId());
                User replacingUser = userRepository.findById(userId)
                        .orElseThrow(() -> new ResourceNotFoundException("Korisnik nije pronađen"));
                for (CheckInPhoto old : existing) {
                    if (!old.isDeleted()) {
                        old.softDelete(replacingUser, "REPLACED_BY_RETAKE");
                        photoRepository.save(old);
                        log.info("[CheckIn] Replaced existing {} photo for booking {} (old id={})",
                                photo.getPhotoType(), lockedBooking.getId(), old.getId());
                    }
                }
            }

            try {
                photo.markCompleted();
                return photoRepository.save(photo);
            } catch (DataIntegrityViolationException e) {
                String detail = e.getMostSpecificCause() != null
                        ? e.getMostSpecificCause().getMessage()
                        : e.getMessage();
                if (isRequiredSlotConflict(detail)) {
                    photo.markTerminalFailure("Required photo slot conflict during finalize: " + detail);
                    photoRepository.save(photo);
                    throw PhotoUploadFinalizeException.terminal(
                            "Fotografija za ovaj obavezni ugao je upravo zamenjena drugim otpremanjem. Osvežite stranicu i proverite poslednju fotografiju.",
                            e
                    );
                }

                photo.markPendingFinalize(photo.getAuditUploadStatus(), photo.getAuditStorageKey(),
                        "Finalize pending after database failure: " + detail);
                photoRepository.save(photo);
                throw PhotoUploadFinalizeException.retryable(
                        "Otpremanje fotografije je sačuvano u skladištu, ali finalizacija nije uspela. Sistem će pokušati oporavak.",
                        e
                );
            }
        });
    }

    private boolean isRequiredSlotConflict(String detail) {
        if (detail == null || detail.isBlank()) {
            return false;
        }
        String normalized = detail.toLowerCase(Locale.ROOT);
        return normalized.contains("uq_checkin_photo_required_slot")
                || (normalized.contains("check_in_photos")
                && normalized.contains("booking_id")
                && normalized.contains("photo_type")
                && normalized.contains("duplicate"));
    }

    private void markUploadFailedTerminal(Long photoId, String errorMessage) {
        transactionOperations.execute(status -> {
            CheckInPhoto photo = photoRepository.findById(photoId)
                    .orElseThrow(() -> new ResourceNotFoundException("Fotografija nije pronađena"));
            photo.recordUploadAttempt();
            photo.markTerminalFailure(errorMessage);
            photoRepository.save(photo);
            return null;
        });
    }

    private UploadReconciliationOutcome reconcileSinglePendingUpload(Long photoId) {
        CheckInPhoto photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new ResourceNotFoundException("Fotografija nije pronađena"));

        if (photo.isDeleted() || photo.isCompletedUpload() || photo.getUploadStatus() == CheckInPhoto.UploadStatus.FAILED_TERMINAL) {
            return UploadReconciliationOutcome.NO_ACTION;
        }

        boolean standardExists = supabaseStorageService.checkInPhotoExists(photo.getStorageKey());
        boolean auditExists = photo.getAuditStorageKey() != null
                && photo.getAuditUploadStatus() != CheckInPhoto.AuditUploadStatus.NOT_REQUIRED
                && supabaseStorageService.checkInAuditPhotoExists(photo.getAuditStorageKey());

        if (!standardExists) {
            cleanupPartialStorage(photo.getStorageKey(), auditExists ? photo.getAuditStorageKey() : null);
            photo.recordUploadAttempt();
            photo.markTerminalFailure("Reconciliation could not find the standard-bucket object for pending upload.");
            photoRepository.save(photo);
            eventService.recordSystemEvent(
                    photo.getBooking(),
                    photo.getCheckInSessionId(),
                    CheckInEventType.PHOTO_UPLOAD_STORAGE_FAILED,
                    Map.of(
                            "photoId", photo.getId(),
                            "photoType", photo.getPhotoType().name(),
                            "stage", "RECONCILIATION",
                            "uploadStatus", photo.getUploadStatus().name(),
                            "error", photo.getLastUploadError() != null ? photo.getLastUploadError() : "STANDARD_OBJECT_MISSING"
                    )
            );
                        return UploadReconciliationOutcome.TERMINAL_FAILURE;
        }

        if (photo.getUploadStatus() == CheckInPhoto.UploadStatus.PENDING_UPLOAD) {
            photo.recordUploadAttempt();
            photo.markPendingFinalize(
                    auditExists ? CheckInPhoto.AuditUploadStatus.COMPLETED :
                            (photo.getAuditUploadStatus() == CheckInPhoto.AuditUploadStatus.NOT_REQUIRED
                                    ? CheckInPhoto.AuditUploadStatus.NOT_REQUIRED
                                    : CheckInPhoto.AuditUploadStatus.FAILED),
                    auditExists ? photo.getAuditStorageKey() : null,
                    photo.getAuditUploadStatus() == CheckInPhoto.AuditUploadStatus.PENDING
                            ? "Audit bucket object missing during reconciliation."
                            : photo.getLastUploadError()
            );
            photoRepository.save(photo);
        }

        CheckInPhoto finalized;
        try {
            finalized = finalizePendingUpload(photoId, photo.getUploadedBy().getId());
        } catch (PhotoUploadFinalizeException ex) {
            CheckInPhoto latestPhoto = photoRepository.findById(photoId)
                    .orElseThrow(() -> new ResourceNotFoundException("Fotografija nije pronađena"));
            if (ex.isTerminal()) {
                cleanupPartialStorage(latestPhoto.getStorageKey(), auditExists ? latestPhoto.getAuditStorageKey() : null);
            }
            eventService.recordSystemEvent(
                    latestPhoto.getBooking(),
                    latestPhoto.getCheckInSessionId(),
                    CheckInEventType.PHOTO_UPLOAD_STORAGE_FAILED,
                    Map.of(
                            "photoId", latestPhoto.getId(),
                            "photoType", latestPhoto.getPhotoType().name(),
                            "stage", ex.isTerminal() ? "FINALIZE_TERMINAL" : "FINALIZE_RETRYABLE",
                            "uploadStatus", latestPhoto.getUploadStatus().name(),
                            "retryable", !ex.isTerminal(),
                            "error", ex.getMessage()
                    )
            );
            return ex.isTerminal()
                    ? UploadReconciliationOutcome.TERMINAL_FAILURE
                    : UploadReconciliationOutcome.RETRYABLE_PENDING;
        }
        eventService.recordSystemEvent(
                finalized.getBooking(),
                finalized.getCheckInSessionId(),
                CheckInEventType.PHOTO_UPLOAD_RECOVERED,
                Map.of(
                        "photoId", finalized.getId(),
                        "photoType", finalized.getPhotoType().name(),
                        "recoveredBy", "SCHEDULER",
                        "previousStatus", photo.getUploadStatus().name()
                )
        );
                return UploadReconciliationOutcome.FINALIZED;
    }

    private String resolveSessionId(Booking booking, CheckInPhotoType photoType) {
        String sessionId = photoType.isCheckoutPhoto() ? booking.getCheckoutSessionId() : booking.getCheckInSessionId();
        if (sessionId != null) {
            return sessionId;
        }

        sessionId = UUID.randomUUID().toString();
        if (photoType.isCheckoutPhoto()) {
            booking.setCheckoutSessionId(sessionId);
            log.info("[CheckIn] Auto-generated missing checkoutSessionId for booking {}", booking.getId());
        } else {
            booking.setCheckInSessionId(sessionId);
            log.info("[CheckIn] Auto-generated missing checkInSessionId for booking {}", booking.getId());
        }
        bookingRepository.save(booking);
        return sessionId;
    }

    private String buildReservedStorageKey(Long bookingId,
                                           String party,
                                           CheckInPhotoType photoType,
                                           String uploadToken,
                                           String contentType,
                                           boolean auditBucket) {
        String extension = resolveExtension(contentType);
        String filename = auditBucket ? "audit_" + uploadToken + "." + extension : uploadToken + "." + extension;
        return String.format("bookings/%d/%s/%s/%s", bookingId, party.toLowerCase(), photoType.name(), filename);
    }

    private String resolveExtension(String contentType) {
        if (contentType == null) {
            return "jpg";
        }
        return switch (contentType.toLowerCase()) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/heic", "image/heif" -> "heic";
            default -> "jpg";
        };
    }

    private void cleanupPartialStorage(String standardStorageKey, String auditStorageKey) {
        if (standardStorageKey != null && !standardStorageKey.isBlank()) {
            supabaseStorageService.deleteCheckInPhoto(standardStorageKey);
        }
        if (auditStorageKey != null && !auditStorageKey.isBlank()) {
            supabaseStorageService.deleteCheckInAuditPhoto(auditStorageKey);
        }
    }

    // ========== SECURITY: File Signature Validation (D5 Fix) ==========

    /**
     * Validates file signature (magic bytes) to prevent malicious file uploads.
     * 
     * <p>This is a critical security measure because:
     * <ul>
     *   <li>Content-Type headers can be spoofed by malicious clients</li>
     *   <li>File extensions can be manipulated</li>
     *   <li>Magic bytes are embedded in the file and cannot be faked without corrupting the file</li>
     * </ul>
     * 
     * <p>Supported formats:
     * <ul>
     *   <li>JPEG: FF D8 FF (first 3 bytes)</li>
     *   <li>PNG: 89 50 4E 47 0D 0A 1A 0A (first 8 bytes)</li>
     *   <li>HEIC/HEIF: ftyp marker at offset 4 with heic/heix/mif1 brand</li>
     *   <li>WebP: RIFF....WEBP header</li>
     * </ul>
     * 
     * @param file the uploaded file to validate
     * @throws IllegalArgumentException if file signature doesn't match supported image formats
     * @throws IOException if file cannot be read
     */
    private void validateFileSignature(MultipartFile file) throws IOException {
        byte[] header = new byte[12];
        int bytesRead;

        try (var inputStream = file.getInputStream()) {
            bytesRead = inputStream.read(header);
        }

        if (bytesRead < 12) {
            throw new IllegalArgumentException("Datoteka je prekratka da bi bila validna slika");
        }

        // WI-7: Delegate to shared FileSignatureValidator utility
        try {
            org.example.rentoza.booking.util.FileSignatureValidator.validateImageSignature(
                    header, file.getContentType());
        } catch (IllegalArgumentException e) {
            log.warn("[Security] Invalid file signature detected. First 12 bytes: {}",
                bytesToHex(header));
            throw new IllegalArgumentException(
                "Nevalidna datoteka. Dozvoljen format: JPEG, PNG, HEIC, WebP");
        }
    }

    /**
     * Check for JPEG signature: FF D8 FF
     */
    private boolean isJpeg(byte[] header) {
        return header[0] == (byte) 0xFF && 
               header[1] == (byte) 0xD8 && 
               header[2] == (byte) 0xFF;
    }

    /**
     * Check for PNG signature: 89 50 4E 47 0D 0A 1A 0A
     */
    private boolean isPng(byte[] header) {
        return header[0] == (byte) 0x89 &&
               header[1] == (byte) 0x50 && // 'P'
               header[2] == (byte) 0x4E && // 'N'
               header[3] == (byte) 0x47 && // 'G'
               header[4] == (byte) 0x0D &&
               header[5] == (byte) 0x0A &&
               header[6] == (byte) 0x1A &&
               header[7] == (byte) 0x0A;
    }

    /**
     * Check for HEIC/HEIF signature: ftyp at offset 4 with brand heic, heix, or mif1
     * 
     * <p>HEIC uses ISO Base Media File Format (ISO/IEC 14496-12).
     * Structure: [4 bytes: box size][4 bytes: 'ftyp'][4 bytes: brand]
     */
    private boolean isHeic(byte[] header) {
        // Check for 'ftyp' at offset 4-7
        if (header[4] != 'f' || header[5] != 't' || 
            header[6] != 'y' || header[7] != 'p') {
            return false;
        }
        
        // Check brand at offset 8-11 (heic, heix, mif1, msf1)
        String brand = new String(header, 8, 4);
        return brand.equals("heic") || brand.equals("heix") || 
               brand.equals("mif1") || brand.equals("msf1") ||
               brand.equals("avif"); // Also support AVIF (AV1 Image)
    }

    /**
     * Check for WebP signature: RIFF....WEBP
     * Structure: RIFF[4 bytes size]WEBP
     */
    private boolean isWebP(byte[] header) {
        return header[0] == 'R' && header[1] == 'I' && 
               header[2] == 'F' && header[3] == 'F' &&
               header[8] == 'W' && header[9] == 'E' && 
               header[10] == 'B' && header[11] == 'P';
    }

    /**
     * Convert bytes to hex string for logging invalid signatures.
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    // ========== PHASE 4E: PHOTO DEADLINE EVIDENCE WEIGHT ==========

    /**
     * Determine evidence weight based on upload timing relative to trip events.
     * 
     * <p><b>Phase 4E Safety Improvement:</b> Photos uploaded after the deadline
     * are marked as SECONDARY evidence to reduce their weight in disputes.
     * 
     * <h3>Deadlines:</h3>
     * <ul>
     *   <li><b>Check-in photos:</b> Must be uploaded within N hours of trip start
     *       (deadline = tripStartedAt + checkinPhotoDeadlineHours)</li>
     *   <li><b>Checkout photos:</b> Must be uploaded within N hours of trip end
     *       (deadline = tripEndedAt + checkoutPhotoDeadlineHours)</li>
     * </ul>
     * 
     * <p>Photos uploaded during the check-in/checkout process (before trip start/end)
     * are always PRIMARY evidence.
     * 
     * @param booking The booking being photographed
     * @param photoType The type of photo being uploaded
     * @return EvidenceWeightResult with weight and reason if late
     */
    private EvidenceWeightResult determineEvidenceWeight(Booking booking, CheckInPhotoType photoType) {
        Instant now = Instant.now();
        
        if (photoType.isCheckoutPhoto()) {
            // Checkout photo deadline: tripEndedAt + checkoutPhotoDeadlineHours
            Instant tripEndedAt = booking.getTripEndedAt();
            
            if (tripEndedAt == null) {
                // Trip hasn't ended yet - photo is on time (during checkout process)
                return new EvidenceWeightResult(EvidenceWeight.PRIMARY, false, null);
            }
            
            Instant deadline = tripEndedAt.plus(checkoutPhotoDeadlineHours, java.time.temporal.ChronoUnit.HOURS);
            
            if (now.isAfter(deadline)) {
                long hoursLate = java.time.Duration.between(deadline, now).toHours();
                String reason = String.format(
                    "Fotografija otpremljena %d sati nakon roka (rok: %d sati od završetka putovanja)",
                    hoursLate, checkoutPhotoDeadlineHours
                );
                return new EvidenceWeightResult(EvidenceWeight.SECONDARY, true, reason);
            }
        } else {
            // Check-in photo deadline: tripStartedAt + checkinPhotoDeadlineHours
            Instant tripStartedAt = booking.getTripStartedAt();
            
            if (tripStartedAt == null) {
                // Trip hasn't started yet - photo is on time (during check-in process)
                return new EvidenceWeightResult(EvidenceWeight.PRIMARY, false, null);
            }
            
            Instant deadline = tripStartedAt.plus(checkinPhotoDeadlineHours, java.time.temporal.ChronoUnit.HOURS);
            
            if (now.isAfter(deadline)) {
                long hoursLate = java.time.Duration.between(deadline, now).toHours();
                String reason = String.format(
                    "Fotografija otpremljena %d sati nakon roka (rok: %d sati od početka putovanja)",
                    hoursLate, checkinPhotoDeadlineHours
                );
                return new EvidenceWeightResult(EvidenceWeight.SECONDARY, true, reason);
            }
        }
        
        return new EvidenceWeightResult(EvidenceWeight.PRIMARY, false, null);
    }
    
    /**
     * Result record for evidence weight determination.
     * 
     * @param weight The determined evidence weight
     * @param isLate Whether the upload was past the deadline
     * @param reason Reason for SECONDARY weight (null if PRIMARY)
     */
    private record EvidenceWeightResult(EvidenceWeight weight, boolean isLate, String reason) {}

    // ========== R1/R2: SHA-256 HASH COMPUTATION ==========

    /**
     * Compute SHA-256 hash of image bytes for duplicate detection and chain-of-custody.
     * Returns lowercase hex string (64 characters), or null on failure.
     *
     * <p>Mirrors the implementation in {@code HostCheckoutPhotoService.computeSha256()}
     * for cross-stage hash consistency.
     *
     * @param data Raw image bytes (original, pre-EXIF-strip)
     * @return Lowercase hex SHA-256 hash, or null if hashing fails
     * @since R1 - Trust & evidence audit remediation
     */
    private String computeSha256(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            log.error("[R1] SHA-256 not available for image hash computation", e);
            return null;
        }
    }
}

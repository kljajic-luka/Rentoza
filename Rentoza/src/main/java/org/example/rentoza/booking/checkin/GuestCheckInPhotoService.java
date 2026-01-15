package org.example.rentoza.booking.checkin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.ExifValidationService.ExifValidationResult;
import org.example.rentoza.booking.checkin.dto.*;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.example.rentoza.storage.SupabaseStorageService;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for handling guest check-in photo uploads (dual-party verification).
 * 
 * <p>When the guest arrives for pickup, they capture photos of the vehicle
 * to confirm the condition matches what the host documented. This creates
 * bilateral photographic evidence for any disputes.
 * 
 * <h2>Business Rules</h2>
 * <ul>
 *   <li>Guest can only upload photos when booking status is CHECK_IN_HOST_COMPLETE</li>
 *   <li>Guest must upload the same 8 required photo types as host</li>
 *   <li>Photos are validated with same EXIF rules as host photos</li>
 *   <li>System automatically detects discrepancies between host/guest photos</li>
 *   <li>Sequence validation ensures all required photos are captured</li>
 * </ul>
 * 
 * @see GuestCheckInPhoto
 * @see PhotoDiscrepancy
 * @see PhotoGuidanceService
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GuestCheckInPhotoService {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");
    private static final int REQUIRED_PHOTO_COUNT = 8;

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final GuestCheckInPhotoRepository guestPhotoRepository;
    private final CheckInPhotoRepository hostPhotoRepository;
    private final PhotoDiscrepancyRepository discrepancyRepository;
    private final CheckInEventService eventService;
    private final ExifValidationService exifValidationService;
    private final PhotoRejectionService photoRejectionService;
    private final PhotoGuidanceService photoGuidanceService;
    private final SupabaseStorageService supabaseStorageService;

    @Value("${storage.mode:local}")
    private String storageMode;

    @Value("${app.checkin.photo.upload-dir:uploads/checkin}")
    private String uploadDir;

    @Value("${app.checkin.photo.max-size-mb:3}")
    private int maxSizeMb;

    @Value("${app.checkin.photo.max-width-pixels:2560}")
    private int maxWidthPixels;

    @Value("${app.checkin.photo.max-height-pixels:2560}")
    private int maxHeightPixels;

    /**
     * Process batch upload of guest check-in photos.
     * 
     * @param bookingId ID of the booking
     * @param userId    ID of the guest user
     * @param submission DTO containing photos and metadata
     * @return response with processed photos and any detected discrepancies
     */
    @Transactional
    public GuestCheckInPhotoResponseDTO uploadGuestPhotos(
            Long bookingId,
            Long userId,
            GuestCheckInPhotoSubmissionDTO submission) {
        
        // Validate booking exists and get it with relations
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        // Validate user is the renter
        if (!booking.getRenter().getId().equals(userId)) {
            throw new AccessDeniedException("Samo gost može otpremiti fotografije za prijem");
        }
        
        // Validate booking status - guest can only upload after host completes
        if (booking.getStatus() != BookingStatus.CHECK_IN_HOST_COMPLETE &&
            booking.getStatus() != BookingStatus.CHECK_IN_OPEN) {
            throw new IllegalStateException(
                "Prijem nije spreman za otpremanje fotografija od strane gosta. " +
                "Trenutni status: " + booking.getStatus());
        }
        
        // Get user
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Korisnik nije pronađen"));
        
        String sessionId = booking.getCheckInSessionId();
        if (sessionId == null) {
            sessionId = UUID.randomUUID().toString();
            booking.setCheckInSessionId(sessionId);
            bookingRepository.save(booking);
        }
        
        // PHASE 1 IMPROVEMENT: Validate photo types at upload time
        // Prevents duplicate types in same submission and re-uploads of existing types
        validatePhotoTypesAtUpload(bookingId, submission.getPhotos());
        
        List<CheckInPhotoDTO> processedPhotos = new ArrayList<>();
        int acceptedCount = 0;
        int rejectedCount = 0;
        List<GuestCheckInPhotoResponseDTO.PhotoDiscrepancySummaryDTO> discrepancies = new ArrayList<>();
        
        // Process each photo
        for (GuestCheckInPhotoSubmissionDTO.PhotoItem photoItem : submission.getPhotos()) {
            try {
                CheckInPhotoDTO result = processGuestPhoto(
                    booking, user, sessionId, photoItem, submission.getClientCapturedAt()
                );
                processedPhotos.add(result);
                
                if (result.isAccepted()) {
                    acceptedCount++;
                    
                    // Check for discrepancy with host photo
                    GuestCheckInPhotoResponseDTO.PhotoDiscrepancySummaryDTO discrepancy =
                        detectAndRecordDiscrepancy(booking, photoItem.getPhotoType(), result.getPhotoId());
                    if (discrepancy != null) {
                        discrepancies.add(discrepancy);
                    }
                } else {
                    rejectedCount++;
                }
            } catch (Exception e) {
                log.error("[GuestCheckIn] Failed to process photo type {}: {}",
                    photoItem.getPhotoType(), e.getMessage(), e);
                
                processedPhotos.add(CheckInPhotoDTO.builder()
                    .photoType(photoItem.getPhotoType())
                    .accepted(false)
                    .rejectionReason("Greška pri obradi: " + e.getMessage())
                    .build());
                rejectedCount++;
            }
        }
        
        // Check completion status
        long validPhotoTypes = guestPhotoRepository.countRequiredGuestPhotoTypes(bookingId);
        boolean complete = validPhotoTypes >= REQUIRED_PHOTO_COUNT;
        
        // Calculate missing photos
        List<String> missingTypes = getMissingPhotoTypes(bookingId);
        
        // Update booking with guest photo count
        booking.setGuestCheckinPhotoCount(acceptedCount);
        if (complete) {
            booking.setGuestCheckinPhotosCompletedAt(Instant.now());
            
            // Record completion event
            eventService.recordEvent(
                booking,
                sessionId,
                CheckInEventType.GUEST_CHECK_IN_PHOTOS_COMPLETE,
                userId,
                CheckInActorRole.GUEST,
                Instant.now(),
                Map.of(
                    "photoCount", acceptedCount,
                    "validPhotoCount", validPhotoTypes,
                    "discrepancyCount", discrepancies.size()
                )
            );
        }
        bookingRepository.save(booking);
        
        // Build response
        GuestCheckInPhotoResponseDTO response = GuestCheckInPhotoResponseDTO.builder()
            .success(acceptedCount > 0)
            .httpStatus(acceptedCount > 0 ? 201 : 400)
            .userMessage(buildUserMessage(acceptedCount, rejectedCount, complete))
            .processedPhotos(processedPhotos)
            .acceptedCount(acceptedCount)
            .rejectedCount(rejectedCount)
            .allRequiredPhotosSubmitted(submission.getPhotos().size() >= REQUIRED_PHOTO_COUNT)
            .guestPhotosComplete(complete)
            .missingRequiredCount(REQUIRED_PHOTO_COUNT - (int) validPhotoTypes)
            .missingPhotoTypes(missingTypes)
            .detectedDiscrepancies(discrepancies.isEmpty() ? null : discrepancies)
            .processedAt(Instant.now())
            .sessionId(sessionId)
            .build();
        
        log.info("[GuestCheckIn] Photos processed: booking={}, accepted={}, rejected={}, complete={}",
            bookingId, acceptedCount, rejectedCount, complete);
        
        return response;
    }

    /**
     * Process a single guest photo.
     */
    private CheckInPhotoDTO processGuestPhoto(
            Booking booking,
            User user,
            String sessionId,
            GuestCheckInPhotoSubmissionDTO.PhotoItem photoItem,
            Instant clientTimestamp) throws IOException {
        
        // Validate photo type is a guest type
        if (!photoItem.getPhotoType().isGuestCheckInPhoto() && 
            !photoItem.getPhotoType().isRequiredForGuestCheckIn()) {
            throw new IllegalArgumentException(
                "Nevažeći tip fotografije za gosta: " + photoItem.getPhotoType());
        }
        
        // Decode base64
        byte[] photoBytes = Base64.getDecoder().decode(photoItem.getBase64Data());
        
        // Validate size
        long maxBytes = maxSizeMb * 1024L * 1024L;
        if (photoBytes.length > maxBytes) {
            throw new IllegalArgumentException(
                String.format("Fotografija je prevelika. Maksimum: %dMB", maxSizeMb));
        }
        
        // Validate image dimensions (prevents memory exhaustion on mobile)
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(photoBytes));
            if (img == null) {
                throw new IllegalArgumentException("Nije moguće pročitati fotografiju. Proverite format slike.");
            }
            if (img.getWidth() > maxWidthPixels || img.getHeight() > maxHeightPixels) {
                throw new IllegalArgumentException(
                    String.format("Rezolucija fotografije je prevelika (%dx%d). Maksimum: %dx%d piksela. " +
                        "Molimo smanjite veličinu slike ili omogućite kompresiju u aplikaciji.",
                        img.getWidth(), img.getHeight(), maxWidthPixels, maxHeightPixels));
            }
            log.debug("[GuestCheckIn] Image dimensions validated: {}x{}", img.getWidth(), img.getHeight());
        } catch (IOException e) {
            log.warn("[GuestCheckIn] Failed to read image dimensions: {}", e.getMessage());
            // Continue processing - EXIF validation will catch corrupt images
        }
        
        // EXIF validation
        ExifValidationResult exifResult = exifValidationService.validate(
            photoBytes,
            clientTimestamp != null ? clientTimestamp : photoItem.getCapturedAt()
        );
        
        // Check for rejection
        if (photoRejectionService.shouldReject(exifResult.getStatus())) {
            return handleRejectedGuestPhoto(booking, user.getId(), photoItem.getPhotoType(), 
                exifResult, photoBytes.length, clientTimestamp);
        }
        
        // Store accepted photo
        return handleAcceptedGuestPhoto(booking, user, sessionId, photoItem, 
            photoBytes, exifResult, clientTimestamp);
    }

    /**
     * Handle rejected guest photo - log event but don't store.
     */
    private CheckInPhotoDTO handleRejectedGuestPhoto(
            Booking booking,
            Long userId,
            CheckInPhotoType photoType,
            ExifValidationResult exifResult,
            long fileSize,
            Instant clientTimestamp) {
        
        PhotoRejectionInfo rejectionInfo = photoRejectionService.getRejectionInfo(exifResult.getStatus());
        
        // Log rejection event
        eventService.recordEvent(
            booking,
            booking.getCheckInSessionId(),
            CheckInEventType.GUEST_CHECK_IN_PHOTO_REJECTED,
            userId,
            CheckInActorRole.GUEST,
            clientTimestamp,
            photoRejectionService.createRejectionEventMetadata(
                exifResult.getStatus(), photoType, fileSize
            )
        );
        
        log.info("[GuestCheckIn] Photo REJECTED: booking={}, type={}, status={}",
            booking.getId(), photoType, exifResult.getStatus());
        
        return CheckInPhotoDTO.builder()
            .photoType(photoType)
            .exifValidationStatus(exifResult.getStatus())
            .exifValidationMessage(exifResult.getMessage())
            .accepted(false)
            .rejectionReason(rejectionInfo != null ? rejectionInfo.getRejectionReason() : "Fotografija nije prihvaćena.")
            .remediationHint(rejectionInfo != null ? rejectionInfo.getRemediationHint() : "Pokušajte ponovo sa novom fotografijom.")
            .build();
    }

    /**
     * Handle accepted guest photo - store to filesystem and database.
     */
    private CheckInPhotoDTO handleAcceptedGuestPhoto(
            Booking booking,
            User user,
            String sessionId,
            GuestCheckInPhotoSubmissionDTO.PhotoItem photoItem,
            byte[] photoBytes,
            ExifValidationResult exifResult,
            Instant clientTimestamp) throws IOException {
        
        // Generate storage path
        String filename = String.format("%s_%s_%s.jpg",
            photoItem.getPhotoType().name().toLowerCase(),
            System.currentTimeMillis(),
            UUID.randomUUID().toString().substring(0, 8)
        );
        String storageKey = String.format("guest-checkin/%s/%s", sessionId, filename);
        
        // ========== STORAGE MODE: Supabase or Local Filesystem ==========
        String contentType = photoItem.getMimeType() != null ? photoItem.getMimeType() : "image/jpeg";
        
        if ("supabase".equalsIgnoreCase(storageMode)) {
            // Upload to Supabase Storage
            try {
                storageKey = supabaseStorageService.uploadCheckInPhotoBytes(
                    booking.getId(),
                    "guest",
                    photoItem.getPhotoType().name(),
                    photoBytes,
                    contentType
                );
                log.info("[GuestCheckIn] Photo uploaded to Supabase: booking={}, type={}, key={}",
                    booking.getId(), photoItem.getPhotoType(), storageKey);
            } catch (IOException e) {
                log.error("[GuestCheckIn] Supabase upload failed for booking {}: {}",
                    booking.getId(), e.getMessage(), e);
                throw e;
            }
        } else {
            // Legacy: Local filesystem storage (deprecated, for migration only)
            log.warn("[GuestCheckIn] Using LOCAL filesystem storage (deprecated): storage.mode={}", storageMode);
            Path uploadPath = Paths.get(uploadDir.replace("checkin", "guest-checkin"), sessionId);
            Files.createDirectories(uploadPath);
            Path filePath = uploadPath.resolve(filename);
            Files.write(filePath, photoBytes);
        }
        
        // Create entity
        GuestCheckInPhoto photo = GuestCheckInPhoto.builder()
            .booking(booking)
            .checkInSessionId(sessionId)
            .photoType(photoItem.getPhotoType())
            .storageBucket(GuestCheckInPhoto.StorageBucket.CHECKIN_STANDARD)
            .storageKey(storageKey)
            .originalFilename(photoItem.getOriginalFilename())
            .mimeType(photoItem.getMimeType() != null ? photoItem.getMimeType() : "image/jpeg")
            .fileSizeBytes(photoBytes.length)
            .exifTimestamp(exifResult.getPhotoTimestamp())
            .exifLatitude(exifResult.getLatitude())
            .exifLongitude(exifResult.getLongitude())
            .exifDeviceMake(exifResult.getDeviceMake())
            .exifDeviceModel(exifResult.getDeviceModel())
            .exifValidationStatus(exifResult.getStatus())
            .exifValidationMessage(exifResult.getMessage())
            .exifValidatedAt(Instant.now())
            .uploadedBy(user)
            .clientUploadedAt(clientTimestamp)
            .build();
        
        photo = guestPhotoRepository.save(photo);
        
        // Record event
        eventService.recordEvent(
            booking,
            sessionId,
            CheckInEventType.GUEST_CHECK_IN_PHOTO_UPLOADED,
            user.getId(),
            CheckInActorRole.GUEST,
            clientTimestamp,
            Map.of(
                "photoId", photo.getId(),
                "photoType", photoItem.getPhotoType().name(),
                "exifValid", exifResult.isAccepted(),
                "exifStatus", exifResult.getStatus().name(),
                "fileSize", photoBytes.length
            )
        );
        
        log.info("[GuestCheckIn] Photo ACCEPTED: booking={}, type={}, photoId={}",
            booking.getId(), photoItem.getPhotoType(), photo.getId());
        
        // Build response DTO
        return CheckInPhotoDTO.builder()
            .photoId(photo.getId())
            .photoType(photo.getPhotoType())
            .url(storageKey)
            .uploadedAt(LocalDateTime.ofInstant(photo.getUploadedAt(), SERBIA_ZONE))
            .exifValidationStatus(photo.getExifValidationStatus())
            .exifValidationMessage(photo.getExifValidationMessage())
            .mimeType(photo.getMimeType())
            .exifTimestamp(photo.getExifTimestamp() != null 
                ? LocalDateTime.ofInstant(photo.getExifTimestamp(), SERBIA_ZONE) : null)
            .exifLatitude(photo.getExifLatitude() != null 
                ? photo.getExifLatitude().doubleValue() : null)
            .exifLongitude(photo.getExifLongitude() != null 
                ? photo.getExifLongitude().doubleValue() : null)
            .deviceModel(photo.getExifDeviceModel())
            .accepted(true)
            .build();
    }

    /**
     * Detect and record any discrepancy between guest and host photos.
     * 
     * @return discrepancy summary if found, null otherwise
     */
    private GuestCheckInPhotoResponseDTO.PhotoDiscrepancySummaryDTO detectAndRecordDiscrepancy(
            Booking booking,
            CheckInPhotoType guestPhotoType,
            Long guestPhotoId) {
        
        // Find corresponding host photo type
        CheckInPhotoType hostPhotoType = guestPhotoType.getCorrespondingHostType();
        if (hostPhotoType == null) {
            return null;
        }
        
        // Find host photo for this type
        List<CheckInPhoto> hostPhotos = hostPhotoRepository.findByBookingIdAndPhotoType(
            booking.getId(), hostPhotoType);
        
        if (hostPhotos.isEmpty()) {
            // No host photo to compare - this is itself a discrepancy
            PhotoDiscrepancy discrepancy = PhotoDiscrepancy.builder()
                .booking(booking)
                .discrepancyType(PhotoDiscrepancy.DiscrepancyType.CHECK_IN)
                .guestPhotoId(guestPhotoId)
                .photoType(guestPhotoType)
                .description("Gost je otpremio fotografiju za koju domaćin nema odgovarajuću fotografiju: " + guestPhotoType.name())
                .severity(PhotoDiscrepancy.Severity.LOW)
                .build();
            
            discrepancy = discrepancyRepository.save(discrepancy);
            
            eventService.recordEvent(
                booking,
                booking.getCheckInSessionId(),
                CheckInEventType.PHOTO_DISCREPANCY_DETECTED,
                null,
                CheckInActorRole.SYSTEM,
                Instant.now(),
                Map.of(
                    "discrepancyId", discrepancy.getId(),
                    "photoType", guestPhotoType.name(),
                    "severity", discrepancy.getSeverity().name(),
                    "reason", "MISSING_HOST_PHOTO"
                )
            );
            
            return GuestCheckInPhotoResponseDTO.PhotoDiscrepancySummaryDTO.builder()
                .discrepancyId(discrepancy.getId())
                .photoType(guestPhotoType.name())
                .severity(discrepancy.getSeverity().name())
                .description(discrepancy.getDescription())
                .blocksHandover(false)
                .build();
        }
        
        // For now, we just log that both photos exist
        // Future enhancement: AI comparison for damage detection
        log.debug("[GuestCheckIn] Both host and guest photos present for type {}", guestPhotoType);
        
        return null;
    }

    /**
     * Get list of required photo types that are still missing.
     */
    private List<String> getMissingPhotoTypes(Long bookingId) {
        List<GuestCheckInPhoto> existingPhotos = guestPhotoRepository.findRequiredGuestPhotos(bookingId);
        Set<CheckInPhotoType> existingTypes = existingPhotos.stream()
            .filter(p -> p.isExifValid())
            .map(GuestCheckInPhoto::getPhotoType)
            .collect(Collectors.toSet());
        
        return Arrays.stream(CheckInPhotoType.getRequiredGuestCheckInTypes())
            .filter(type -> !existingTypes.contains(type))
            .map(Enum::name)
            .collect(Collectors.toList());
    }

    /**
     * Build user-friendly message based on results.
     */
    private String buildUserMessage(int accepted, int rejected, boolean complete) {
        if (complete) {
            return "Sve obavezne fotografije su uspešno sačuvane. Možete nastaviti sa preuzimanjem vozila.";
        } else if (accepted > 0 && rejected > 0) {
            return String.format("Prihvaćeno %d fotografija, odbijeno %d. Molimo dodajte preostale fotografije.", 
                accepted, rejected);
        } else if (accepted > 0) {
            return String.format("Prihvaćeno %d fotografija. Molimo dodajte preostale obavezne fotografije.", accepted);
        } else {
            return "Nijedna fotografija nije prihvaćena. Proverite da li fotografišete direktno sa kamere.";
        }
    }

    /**
     * PHASE 1 IMPROVEMENT: Validate photo types at upload time.
     * 
     * <p>Prevents two issues:
     * <ol>
     *   <li>Duplicate photo types in the same submission (e.g., 8x FRONT)</li>
     *   <li>Re-upload of photo types that already exist for this booking</li>
     * </ol>
     * 
     * @param bookingId the booking ID
     * @param photos list of photos being submitted
     * @throws IllegalArgumentException if validation fails
     */
    private void validatePhotoTypesAtUpload(Long bookingId, List<GuestCheckInPhotoSubmissionDTO.PhotoItem> photos) {
        if (photos == null || photos.isEmpty()) {
            return;
        }
        
        // Check for duplicate types within this submission
        Set<CheckInPhotoType> submittedTypes = new HashSet<>();
        List<CheckInPhotoType> duplicates = new ArrayList<>();
        
        for (GuestCheckInPhotoSubmissionDTO.PhotoItem photo : photos) {
            if (!submittedTypes.add(photo.getPhotoType())) {
                duplicates.add(photo.getPhotoType());
            }
        }
        
        if (!duplicates.isEmpty()) {
            String duplicateNames = duplicates.stream()
                .map(CheckInPhotoType::name)
                .distinct()
                .collect(Collectors.joining(", "));
            throw new IllegalArgumentException(
                String.format("Otkrivene su duplirane vrste fotografija u ovom zahtevu: %s. " +
                    "Svaki tip fotografije može biti poslat samo jednom.", duplicateNames));
        }
        
        // Check for photo types that already exist for this booking
        List<String> alreadyUploaded = new ArrayList<>();
        for (CheckInPhotoType type : submittedTypes) {
            long existingCount = guestPhotoRepository.countByBookingIdAndPhotoType(bookingId, type);
            if (existingCount > 0) {
                alreadyUploaded.add(type.name());
            }
        }
        
        if (!alreadyUploaded.isEmpty()) {
            String existingTypes = String.join(", ", alreadyUploaded);
            log.warn("[GuestCheckIn] Attempt to re-upload existing photo types: bookingId={}, types={}", 
                bookingId, existingTypes);
            throw new IllegalArgumentException(
                String.format("Sledeće vrste fotografija su već otpremljene za ovu rezervaciju: %s. " +
                    "Koristite opciju zamene ako želite da promenite postojeću fotografiju.", existingTypes));
        }
        
        log.debug("[GuestCheckIn] Photo types validated for upload: bookingId={}, types={}", 
            bookingId, submittedTypes.stream().map(Enum::name).collect(Collectors.joining(", ")));
    }

    /**
     * Get all guest check-in photos for a booking.
     */
    @Transactional(readOnly = true)
    public List<CheckInPhotoDTO> getGuestPhotos(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        // Allow access to both host and guest
        boolean isHost = booking.getCar().getOwner().getId().equals(userId);
        boolean isGuest = booking.getRenter().getId().equals(userId);
        
        if (!isHost && !isGuest) {
            throw new AccessDeniedException("Nemate pristup ovim fotografijama");
        }
        
        return guestPhotoRepository.findByBookingId(bookingId).stream()
            .filter(photo -> !photo.isDeleted())
            .map(this::toDTO)
            .collect(Collectors.toList());
    }

    private CheckInPhotoDTO toDTO(GuestCheckInPhoto photo) {
        return CheckInPhotoDTO.builder()
            .photoId(photo.getId())
            .photoType(photo.getPhotoType())
            .url(photo.getStorageKey())
            .uploadedAt(LocalDateTime.ofInstant(photo.getUploadedAt(), SERBIA_ZONE))
            .exifValidationStatus(photo.getExifValidationStatus())
            .exifValidationMessage(photo.getExifValidationMessage())
            .mimeType(photo.getMimeType())
            .width(photo.getImageWidth())
            .height(photo.getImageHeight())
            .exifTimestamp(photo.getExifTimestamp() != null 
                ? LocalDateTime.ofInstant(photo.getExifTimestamp(), SERBIA_ZONE) : null)
            .exifLatitude(photo.getExifLatitude() != null 
                ? photo.getExifLatitude().doubleValue() : null)
            .exifLongitude(photo.getExifLongitude() != null 
                ? photo.getExifLongitude().doubleValue() : null)
            .deviceModel(photo.getExifDeviceModel())
            .accepted(true)
            .build();
    }
}

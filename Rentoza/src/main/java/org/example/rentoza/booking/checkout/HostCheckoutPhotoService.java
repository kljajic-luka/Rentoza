package org.example.rentoza.booking.checkout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.*;
import org.example.rentoza.booking.checkin.ExifValidationService.ExifValidationResult;
import org.example.rentoza.booking.checkin.dto.CheckInPhotoDTO;
import org.example.rentoza.booking.checkin.dto.PhotoRejectionInfo;
import org.example.rentoza.booking.checkout.dto.HostCheckoutPhotoResponseDTO;
import org.example.rentoza.booking.checkout.dto.HostCheckoutPhotoSubmissionDTO;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for handling host checkout photo uploads (dual-party verification).
 * 
 * <p>When the vehicle is returned, the host captures photos to verify
 * the return condition matches what the guest documented. This creates
 * bilateral photographic evidence for any damage disputes.
 * 
 * <h2>Business Rules</h2>
 * <ul>
 *   <li>Host can only upload photos when booking status is CHECKOUT_GUEST_COMPLETE</li>
 *   <li>Host must upload the same 8 required photo types as guest</li>
 *   <li>Photos are validated with same EXIF rules</li>
 *   <li>System automatically detects discrepancies between checkout photos</li>
 *   <li>System can compare check-in vs checkout for damage detection</li>
 * </ul>
 * 
 * @see HostCheckoutPhoto
 * @see PhotoDiscrepancy
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HostCheckoutPhotoService {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");
    private static final int REQUIRED_PHOTO_COUNT = 8;

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final HostCheckoutPhotoRepository hostCheckoutPhotoRepository;
    private final CheckInPhotoRepository checkInPhotoRepository;
    private final PhotoDiscrepancyRepository discrepancyRepository;
    private final CheckInEventService eventService;
    private final ExifValidationService exifValidationService;
    private final PhotoRejectionService photoRejectionService;

    @Value("${app.checkout.photo.upload-dir:uploads/checkout}")
    private String uploadDir;

    @Value("${app.checkout.photo.max-size-mb:10}")
    private int maxSizeMb;

    /**
     * Process batch upload of host checkout photos.
     * 
     * @param bookingId ID of the booking
     * @param userId    ID of the host user
     * @param submission DTO containing photos and metadata
     * @return response with processed photos, discrepancies, and condition changes
     */
    @Transactional
    public HostCheckoutPhotoResponseDTO uploadHostCheckoutPhotos(
            Long bookingId,
            Long userId,
            HostCheckoutPhotoSubmissionDTO submission) {
        
        // Validate booking exists and get it with relations
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        // Validate user is the owner (host)
        if (!booking.getCar().getOwner().getId().equals(userId)) {
            throw new AccessDeniedException("Samo vlasnik vozila može otpremiti fotografije za povratak");
        }
        
        // Validate booking status - host can only upload after guest completes checkout
        if (booking.getStatus() != BookingStatus.CHECKOUT_GUEST_COMPLETE) {
            throw new IllegalStateException(
                "Povratak nije spreman za fotografije od strane domaćina. " +
                "Trenutni status: " + booking.getStatus());
        }
        
        // Get user
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Korisnik nije pronađen"));
        
        String sessionId = booking.getCheckoutSessionId();
        if (sessionId == null) {
            sessionId = UUID.randomUUID().toString();
            booking.setCheckoutSessionId(sessionId);
            bookingRepository.save(booking);
        }
        
        List<CheckInPhotoDTO> processedPhotos = new ArrayList<>();
        int acceptedCount = 0;
        int rejectedCount = 0;
        List<HostCheckoutPhotoResponseDTO.PhotoDiscrepancySummaryDTO> checkoutDiscrepancies = new ArrayList<>();
        List<HostCheckoutPhotoResponseDTO.ConditionComparisonDTO> conditionChanges = new ArrayList<>();
        boolean newDamageDetected = submission.getNewDamageReported() != null && submission.getNewDamageReported();
        
        // Process each photo
        for (HostCheckoutPhotoSubmissionDTO.PhotoItem photoItem : submission.getPhotos()) {
            try {
                CheckInPhotoDTO result = processHostCheckoutPhoto(
                    booking, user, sessionId, photoItem, submission.getClientCapturedAt()
                );
                processedPhotos.add(result);
                
                if (result.isAccepted()) {
                    acceptedCount++;
                    
                    // Check for discrepancy with guest checkout photo
                    HostCheckoutPhotoResponseDTO.PhotoDiscrepancySummaryDTO discrepancy =
                        detectCheckoutDiscrepancy(booking, photoItem.getPhotoType(), result.getPhotoId());
                    if (discrepancy != null) {
                        checkoutDiscrepancies.add(discrepancy);
                    }
                    
                    // Compare with check-in photo for condition changes
                    HostCheckoutPhotoResponseDTO.ConditionComparisonDTO comparison =
                        compareWithCheckIn(booking, photoItem.getPhotoType());
                    if (comparison != null && comparison.isChangeDetected()) {
                        conditionChanges.add(comparison);
                        newDamageDetected = true;
                    }
                } else {
                    rejectedCount++;
                }
            } catch (Exception e) {
                log.error("[HostCheckout] Failed to process photo type {}: {}",
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
        long validPhotoTypes = hostCheckoutPhotoRepository.countRequiredHostCheckoutPhotoTypes(bookingId);
        boolean complete = validPhotoTypes >= REQUIRED_PHOTO_COUNT;
        
        // Calculate missing photos
        List<String> missingTypes = getMissingPhotoTypes(bookingId);
        
        // Update booking
        booking.setHostCheckoutPhotoCount(acceptedCount);
        booking.setCheckoutDiscrepancyCount(checkoutDiscrepancies.size());
        if (complete) {
            booking.setHostCheckoutPhotosCompletedAt(Instant.now());
            
            // Record completion event
            eventService.recordEvent(
                booking,
                sessionId,
                CheckInEventType.HOST_CHECKOUT_PHOTOS_COMPLETE,
                userId,
                CheckInActorRole.HOST,
                Instant.now(),
                Map.of(
                    "photoCount", acceptedCount,
                    "validPhotoCount", validPhotoTypes,
                    "discrepancyCount", checkoutDiscrepancies.size(),
                    "conditionChangesCount", conditionChanges.size(),
                    "newDamageDetected", newDamageDetected
                )
            );
        }
        
        // Handle damage reporting
        if (newDamageDetected && submission.getDamageDescription() != null) {
            eventService.recordEvent(
                booking,
                sessionId,
                CheckInEventType.CHECKOUT_HOST_DAMAGE_REPORTED,
                userId,
                CheckInActorRole.HOST,
                Instant.now(),
                Map.of(
                    "damageDescription", submission.getDamageDescription(),
                    "estimatedCostRsd", submission.getEstimatedDamageCostRsd() != null 
                        ? submission.getEstimatedDamageCostRsd() : 0,
                    "conditionAccepted", submission.getConditionAccepted() != null 
                        ? submission.getConditionAccepted() : false
                )
            );
        }
        
        bookingRepository.save(booking);
        
        // Build response
        HostCheckoutPhotoResponseDTO response = HostCheckoutPhotoResponseDTO.builder()
            .success(acceptedCount > 0)
            .httpStatus(acceptedCount > 0 ? 201 : 400)
            .userMessage(buildUserMessage(acceptedCount, rejectedCount, complete, newDamageDetected))
            .processedPhotos(processedPhotos)
            .acceptedCount(acceptedCount)
            .rejectedCount(rejectedCount)
            .allRequiredPhotosSubmitted(submission.getPhotos().size() >= REQUIRED_PHOTO_COUNT)
            .hostCheckoutPhotosComplete(complete)
            .missingRequiredCount(REQUIRED_PHOTO_COUNT - (int) validPhotoTypes)
            .missingPhotoTypes(missingTypes)
            .checkoutDiscrepancies(checkoutDiscrepancies.isEmpty() ? null : checkoutDiscrepancies)
            .conditionChanges(conditionChanges.isEmpty() ? null : conditionChanges)
            .newDamageDetected(newDamageDetected)
            .processedAt(Instant.now())
            .sessionId(sessionId)
            .build();
        
        log.info("[HostCheckout] Photos processed: booking={}, accepted={}, rejected={}, complete={}, damage={}",
            bookingId, acceptedCount, rejectedCount, complete, newDamageDetected);
        
        return response;
    }

    /**
     * Process a single host checkout photo.
     */
    private CheckInPhotoDTO processHostCheckoutPhoto(
            Booking booking,
            User user,
            String sessionId,
            HostCheckoutPhotoSubmissionDTO.PhotoItem photoItem,
            Instant clientTimestamp) throws IOException {
        
        // Validate photo type is a host checkout type
        if (!photoItem.getPhotoType().isHostCheckoutPhoto() && 
            !photoItem.getPhotoType().isRequiredForHostCheckout()) {
            throw new IllegalArgumentException(
                "Nevažeći tip fotografije za domaćina: " + photoItem.getPhotoType());
        }
        
        // Decode base64
        byte[] photoBytes = Base64.getDecoder().decode(photoItem.getBase64Data());
        
        // Validate size
        long maxBytes = maxSizeMb * 1024L * 1024L;
        if (photoBytes.length > maxBytes) {
            throw new IllegalArgumentException(
                String.format("Fotografija je prevelika. Maksimum: %dMB", maxSizeMb));
        }
        
        // EXIF validation
        ExifValidationResult exifResult = exifValidationService.validate(
            photoBytes,
            clientTimestamp != null ? clientTimestamp : photoItem.getCapturedAt()
        );
        
        // Check for rejection
        if (photoRejectionService.shouldReject(exifResult.getStatus())) {
            return handleRejectedPhoto(booking, user.getId(), photoItem.getPhotoType(), 
                exifResult, photoBytes.length, clientTimestamp);
        }
        
        // Store accepted photo
        return handleAcceptedPhoto(booking, user, sessionId, photoItem, 
            photoBytes, exifResult, clientTimestamp);
    }

    /**
     * Handle rejected photo - log event but don't store.
     */
    private CheckInPhotoDTO handleRejectedPhoto(
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
            booking.getCheckoutSessionId(),
            CheckInEventType.HOST_CHECKOUT_PHOTO_REJECTED,
            userId,
            CheckInActorRole.HOST,
            clientTimestamp,
            photoRejectionService.createRejectionEventMetadata(
                exifResult.getStatus(), photoType, fileSize
            )
        );
        
        log.info("[HostCheckout] Photo REJECTED: booking={}, type={}, status={}",
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
     * Handle accepted photo - store to filesystem and database.
     */
    private CheckInPhotoDTO handleAcceptedPhoto(
            Booking booking,
            User user,
            String sessionId,
            HostCheckoutPhotoSubmissionDTO.PhotoItem photoItem,
            byte[] photoBytes,
            ExifValidationResult exifResult,
            Instant clientTimestamp) throws IOException {
        
        // Generate storage path
        String filename = String.format("%s_%s_%s.jpg",
            photoItem.getPhotoType().name().toLowerCase(),
            System.currentTimeMillis(),
            UUID.randomUUID().toString().substring(0, 8)
        );
        String storageKey = String.format("host-checkout/%s/%s", sessionId, filename);
        
        // Ensure directory exists
        Path uploadPath = Paths.get(uploadDir.replace("checkout", "host-checkout"), sessionId);
        Files.createDirectories(uploadPath);
        
        // Save file
        Path filePath = uploadPath.resolve(filename);
        Files.write(filePath, photoBytes);
        
        // Create entity
        HostCheckoutPhoto photo = HostCheckoutPhoto.builder()
            .booking(booking)
            .checkoutSessionId(sessionId)
            .photoType(photoItem.getPhotoType())
            .storageBucket(HostCheckoutPhoto.StorageBucket.CHECKOUT_STANDARD)
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
        
        photo = hostCheckoutPhotoRepository.save(photo);
        
        // Record event
        eventService.recordEvent(
            booking,
            sessionId,
            CheckInEventType.HOST_CHECKOUT_PHOTO_UPLOADED,
            user.getId(),
            CheckInActorRole.HOST,
            clientTimestamp,
            Map.of(
                "photoId", photo.getId(),
                "photoType", photoItem.getPhotoType().name(),
                "exifValid", exifResult.isAccepted(),
                "exifStatus", exifResult.getStatus().name(),
                "fileSize", photoBytes.length,
                "isDamagePhoto", photoItem.getIsDamagePhoto() != null && photoItem.getIsDamagePhoto()
            )
        );
        
        log.info("[HostCheckout] Photo ACCEPTED: booking={}, type={}, photoId={}",
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
     * Detect discrepancy between host and guest checkout photos.
     */
    private HostCheckoutPhotoResponseDTO.PhotoDiscrepancySummaryDTO detectCheckoutDiscrepancy(
            Booking booking,
            CheckInPhotoType hostPhotoType,
            Long hostPhotoId) {
        
        // Find corresponding guest checkout photo type
        CheckInPhotoType guestPhotoType = hostPhotoType.getCorrespondingGuestCheckoutType();
        if (guestPhotoType == null) {
            return null;
        }
        
        // Find guest checkout photo for this type
        List<CheckInPhoto> guestPhotos = checkInPhotoRepository.findByBookingIdAndPhotoType(
            booking.getId(), guestPhotoType);
        
        if (guestPhotos.isEmpty()) {
            // No guest photo to compare - this is itself a discrepancy
            PhotoDiscrepancy discrepancy = PhotoDiscrepancy.builder()
                .booking(booking)
                .discrepancyType(PhotoDiscrepancy.DiscrepancyType.CHECK_OUT)
                .hostPhoto(null) // Will be set later if needed
                .guestPhotoId(null)
                .photoType(hostPhotoType)
                .description("Domaćin je otpremio fotografiju povratka za koju gost nema odgovarajuću fotografiju: " + hostPhotoType.name())
                .severity(PhotoDiscrepancy.Severity.MEDIUM)
                .build();
            
            discrepancy = discrepancyRepository.save(discrepancy);
            
            eventService.recordEvent(
                booking,
                booking.getCheckoutSessionId(),
                CheckInEventType.PHOTO_DISCREPANCY_DETECTED,
                null,
                CheckInActorRole.SYSTEM,
                Instant.now(),
                Map.of(
                    "discrepancyId", discrepancy.getId(),
                    "photoType", hostPhotoType.name(),
                    "severity", discrepancy.getSeverity().name(),
                    "reason", "MISSING_GUEST_CHECKOUT_PHOTO"
                )
            );
            
            return HostCheckoutPhotoResponseDTO.PhotoDiscrepancySummaryDTO.builder()
                .discrepancyId(discrepancy.getId())
                .photoType(hostPhotoType.name())
                .severity(discrepancy.getSeverity().name())
                .description(discrepancy.getDescription())
                .blocksCheckout(false)
                .build();
        }
        
        return null;
    }

    /**
     * Compare checkout photo with check-in photo to detect condition changes.
     */
    private HostCheckoutPhotoResponseDTO.ConditionComparisonDTO compareWithCheckIn(
            Booking booking,
            CheckInPhotoType checkoutPhotoType) {
        
        // Map checkout photo type to check-in photo type
        String checkoutTypeName = checkoutPhotoType.name();
        String checkInTypeName = null;
        
        if (checkoutTypeName.startsWith("HOST_CHECKOUT_")) {
            // HOST_CHECKOUT_EXTERIOR_FRONT -> HOST_EXTERIOR_FRONT
            checkInTypeName = checkoutTypeName.replace("HOST_CHECKOUT_", "HOST_");
        }
        
        if (checkInTypeName == null) {
            return null;
        }
        
        try {
            CheckInPhotoType checkInType = CheckInPhotoType.valueOf(checkInTypeName);
            List<CheckInPhoto> checkInPhotos = checkInPhotoRepository.findByBookingIdAndPhotoType(
                booking.getId(), checkInType);
            
            if (checkInPhotos.isEmpty()) {
                return null;
            }
            
            // Get URLs for comparison (future: AI comparison)
            CheckInPhoto checkInPhoto = checkInPhotos.get(0);
            
            return HostCheckoutPhotoResponseDTO.ConditionComparisonDTO.builder()
                .photoType(checkInType.name().replace("HOST_", ""))
                .checkInPhotoUrl(checkInPhoto.getStorageKey())
                .checkOutPhotoUrl(null) // Set by caller
                .changeDetected(false) // Future: AI detection
                .changeSeverity("NONE")
                .build();
            
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Get list of required photo types that are still missing.
     */
    private List<String> getMissingPhotoTypes(Long bookingId) {
        List<HostCheckoutPhoto> existingPhotos = hostCheckoutPhotoRepository.findRequiredHostCheckoutPhotos(bookingId);
        Set<CheckInPhotoType> existingTypes = existingPhotos.stream()
            .filter(p -> p.isExifValid())
            .map(HostCheckoutPhoto::getPhotoType)
            .collect(Collectors.toSet());
        
        return Arrays.stream(CheckInPhotoType.getRequiredHostCheckoutTypes())
            .filter(type -> !existingTypes.contains(type))
            .map(Enum::name)
            .collect(Collectors.toList());
    }

    /**
     * Build user-friendly message based on results.
     */
    private String buildUserMessage(int accepted, int rejected, boolean complete, boolean damageDetected) {
        StringBuilder message = new StringBuilder();
        
        if (complete) {
            message.append("Sve fotografije povratka su uspešno sačuvane.");
            if (damageDetected) {
                message.append(" Otkrivena je promena stanja vozila - molimo pregledajte.");
            } else {
                message.append(" Povratak vozila je spreman za završetak.");
            }
        } else if (accepted > 0 && rejected > 0) {
            message.append(String.format("Prihvaćeno %d fotografija, odbijeno %d. Molimo dodajte preostale fotografije.", 
                accepted, rejected));
        } else if (accepted > 0) {
            message.append(String.format("Prihvaćeno %d fotografija. Molimo dodajte preostale obavezne fotografije.", accepted));
        } else {
            message.append("Nijedna fotografija nije prihvaćena. Proverite da li fotografišete direktno sa kamere.");
        }
        
        return message.toString();
    }

    /**
     * Get all host checkout photos for a booking.
     */
    @Transactional(readOnly = true)
    public List<CheckInPhotoDTO> getHostCheckoutPhotos(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        // Allow access to both host and guest
        boolean isHost = booking.getCar().getOwner().getId().equals(userId);
        boolean isGuest = booking.getRenter().getId().equals(userId);
        
        if (!isHost && !isGuest) {
            throw new AccessDeniedException("Nemate pristup ovim fotografijama");
        }
        
        return hostCheckoutPhotoRepository.findByBookingId(bookingId).stream()
            .filter(photo -> !photo.isDeleted())
            .map(this::toDTO)
            .collect(Collectors.toList());
    }

    private CheckInPhotoDTO toDTO(HostCheckoutPhoto photo) {
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

package org.example.rentoza.booking.checkin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.ExifValidationService.ExifValidationResult;
import org.example.rentoza.booking.checkin.dto.CheckInPhotoDTO;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.security.LockboxEncryptionService;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * Service for handling check-in photo uploads and storage.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckInPhotoService {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final CheckInPhotoRepository photoRepository;
    private final CheckInEventService eventService;
    private final ExifValidationService exifValidationService;
    private final LockboxEncryptionService lockboxEncryptionService;

    @Value("${app.checkin.photo.upload-dir:uploads/checkin}")
    private String uploadDir;

    @Value("${app.checkin.photo.max-size-mb:10}")
    private int maxSizeMb;

    /**
     * Upload a check-in photo with EXIF validation.
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
     */
    @Transactional
    public CheckInPhotoDTO uploadPhoto(
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
        
        // Get booking
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        // Validate access based on photo type and booking status
        if (photoType.isCheckoutPhoto()) {
            // Checkout photo authorization
            if (photoType.isHostCheckoutPhoto()) {
                // Host checkout photo: only owner can upload
                if (!booking.getCar().getOwner().getId().equals(userId)) {
                    throw new AccessDeniedException("Samo vlasnik vozila može otpremiti fotografije za checkout");
                }
            } else {
                // Guest checkout photo: only renter can upload
                if (!booking.getRenter().getId().equals(userId)) {
                    throw new AccessDeniedException("Samo gost može otpremiti fotografije za checkout");
                }
            }
        } else {
            // Check-in photo: only owner can upload
            if (!booking.getCar().getOwner().getId().equals(userId)) {
                throw new AccessDeniedException("Samo vlasnik vozila može otpremiti fotografije");
            }
        }
        
        // Validate status based on photo type
        if (photoType.isCheckoutPhoto()) {
            if (photoType.isHostCheckoutPhoto()) {
                // Host checkout: must be in CHECKOUT_GUEST_COMPLETE
                if (booking.getStatus() != BookingStatus.CHECKOUT_GUEST_COMPLETE) {
                    throw new IllegalStateException("Checkout nije spreman za otpremanje fotografija od strane domaćina");
                }
            } else {
                // Guest checkout: must be in CHECKOUT_OPEN
                if (booking.getStatus() != BookingStatus.CHECKOUT_OPEN) {
                    throw new IllegalStateException("Checkout nije otvoren za otpremanje fotografija");
                }
            }
        } else {
            // Check-in photo: must be in CHECK_IN_OPEN
            if (booking.getStatus() != BookingStatus.CHECK_IN_OPEN) {
                throw new IllegalStateException("Prijem nije otvoren za otpremanje fotografija");
            }
        }
        
        // Get user
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Korisnik nije pronađen"));
        
        // Read file bytes
        byte[] photoBytes = file.getBytes();
        
        // EXIF validation
        // We must pass clientTimestamp to allow the service to:
        // 1. Calculate photo age relative to when the user actually took/uploaded it
        // 2. Enable "sidecar fallback" for HEIC/no-EXIF images (requires non-null timestamp)
        ExifValidationResult exifResult = exifValidationService.validate(
            photoBytes, 
            clientTimestamp
        );
        
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
        
        // Generate storage path
        String sessionId = photoType.isCheckoutPhoto() 
            ? booking.getCheckoutSessionId() 
            : booking.getCheckInSessionId();
            
        if (sessionId == null) {
            // Auto-heal: Generate session ID if missing (legacy booking support)
            sessionId = UUID.randomUUID().toString();
            if (photoType.isCheckoutPhoto()) {
                booking.setCheckoutSessionId(sessionId);
                log.info("[CheckIn] Auto-generated missing checkoutSessionId for booking {}", bookingId);
            } else {
                booking.setCheckInSessionId(sessionId);
                log.info("[CheckIn] Auto-generated missing checkInSessionId for booking {}", bookingId);
            }
            bookingRepository.save(booking);
        }
        
        String filename = String.format("%s_%s_%s.jpg", 
            photoType.name().toLowerCase(), 
            System.currentTimeMillis(),
            UUID.randomUUID().toString().substring(0, 8)
        );
        String storageKey = String.format(
            photoType.isCheckoutPhoto() ? "checkout/%s/%s" : "checkin/%s/%s", 
            sessionId, 
            filename
        );
        
        // Ensure directory exists (use different base dir for checkout if needed)
        String baseDir = photoType.isCheckoutPhoto() ? uploadDir.replace("checkin", "checkout") : uploadDir;
        Path uploadPath = Paths.get(baseDir, sessionId);
        Files.createDirectories(uploadPath);
        
        // Save file
        Path filePath = uploadPath.resolve(filename);
        Files.write(filePath, photoBytes);
        
        // Determine storage bucket
        CheckInPhoto.StorageBucket bucket;
        if (photoType.name().contains("ID")) {
            bucket = CheckInPhoto.StorageBucket.CHECKIN_PII;
        } else if (photoType.isCheckoutPhoto()) {
            bucket = CheckInPhoto.StorageBucket.CHECKIN_STANDARD; // Or create CHECKOUT_STANDARD if needed
        } else {
            bucket = CheckInPhoto.StorageBucket.CHECKIN_STANDARD;
        }
        
        // Create photo entity
        CheckInPhoto photo = CheckInPhoto.builder()
                .booking(booking)
                .checkInSessionId(sessionId)
                .photoType(photoType)
                .storageBucket(bucket)
                .storageKey(storageKey)
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
                .uploadedBy(user)
                .clientUploadedAt(clientTimestamp)
                .build();
        
        photo = photoRepository.save(photo);
        
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
            userId,
            actorRole,
            clientTimestamp,
            Map.of(
                "photoId", photo.getId(),
                "photoType", photoType.name(),
                "exifValid", exifResult.isAccepted(),
                "exifStatus", exifResult.getStatus().name(),
                "fileSize", file.getSize(),
                "usedClientGps", usedClientGps
            )
        );
        
        log.info("[CheckIn] Photo uploaded: booking={}, type={}, exifStatus={}", 
            bookingId, photoType, exifResult.getStatus());
        
        // Add to booking's photo collection
        booking.getCheckInPhotos().add(photo);
        
        return mapToDTO(photo);
    }

    /**
     * Reveal lockbox code to guest.
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
        
        // Validate status (must have acknowledged condition)
        if (booking.getStatus().ordinal() < BookingStatus.CHECK_IN_HOST_COMPLETE.ordinal()) {
            throw new IllegalStateException("Domaćin još nije završio prijem");
        }
        
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
                "guestLatitude", latitude != null ? latitude.toString() : "N/A",
                "guestLongitude", longitude != null ? longitude.toString() : "N/A"
            )
        );
        
        bookingRepository.save(booking);
        
        // Decrypt the lockbox code using AES-256-GCM
        String decryptedCode = lockboxEncryptionService.decrypt(booking.getLockboxCodeEncrypted());
        log.info("[CheckIn] Lockbox code decrypted and revealed for booking {} to user {}", bookingId, userId);
        
        return decryptedCode;
    }

    /**
     * Store an ID verification photo in the PII bucket.
     * 
     * <p>This method is used by the ID verification service to store:
     * <ul>
     *   <li>Selfie for liveness check</li>
     *   <li>ID document front</li>
     *   <li>ID document back</li>
     * </ul>
     *
     * @param bookingId The booking ID
     * @param sessionId The check-in session ID
     * @param file The photo file
     * @param photoType Type identifier (selfie, id_front, id_back)
     * @return Storage key for the photo
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
        
        // Ensure directory exists
        Path uploadPath = Paths.get(uploadDir + "_pii", sessionId);
        Files.createDirectories(uploadPath);
        
        // Save file
        Path filePath = uploadPath.resolve(filename);
        Files.write(filePath, file.getBytes());
        
        log.info("[CheckIn] ID photo stored: booking={}, type={}, key={}", bookingId, photoType, storageKey);
        
        return storageKey;
    }

    private CheckInPhotoDTO mapToDTO(CheckInPhoto photo) {
        return CheckInPhotoDTO.builder()
                .photoId(photo.getId())
                .photoType(photo.getPhotoType())
                .url(photo.getStorageKey())
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

    private LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) return null;
        return LocalDateTime.ofInstant(instant, SERBIA_ZONE);
    }
}

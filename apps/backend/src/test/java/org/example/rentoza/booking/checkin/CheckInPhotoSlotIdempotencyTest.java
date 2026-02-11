package org.example.rentoza.booking.checkin;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.ExifValidationService.ExifValidationResult;
import org.example.rentoza.booking.checkin.dto.PhotoUploadResponse;
import org.example.rentoza.booking.photo.PiiPhotoStorageService;
import org.example.rentoza.car.Car;
import org.example.rentoza.security.LockboxEncryptionService;
import org.example.rentoza.storage.SupabaseStorageService;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.example.rentoza.util.ExifStrippingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests slot idempotency: re-uploading a required photo type
 * soft-deletes the previous active photo for that slot.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CheckInPhotoService – Slot idempotency (soft-delete-then-insert)")
class CheckInPhotoSlotIdempotencyTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private UserRepository userRepository;
    @Mock private CheckInPhotoRepository photoRepository;
    @Mock private CheckInEventService eventService;
    @Mock private ExifValidationService exifValidationService;
    @Mock private LockboxEncryptionService lockboxEncryptionService;
    @Mock private PhotoRejectionService photoRejectionService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SupabaseStorageService supabaseStorageService;
    @Mock private PiiPhotoStorageService piiPhotoStorageService;
    @Mock private ExifStrippingService exifStrippingService;
    @Mock private CheckInValidationService validationService;

    @InjectMocks
    private CheckInPhotoService photoService;

    private Booking booking;
    private User owner;
    private CheckInPhoto existingPhoto;

    // Minimal valid JPEG header (FF D8 FF E0 + JFIF marker, padded to 12 bytes)
    private static final byte[] JPEG_BYTES = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01
    };

    @BeforeEach
    void setUp() throws Exception {
        // @Value defaults
        ReflectionTestUtils.setField(photoService, "maxSizeMb", 10);
        ReflectionTestUtils.setField(photoService, "auditBackupEnabled", false);

        // Owner user
        owner = new User();
        owner.setId(100L);

        // Car with owner
        Car car = new Car();
        car.setOwner(owner);

        // Booking in CHECK_IN_OPEN state
        booking = new Booking();
        booking.setId(1L);
        booking.setStatus(BookingStatus.CHECK_IN_OPEN);
        booking.setCar(car);
        booking.setCheckInSessionId("session-abc");
        booking.setCheckInPhotos(new ArrayList<>());

        // Existing active photo for HOST_EXTERIOR_FRONT
        existingPhoto = CheckInPhoto.builder()
                .booking(booking)
                .checkInSessionId("session-abc")
                .photoType(CheckInPhotoType.HOST_EXTERIOR_FRONT)
                .storageKey("old-key")
                .mimeType("image/jpeg")
                .fileSizeBytes(1024)
                .uploadedBy(owner)
                .build();
        existingPhoto.setId(99L);

        // Common mock stubs
        when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(booking));
        when(userRepository.findById(100L)).thenReturn(Optional.of(owner));

        // EXIF validation returns accepted
        ExifValidationResult exifResult = ExifValidationResult.builder()
                .status(ExifValidationStatus.VALID_NO_GPS)
                .message("OK")
                .build();
        when(exifValidationService.validate(any(), any())).thenReturn(exifResult);

        // Rejection service: don't reject
        when(photoRejectionService.shouldReject(any())).thenReturn(false);

        // EXIF stripping: return bytes as-is
        when(exifStrippingService.stripExifMetadata(any(), any())).thenReturn(JPEG_BYTES);

        // Supabase upload: return storage key
        when(supabaseStorageService.uploadCheckInPhotoBytes(anyLong(), any(), any(), any(), any()))
                .thenReturn("new-storage-key");

        // Photo save: return the photo with an ID set
        when(photoRepository.save(any(CheckInPhoto.class))).thenAnswer(invocation -> {
            CheckInPhoto p = invocation.getArgument(0);
            if (p.getId() == null) {
                p.setId(200L);
            }
            return p;
        });
    }

    @Test
    @DisplayName("Re-upload of required slot soft-deletes existing photo before inserting new one")
    void shouldSoftDeleteExistingPhotoOnRetake() throws Exception {
        // Arrange: existing active photo for HOST_EXTERIOR_FRONT
        when(photoRepository.findByBookingIdAndPhotoType(1L, CheckInPhotoType.HOST_EXTERIOR_FRONT))
                .thenReturn(List.of(existingPhoto));

        MockMultipartFile file = new MockMultipartFile(
                "file", "retake.jpg", "image/jpeg", JPEG_BYTES);

        // Act
        PhotoUploadResponse response = photoService.uploadPhoto(
                1L, 100L, file, CheckInPhotoType.HOST_EXTERIOR_FRONT,
                Instant.now(), null, null);

        // Assert: response is accepted
        assertThat(response.isAccepted()).isTrue();

        // Assert: existing photo was soft-deleted
        assertThat(existingPhoto.isDeleted()).isTrue();
        assertThat(existingPhoto.getDeletedReason()).isEqualTo("REPLACED_BY_RETAKE");

        // Assert: photoRepository.save called for soft-delete AND new photo
        ArgumentCaptor<CheckInPhoto> saveCaptor = ArgumentCaptor.forClass(CheckInPhoto.class);
        verify(photoRepository, atLeast(2)).save(saveCaptor.capture());

        List<CheckInPhoto> savedPhotos = saveCaptor.getAllValues();
        // First save: soft-deleted old photo
        assertThat(savedPhotos).anySatisfy(p -> {
            assertThat(p.getId()).isEqualTo(99L);
            assertThat(p.isDeleted()).isTrue();
        });
        // Second save: new photo
        assertThat(savedPhotos).anySatisfy(p -> {
            assertThat(p.getStorageKey()).isEqualTo("new-storage-key");
        });
    }

    @Test
    @DisplayName("First upload of required slot does not soft-delete anything")
    void shouldNotSoftDeleteWhenNoExistingPhoto() throws Exception {
        // Arrange: no existing photo
        when(photoRepository.findByBookingIdAndPhotoType(1L, CheckInPhotoType.HOST_EXTERIOR_FRONT))
                .thenReturn(List.of());

        MockMultipartFile file = new MockMultipartFile(
                "file", "first.jpg", "image/jpeg", JPEG_BYTES);

        // Act
        PhotoUploadResponse response = photoService.uploadPhoto(
                1L, 100L, file, CheckInPhotoType.HOST_EXTERIOR_FRONT,
                Instant.now(), null, null);

        // Assert: response is accepted
        assertThat(response.isAccepted()).isTrue();

        // Assert: only one save (the new photo)
        verify(photoRepository, times(1)).save(any(CheckInPhoto.class));
    }

    @Test
    @DisplayName("Damage photo does NOT trigger slot idempotency (multi-entry by design)")
    void shouldNotSoftDeleteForDamagePhotoType() throws Exception {
        // Arrange: Damage photos are multi-entry, not slot-idempotent
        // We don't call findByBookingIdAndPhotoType for damage types
        MockMultipartFile file = new MockMultipartFile(
                "file", "damage.jpg", "image/jpeg", JPEG_BYTES);

        // Act
        PhotoUploadResponse response = photoService.uploadPhoto(
                1L, 100L, file, CheckInPhotoType.HOST_DAMAGE_PREEXISTING,
                Instant.now(), null, null);

        // Assert
        assertThat(response.isAccepted()).isTrue();
        verify(photoRepository, never()).findByBookingIdAndPhotoType(anyLong(), eq(CheckInPhotoType.HOST_DAMAGE_PREEXISTING));
    }
}

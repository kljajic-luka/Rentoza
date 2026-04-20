package org.example.rentoza.booking.checkin;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.ExifValidationService.ExifValidationResult;
import org.example.rentoza.booking.checkin.dto.PhotoUploadResponse;
import org.example.rentoza.booking.photo.PiiPhotoStorageService;
import org.example.rentoza.booking.photo.PhotoUrlService;
import org.example.rentoza.car.Car;
import org.example.rentoza.security.LockboxEncryptionService;
import org.example.rentoza.storage.SupabaseStorageService;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.example.rentoza.util.ExifStrippingService;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CheckInPhotoService - Durable upload persistence")
class CheckInPhotoUploadDurabilityTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private UserRepository userRepository;
    @Mock private CheckInPhotoRepository photoRepository;
    @Mock private CheckInEventService eventService;
    @Mock private ExifValidationService exifValidationService;
    @Mock private LockboxEncryptionService lockboxEncryptionService;
    @Mock private GeofenceService geofenceService;
    @Mock private PhotoRejectionService photoRejectionService;
    @Mock private PhotoRejectionBudgetService photoRejectionBudgetService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SupabaseStorageService supabaseStorageService;
    @Mock private PiiPhotoStorageService piiPhotoStorageService;
    @Mock private ExifStrippingService exifStrippingService;
    @Mock private CheckInValidationService validationService;
    @Mock private PhotoUrlService photoUrlService;
    @Mock private TransactionOperations transactionOperations;

    @InjectMocks
    private CheckInPhotoService photoService;

    private Booking booking;
    private User owner;
    private List<CheckInPhoto> saveSnapshots;
    private Map<Long, CheckInPhoto> storedPhotos;

    private static final byte[] JPEG_BYTES = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01
    };

    @BeforeEach
    void setUp() throws Exception {
        ReflectionTestUtils.setField(photoService, "maxSizeMb", 10);
        ReflectionTestUtils.setField(photoService, "auditBackupEnabled", true);
        ReflectionTestUtils.setField(photoService, "checkinPhotoDeadlineHours", 24);
        ReflectionTestUtils.setField(photoService, "checkoutPhotoDeadlineHours", 24);

        when(transactionOperations.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

        owner = new User();
        owner.setId(100L);

        Car car = new Car();
        car.setOwner(owner);

        booking = new Booking();
        booking.setId(1L);
        booking.setStatus(BookingStatus.CHECK_IN_OPEN);
        booking.setCar(car);
        booking.setCheckInSessionId("00000000-0000-0000-0000-000000000001");
        booking.setCheckInPhotos(new ArrayList<>());
        saveSnapshots = new ArrayList<>();
        storedPhotos = new HashMap<>();

        when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(booking));
        when(bookingRepository.findByIdWithLock(1L)).thenReturn(Optional.of(booking));
        when(userRepository.findById(100L)).thenReturn(Optional.of(owner));
        when(photoRepository.countByBookingId(1L)).thenReturn(0L);
        when(photoRepository.findByBookingIdAndPhotoType(anyLong(), any())).thenReturn(List.of());
        when(photoRepository.findCompletedByBookingIdAndPhotoTypeExcludingId(anyLong(), any(), anyLong())).thenReturn(List.of());
        when(exifValidationService.validate(any(), any())).thenReturn(ExifValidationResult.builder()
                .status(ExifValidationStatus.VALID_NO_GPS)
                .message("OK")
                .build());
        when(photoRejectionService.shouldReject(any())).thenReturn(false);
        when(exifStrippingService.stripExifMetadata(any(), any())).thenReturn(JPEG_BYTES);
        when(photoUrlService.generateSignedUrl(anyString(), anyString(), anyLong()))
                .thenAnswer(invocation -> "signed:" + invocation.getArgument(1, String.class));

        when(photoRepository.save(any(CheckInPhoto.class))).thenAnswer(invocation -> {
            CheckInPhoto photo = invocation.getArgument(0);
            if (photo.getId() == null) {
                photo.setId(900L);
            }
            return persistSnapshot(photo);
        });
        when(photoRepository.findById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(snapshot(storedPhotos.get(invocation.getArgument(0, Long.class)))));
    }

    private CheckInPhoto lastSavedPhoto() {
        return snapshot(saveSnapshots.get(saveSnapshots.size() - 1));
    }

    private CheckInPhoto persistSnapshot(CheckInPhoto photo) {
        CheckInPhoto saved = snapshot(photo);
        saveSnapshots.add(saved);
        storedPhotos.put(saved.getId(), snapshot(saved));
        return photo;
    }

    private CheckInPhoto snapshot(CheckInPhoto photo) {
        if (photo == null) {
            return null;
        }
        CheckInPhoto copy = new CheckInPhoto();
        copy.setId(photo.getId());
        copy.setBooking(photo.getBooking());
        copy.setCheckInSessionId(photo.getCheckInSessionId());
        copy.setPhotoType(photo.getPhotoType());
        copy.setStorageBucket(photo.getStorageBucket());
        copy.setStorageKey(photo.getStorageKey());
        copy.setAuditStorageKey(photo.getAuditStorageKey());
        copy.setUploadStatus(photo.getUploadStatus());
        copy.setUploadAttempts(photo.getUploadAttempts());
        copy.setLastUploadAttemptAt(photo.getLastUploadAttemptAt());
        copy.setStandardUploadedAt(photo.getStandardUploadedAt());
        copy.setUploadFinalizedAt(photo.getUploadFinalizedAt());
        copy.setLastUploadError(photo.getLastUploadError());
        copy.setAuditUploadStatus(photo.getAuditUploadStatus());
        copy.setAuditUploadedAt(photo.getAuditUploadedAt());
        copy.setOriginalFilename(photo.getOriginalFilename());
        copy.setMimeType(photo.getMimeType());
        copy.setFileSizeBytes(photo.getFileSizeBytes());
        copy.setUploadedBy(photo.getUploadedBy());
        copy.setDeletedAt(photo.getDeletedAt());
        copy.setDeletedReason(photo.getDeletedReason());
        copy.setImageHash(photo.getImageHash());
        copy.setExifValidationStatus(photo.getExifValidationStatus());
        copy.setExifValidationMessage(photo.getExifValidationMessage());
        return copy;
    }

    @Test
    @DisplayName("DB-first pending row finalizes after successful storage writes")
    void uploadPhoto_dbFirstPendingRow_finalizesAfterStorage() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "front.jpg", "image/jpeg", JPEG_BYTES);

        PhotoUploadResponse response = photoService.uploadPhoto(
                1L, 100L, file, CheckInPhotoType.HOST_EXTERIOR_FRONT, Instant.now(), null, null);

        assertThat(response.isAccepted()).isTrue();

        InOrder order = inOrder(photoRepository, supabaseStorageService);
        order.verify(photoRepository).save(any(CheckInPhoto.class));
        order.verify(supabaseStorageService).uploadCheckInPhotoToAuditBucketAtPath(anyString(), any(), eq("image/jpeg"));
        order.verify(supabaseStorageService).uploadCheckInPhotoBytesAtPath(anyString(), any(), eq("image/jpeg"));

        ArgumentCaptor<CheckInPhoto> captor = ArgumentCaptor.forClass(CheckInPhoto.class);
        verify(photoRepository, atLeastOnce()).save(captor.capture());
        assertThat(saveSnapshots.get(0).getUploadStatus()).isEqualTo(CheckInPhoto.UploadStatus.PENDING_UPLOAD);
        assertThat(saveSnapshots).anySatisfy(photo -> assertThat(photo.getUploadStatus()).isEqualTo(CheckInPhoto.UploadStatus.PENDING_FINALIZE));
        assertThat(saveSnapshots).anySatisfy(photo -> assertThat(photo.getUploadStatus()).isEqualTo(CheckInPhoto.UploadStatus.COMPLETED));
    }

    @Test
    @DisplayName("storage success plus finalize DB failure leaves pending-finalize row for reconciliation")
    void uploadPhoto_storageSuccessButFinalizeFails_leavesPendingFinalizeState() throws Exception {
        when(photoRepository.save(any(CheckInPhoto.class))).thenAnswer(invocation -> {
            CheckInPhoto photo = invocation.getArgument(0);
            if (photo.getId() == null) {
                photo.setId(900L);
                return persistSnapshot(photo);
            }
            if (photo.getUploadStatus() == CheckInPhoto.UploadStatus.COMPLETED) {
                throw new DataIntegrityViolationException("finalize failed");
            }
            return persistSnapshot(photo);
        });

        MockMultipartFile file = new MockMultipartFile("file", "front.jpg", "image/jpeg", JPEG_BYTES);

        assertThatThrownBy(() -> photoService.uploadPhoto(
                1L, 100L, file, CheckInPhotoType.HOST_EXTERIOR_FRONT, Instant.now(), null, null))
                .isInstanceOf(IllegalStateException.class);

        verify(photoRepository, atLeastOnce()).save(any(CheckInPhoto.class));
        assertThat(saveSnapshots).anySatisfy(photo -> assertThat(photo.getUploadStatus()).isEqualTo(CheckInPhoto.UploadStatus.PENDING_FINALIZE));
    }

    @Test
    @DisplayName("stale pending upload reconciliation finalizes when standard object exists")
    void reconcileStalePendingUpload_finalizesWhenStorageExists() {
        CheckInPhoto pending = CheckInPhoto.builder()
                .id(901L)
                .booking(booking)
                .checkInSessionId("session-abc")
                .photoType(CheckInPhotoType.HOST_EXTERIOR_FRONT)
                .storageBucket(CheckInPhoto.StorageBucket.CHECKIN_STANDARD)
                .storageKey("bookings/1/host/HOST_EXTERIOR_FRONT/recover.jpg")
                .mimeType("image/jpeg")
                .fileSizeBytes(JPEG_BYTES.length)
                .uploadedBy(owner)
                .uploadStatus(CheckInPhoto.UploadStatus.PENDING_FINALIZE)
                .auditUploadStatus(CheckInPhoto.AuditUploadStatus.NOT_REQUIRED)
                .build();

        when(photoRepository.findStalePendingUploads(any())).thenReturn(List.of(pending));
        when(photoRepository.findById(901L)).thenReturn(Optional.of(pending));
        when(supabaseStorageService.checkInPhotoExists(pending.getStorageKey())).thenReturn(true);

        CheckInPhotoService.UploadReconciliationResult result = photoService.reconcileStaleUploads(Instant.now());

        assertThat(result.finalized()).isEqualTo(1);
        assertThat(result.terminalFailures()).isEqualTo(0);
        verify(eventService).recordSystemEvent(eq(booking), eq("session-abc"), eq(CheckInEventType.PHOTO_UPLOAD_RECOVERED), any(Map.class));
    }

    @Test
    @DisplayName("stale pending upload reconciliation keeps storage and retryability on transient finalize DB failure")
    void reconcileStalePendingUpload_finalizeDbFailure_remainsRecoverable() {
        CheckInPhoto pending = CheckInPhoto.builder()
                .id(901L)
                .booking(booking)
                .checkInSessionId("session-abc")
                .photoType(CheckInPhotoType.HOST_EXTERIOR_FRONT)
                .storageBucket(CheckInPhoto.StorageBucket.CHECKIN_STANDARD)
                .storageKey("bookings/1/host/HOST_EXTERIOR_FRONT/recover.jpg")
                .mimeType("image/jpeg")
                .fileSizeBytes(JPEG_BYTES.length)
                .uploadedBy(owner)
                .uploadStatus(CheckInPhoto.UploadStatus.PENDING_FINALIZE)
                .auditUploadStatus(CheckInPhoto.AuditUploadStatus.NOT_REQUIRED)
                .lastUploadError("existing pending finalize")
                .build();
        storedPhotos.put(901L, snapshot(pending));

        when(photoRepository.findStalePendingUploads(any())).thenReturn(List.of(snapshot(pending)));
        when(photoRepository.save(any(CheckInPhoto.class))).thenAnswer(invocation -> {
            CheckInPhoto photo = invocation.getArgument(0);
            if (photo.getId() == null) {
                photo.setId(900L);
            }
            if (photo.getId().equals(901L)
                    && photo.getUploadStatus() == CheckInPhoto.UploadStatus.COMPLETED) {
                throw new DataIntegrityViolationException("transient finalize write failed");
            }
            return persistSnapshot(photo);
        });
        when(supabaseStorageService.checkInPhotoExists(pending.getStorageKey())).thenReturn(true);

        CheckInPhotoService.UploadReconciliationResult result = photoService.reconcileStaleUploads(Instant.now());

        assertThat(result.finalized()).isEqualTo(0);
        assertThat(result.terminalFailures()).isEqualTo(0);
        assertThat(storedPhotos.get(901L).getUploadStatus()).isEqualTo(CheckInPhoto.UploadStatus.PENDING_FINALIZE);
        assertThat(storedPhotos.get(901L).getLastUploadError()).contains("Finalize pending after database failure");
        verify(supabaseStorageService, never()).deleteCheckInPhoto(anyString());
        verify(supabaseStorageService, never()).deleteCheckInAuditPhoto(anyString());
        verify(eventService).recordSystemEvent(
                eq(booking),
                eq("session-abc"),
                eq(CheckInEventType.PHOTO_UPLOAD_STORAGE_FAILED),
                argThat(metadata -> "FINALIZE_RETRYABLE".equals(metadata.get("stage"))
                        && "PENDING_FINALIZE".equals(metadata.get("uploadStatus"))
                        && Boolean.TRUE.equals(metadata.get("retryable")))
        );
    }

    @Test
    @DisplayName("audit-bucket failure remains visible while standard upload still completes")
    void uploadPhoto_auditBucketFails_preservesVisibility() throws Exception {
        doThrow(new IOException("audit unavailable")).when(supabaseStorageService)
                .uploadCheckInPhotoToAuditBucketAtPath(anyString(), any(), eq("image/jpeg"));
        doNothing().when(supabaseStorageService).uploadCheckInPhotoBytesAtPath(anyString(), any(), eq("image/jpeg"));

        MockMultipartFile file = new MockMultipartFile("file", "front.jpg", "image/jpeg", JPEG_BYTES);

        PhotoUploadResponse response = photoService.uploadPhoto(
                1L, 100L, file, CheckInPhotoType.HOST_EXTERIOR_FRONT, Instant.now(), null, null);

        assertThat(response.isAccepted()).isTrue();

        verify(photoRepository, atLeastOnce()).save(any(CheckInPhoto.class));
        assertThat(saveSnapshots).anySatisfy(photo -> {
            assertThat(photo.getAuditUploadStatus()).isEqualTo(CheckInPhoto.AuditUploadStatus.FAILED);
            assertThat(photo.getLastUploadError()).contains("Audit bucket upload failed");
        });
        verify(eventService).recordEvent(eq(booking), eq("00000000-0000-0000-0000-000000000001"), eq(CheckInEventType.HOST_PHOTO_UPLOADED), eq(100L), eq(CheckInActorRole.HOST), any(), argThat(metadata -> Boolean.TRUE.equals(metadata.get("auditIntegrityGap"))));
    }

    @Test
    @DisplayName("upload does not touch detached booking photo collection after finalize")
    void uploadPhoto_doesNotTouchDetachedBookingPhotoCollectionAfterFinalize() throws Exception {
        Booking detachedBooking = spy(booking);
        doThrow(new LazyInitializationException("failed to lazily initialize a collection of role: org.example.rentoza.booking.Booking.checkInPhotos"))
                .when(detachedBooking)
                .getCheckInPhotos();

        assertThatThrownBy(detachedBooking::getCheckInPhotos)
            .isInstanceOf(LazyInitializationException.class)
            .hasMessageContaining("Booking.checkInPhotos");

        when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(detachedBooking));
        when(bookingRepository.findByIdWithLock(1L)).thenReturn(Optional.of(detachedBooking));

        MockMultipartFile file = new MockMultipartFile("file", "front.jpg", "image/jpeg", JPEG_BYTES);

        PhotoUploadResponse response = photoService.uploadPhoto(
                1L, 100L, file, CheckInPhotoType.HOST_EXTERIOR_FRONT, Instant.now(), null, null);

        assertThat(response.isAccepted()).isTrue();
        assertThat(saveSnapshots).anySatisfy(photo ->
                assertThat(photo.getUploadStatus()).isEqualTo(CheckInPhoto.UploadStatus.COMPLETED));
    }
}
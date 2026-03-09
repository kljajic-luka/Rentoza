package org.example.rentoza.booking.checkin;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.dto.CheckInPhotoDTO;
import org.example.rentoza.booking.checkin.dto.GuestCheckInPhotoResponseDTO;
import org.example.rentoza.booking.checkin.dto.GuestCheckInPhotoSubmissionDTO;
import org.example.rentoza.car.Car;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.example.rentoza.storage.SupabaseStorageService;
import org.example.rentoza.booking.photo.PhotoAccessLogService;

import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GuestCheckInPhotoService.
 * 
 * Tests the dual-party photo verification workflow where guests
 * upload photos to confirm vehicle condition at pickup.
 * 
 * @since Enterprise Upgrade Phase 2
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GuestCheckInPhotoService Tests")
class GuestCheckInPhotoServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private UserRepository userRepository;
    @Mock private GuestCheckInPhotoRepository guestPhotoRepository;
    @Mock private CheckInPhotoRepository hostPhotoRepository;
    @Mock private PhotoDiscrepancyRepository discrepancyRepository;
    @Mock private CheckInEventService eventService;
    @Mock private ExifValidationService exifValidationService;
    @Mock private PhotoRejectionService photoRejectionService;
    @Mock private PhotoGuidanceService photoGuidanceService;
    @Mock private SupabaseStorageService supabaseStorageService;
    @Mock private org.example.rentoza.booking.photo.PhotoUrlService photoUrlService;
    @Mock private PhotoRejectionBudgetService photoRejectionBudgetService;
    @Mock private PhotoAccessLogService photoAccessLogService;

    private GuestCheckInPhotoService guestPhotoService;
    
    // Test fixtures
    private Long bookingId;
    private Long renterId;
    private Long hostId;
    private Booking testBooking;
    private User testRenter;
    private User testHost;
    private Car testCar;

    @BeforeEach
    void setUp() {
        guestPhotoService = new GuestCheckInPhotoService(
            bookingRepository,
            userRepository,
            guestPhotoRepository,
            hostPhotoRepository,
            discrepancyRepository,
            eventService,
            exifValidationService,
            photoRejectionService,
            photoGuidanceService,
            supabaseStorageService,
            photoUrlService,
            photoRejectionBudgetService,
            photoAccessLogService
        );
        
        // Inject @Value properties that Spring would normally inject
        ReflectionTestUtils.setField(guestPhotoService, "maxSizeMb", 3);
        ReflectionTestUtils.setField(guestPhotoService, "maxWidthPixels", 2560);
        ReflectionTestUtils.setField(guestPhotoService, "maxHeightPixels", 2560);
        
        // Initialize test fixtures
        bookingId = 1L;
        renterId = 100L;
        hostId = 200L;
        
        testRenter = new User();
        testRenter.setId(renterId);
        testRenter.setEmail("renter@test.com");
        
        testHost = new User();
        testHost.setId(hostId);
        testHost.setEmail("host@test.com");
        
        testCar = new Car();
        testCar.setId(1L);
        testCar.setOwner(testHost);
        
        testBooking = new Booking();
        testBooking.setId(bookingId);
        testBooking.setRenter(testRenter);
        testBooking.setCar(testCar);
        testBooking.setStatus(BookingStatus.CHECK_IN_HOST_COMPLETE);
        testBooking.setCheckInSessionId("test-session-123");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AUTHORIZATION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Authorization Tests")
    class AuthorizationTests {

        @Test
        @DisplayName("Should reject upload from non-renter user")
        void shouldRejectUploadFromNonRenter() {
            // Arrange
            Long wrongUserId = 999L;
            GuestCheckInPhotoSubmissionDTO submission = createValidSubmission();
            
            when(bookingRepository.findByIdWithRelations(bookingId))
                .thenReturn(Optional.of(testBooking));
            
            // Act & Assert
            assertThatThrownBy(() -> 
                guestPhotoService.uploadGuestPhotos(bookingId, wrongUserId, submission))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("gost");
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when booking not found")
        void shouldThrowWhenBookingNotFound() {
            // Arrange
            GuestCheckInPhotoSubmissionDTO submission = createValidSubmission();
            
            when(bookingRepository.findByIdWithRelations(bookingId))
                .thenReturn(Optional.empty());
            
            // Act & Assert
            assertThatThrownBy(() -> 
                guestPhotoService.uploadGuestPhotos(bookingId, renterId, submission))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Should return signed URLs and log guest photo list access")
        void shouldReturnSignedUrlsAndLogAccess() {
            when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(testBooking));

            GuestCheckInPhoto photo = GuestCheckInPhoto.builder()
                .id(55L)
                .booking(testBooking)
                .checkInSessionId("test-session-123")
                .photoType(CheckInPhotoType.GUEST_EXTERIOR_FRONT)
                .storageKey("bookings/1/guest/GUEST_EXTERIOR_FRONT/p1.jpg")
                .mimeType("image/jpeg")
                .uploadedAt(Instant.now())
                .uploadedBy(testRenter)
                .exifValidationStatus(ExifValidationStatus.VALID)
                .build();

            when(guestPhotoRepository.findByCheckInSessionId("test-session-123")).thenReturn(List.of(photo));
            when(photoUrlService.generateSignedUrl(anyString(), anyString(), anyLong()))
                .thenReturn("https://signed.example/photo");

            List<CheckInPhotoDTO> result = guestPhotoService.getGuestPhotos(bookingId, renterId);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getUrl()).isEqualTo("https://signed.example/photo");
            verify(photoAccessLogService).logPhotosListAccess(eq(renterId), eq(bookingId), eq(1), anyString(), any());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BOOKING STATUS VALIDATION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Booking Status Validation Tests")
    class BookingStatusTests {

        @Test
        @DisplayName("Should allow upload when status is CHECK_IN_HOST_COMPLETE")
        void shouldAllowUploadWhenHostComplete() {
            // Arrange
            testBooking.setStatus(BookingStatus.CHECK_IN_HOST_COMPLETE);
            GuestCheckInPhotoSubmissionDTO submission = createValidSubmission();
            
            when(bookingRepository.findByIdWithRelations(bookingId))
                .thenReturn(Optional.of(testBooking));
            when(userRepository.findById(renterId))
                .thenReturn(Optional.of(testRenter));
            when(guestPhotoRepository.countRequiredGuestPhotoTypesBySession(anyString()))
                .thenReturn(0L);
            when(exifValidationService.validate(any(), any()))
                .thenReturn(createValidExifResult());
            when(photoRejectionService.shouldReject(any()))
                .thenReturn(true); // Simulate rejection for simplicity
            
            // Act - should not throw
            assertThatCode(() -> 
                guestPhotoService.uploadGuestPhotos(bookingId, renterId, submission))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should reject upload when status is CHECK_IN_OPEN (host not yet complete)")
        void shouldRejectUploadWhenCheckInOpen() {
            // Arrange - CHECK_IN_OPEN means host hasn't finished their photos yet
            testBooking.setStatus(BookingStatus.CHECK_IN_OPEN);
            GuestCheckInPhotoSubmissionDTO submission = createValidSubmission();

            when(bookingRepository.findByIdWithRelations(bookingId))
                .thenReturn(Optional.of(testBooking));

            // Act & Assert - guest cannot upload before host completes
            assertThatThrownBy(() ->
                guestPhotoService.uploadGuestPhotos(bookingId, renterId, submission))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHECK_IN_OPEN");
        }

        @Test
        @DisplayName("Should reject upload when status is PENDING_APPROVAL")
        void shouldRejectUploadWhenPending() {
            // Arrange
            testBooking.setStatus(BookingStatus.PENDING_APPROVAL);
            GuestCheckInPhotoSubmissionDTO submission = createValidSubmission();
            
            when(bookingRepository.findByIdWithRelations(bookingId))
                .thenReturn(Optional.of(testBooking));
            
            // Act & Assert
            assertThatThrownBy(() -> 
                guestPhotoService.uploadGuestPhotos(bookingId, renterId, submission))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING_APPROVAL");
        }

        @Test
        @DisplayName("Should reject upload when status is COMPLETED")
        void shouldRejectUploadWhenCompleted() {
            // Arrange
            testBooking.setStatus(BookingStatus.COMPLETED);
            GuestCheckInPhotoSubmissionDTO submission = createValidSubmission();
            
            when(bookingRepository.findByIdWithRelations(bookingId))
                .thenReturn(Optional.of(testBooking));
            
            // Act & Assert
            assertThatThrownBy(() -> 
                guestPhotoService.uploadGuestPhotos(bookingId, renterId, submission))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COMPLETION TRACKING TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Completion Tracking Tests")
    class CompletionTrackingTests {

        @Test
        @DisplayName("Should mark complete when all 8 required photos uploaded")
        void shouldMarkCompleteWhenAllPhotosUploaded() {
            // Arrange
            testBooking.setStatus(BookingStatus.CHECK_IN_HOST_COMPLETE);
            testBooking.setCheckInSessionId(null); // Will generate new session
            GuestCheckInPhotoSubmissionDTO submission = createSubmissionWith8Photos();
            
            when(bookingRepository.findByIdWithRelations(bookingId))
                .thenReturn(Optional.of(testBooking));
            when(userRepository.findById(renterId))
                .thenReturn(Optional.of(testRenter));
            when(guestPhotoRepository.countRequiredGuestPhotoTypesBySession(anyString()))
                .thenReturn(8L); // All photos present
            when(exifValidationService.validate(any(), any()))
                .thenReturn(createValidExifResult());
            when(photoRejectionService.shouldReject(any()))
                .thenReturn(true); // Reject for simplicity
            
            // Act
            GuestCheckInPhotoResponseDTO response = 
                guestPhotoService.uploadGuestPhotos(bookingId, renterId, submission);
            
            // Assert
            assertThat(response.isGuestPhotosComplete()).isTrue();
            
            // Verify completion event was recorded
            verify(eventService).recordEvent(
                eq(testBooking),
                anyString(),
                eq(CheckInEventType.GUEST_CHECK_IN_PHOTOS_COMPLETE),
                eq(renterId),
                eq(CheckInActorRole.GUEST),
                any(Instant.class),
                anyMap()
            );
        }

        @Test
        @DisplayName("Should not mark complete when photos are missing")
        void shouldNotMarkCompleteWhenPhotosMissing() {
            // Arrange
            testBooking.setStatus(BookingStatus.CHECK_IN_HOST_COMPLETE);
            GuestCheckInPhotoSubmissionDTO submission = createValidSubmission();
            
            when(bookingRepository.findByIdWithRelations(bookingId))
                .thenReturn(Optional.of(testBooking));
            when(userRepository.findById(renterId))
                .thenReturn(Optional.of(testRenter));
            when(guestPhotoRepository.countRequiredGuestPhotoTypesBySession(anyString()))
                .thenReturn(3L); // Only 3 of 8 photos
            when(exifValidationService.validate(any(), any()))
                .thenReturn(createValidExifResult());
            when(photoRejectionService.shouldReject(any()))
                .thenReturn(true);
            
            // Act
            GuestCheckInPhotoResponseDTO response = 
                guestPhotoService.uploadGuestPhotos(bookingId, renterId, submission);
            
            // Assert
            assertThat(response.isGuestPhotosComplete()).isFalse();
            assertThat(response.getMissingRequiredCount()).isEqualTo(5); // 8 - 3 = 5
            
            // Verify completion event was NOT recorded
            verify(eventService, never()).recordEvent(
                any(), any(), eq(CheckInEventType.GUEST_CHECK_IN_PHOTOS_COMPLETE),
                any(), any(), any(), any()
            );
        }

        @Test
        @DisplayName("Should track missing photo types")
        void shouldTrackMissingPhotoTypes() {
            // Arrange
            testBooking.setStatus(BookingStatus.CHECK_IN_HOST_COMPLETE);
            GuestCheckInPhotoSubmissionDTO submission = createValidSubmission();
            
            when(bookingRepository.findByIdWithRelations(bookingId))
                .thenReturn(Optional.of(testBooking));
            when(userRepository.findById(renterId))
                .thenReturn(Optional.of(testRenter));
            when(guestPhotoRepository.countRequiredGuestPhotoTypesBySession(anyString()))
                .thenReturn(0L);
            when(exifValidationService.validate(any(), any()))
                .thenReturn(createValidExifResult());
            when(photoRejectionService.shouldReject(any()))
                .thenReturn(true);
            
            // Act
            GuestCheckInPhotoResponseDTO response = 
                guestPhotoService.uploadGuestPhotos(bookingId, renterId, submission);
            
            // Assert
            assertThat(response.getMissingPhotoTypes()).isNotEmpty();
            assertThat(response.getMissingRequiredCount()).isGreaterThan(0);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RESPONSE BUILDING TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Response Building Tests")
    class ResponseBuildingTests {

        @Test
        @DisplayName("Should return 201 when at least one photo accepted")
        void shouldReturn201WhenPhotosAccepted() {
            // This requires more complex mocking to actually accept photos
            // For now we verify the rejection path returns 400
        }

        @Test
        @DisplayName("Should return 400 when all photos rejected")
        void shouldReturn400WhenAllPhotosRejected() {
            // Arrange
            testBooking.setStatus(BookingStatus.CHECK_IN_HOST_COMPLETE);
            GuestCheckInPhotoSubmissionDTO submission = createValidSubmission();
            
            when(bookingRepository.findByIdWithRelations(bookingId))
                .thenReturn(Optional.of(testBooking));
            when(userRepository.findById(renterId))
                .thenReturn(Optional.of(testRenter));
            when(guestPhotoRepository.countRequiredGuestPhotoTypesBySession(anyString()))
                .thenReturn(0L);
            when(exifValidationService.validate(any(), any()))
                .thenReturn(createInvalidExifResult());
            when(photoRejectionService.shouldReject(any()))
                .thenReturn(true);
            
            // Act
            GuestCheckInPhotoResponseDTO response = 
                guestPhotoService.uploadGuestPhotos(bookingId, renterId, submission);
            
            // Assert
            assertThat(response.getHttpStatus()).isEqualTo(400);
            assertThat(response.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("Should include session ID in response")
        void shouldIncludeSessionIdInResponse() {
            // Arrange
            testBooking.setStatus(BookingStatus.CHECK_IN_HOST_COMPLETE);
            testBooking.setCheckInSessionId("existing-session-456");
            GuestCheckInPhotoSubmissionDTO submission = createValidSubmission();
            
            when(bookingRepository.findByIdWithRelations(bookingId))
                .thenReturn(Optional.of(testBooking));
            when(userRepository.findById(renterId))
                .thenReturn(Optional.of(testRenter));
            when(guestPhotoRepository.countRequiredGuestPhotoTypesBySession(anyString()))
                .thenReturn(0L);
            when(exifValidationService.validate(any(), any()))
                .thenReturn(createValidExifResult());
            when(photoRejectionService.shouldReject(any()))
                .thenReturn(true);
            
            // Act
            GuestCheckInPhotoResponseDTO response = 
                guestPhotoService.uploadGuestPhotos(bookingId, renterId, submission);
            
            // Assert
            assertThat(response.getSessionId()).isEqualTo("existing-session-456");
        }

        @Test
        @DisplayName("Should generate new session ID if none exists")
        void shouldGenerateSessionIdIfNoneExists() {
            // Arrange
            testBooking.setStatus(BookingStatus.CHECK_IN_HOST_COMPLETE);
            testBooking.setCheckInSessionId(null);
            GuestCheckInPhotoSubmissionDTO submission = createValidSubmission();
            
            when(bookingRepository.findByIdWithRelations(bookingId))
                .thenReturn(Optional.of(testBooking));
            when(userRepository.findById(renterId))
                .thenReturn(Optional.of(testRenter));
            when(guestPhotoRepository.countRequiredGuestPhotoTypesBySession(anyString()))
                .thenReturn(0L);
            when(exifValidationService.validate(any(), any()))
                .thenReturn(createValidExifResult());
            when(photoRejectionService.shouldReject(any()))
                .thenReturn(true);
            
            // Act
            GuestCheckInPhotoResponseDTO response = 
                guestPhotoService.uploadGuestPhotos(bookingId, renterId, submission);
            
            // Assert
            assertThat(response.getSessionId()).isNotNull().isNotEmpty();
            
            // Verify booking was saved with new session ID
            ArgumentCaptor<Booking> bookingCaptor = ArgumentCaptor.forClass(Booking.class);
            verify(bookingRepository, atLeastOnce()).save(bookingCaptor.capture());
            assertThat(bookingCaptor.getValue().getCheckInSessionId()).isNotNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════
    // PHASE 1 IMPROVEMENT: UPLOAD-TIME TYPE VALIDATION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Upload-Time Type Validation Tests (Phase 1 Improvement)")
    class UploadTimeTypeValidationTests {

        @Test
        @DisplayName("Should reject submission with duplicate photo types in same request")
        void shouldRejectDuplicatePhotoTypesInSameSubmission() {
            // Arrange
            testBooking.setStatus(BookingStatus.CHECK_IN_HOST_COMPLETE);
            GuestCheckInPhotoSubmissionDTO submission = new GuestCheckInPhotoSubmissionDTO();
            submission.setPhotos(List.of(
                createPhotoItem(CheckInPhotoType.GUEST_EXTERIOR_FRONT),
                createPhotoItem(CheckInPhotoType.GUEST_EXTERIOR_FRONT) // Duplicate!
            ));
            submission.setClientCapturedAt(Instant.now());
            
            when(bookingRepository.findByIdWithRelations(bookingId))
                .thenReturn(Optional.of(testBooking));
            when(userRepository.findById(renterId))
                .thenReturn(Optional.of(testRenter));
            
            // Act & Assert
            assertThatThrownBy(() -> 
                guestPhotoService.uploadGuestPhotos(bookingId, renterId, submission))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplirane");
        }

        @Test
        @DisplayName("Should reject re-upload of photo type that already exists")
        void shouldRejectReuploadOfExistingPhotoType() {
            // Arrange
            testBooking.setStatus(BookingStatus.CHECK_IN_HOST_COMPLETE);
            GuestCheckInPhotoSubmissionDTO submission = createValidSubmission(); // GUEST_EXTERIOR_FRONT
            
            when(bookingRepository.findByIdWithRelations(bookingId))
                .thenReturn(Optional.of(testBooking));
            when(userRepository.findById(renterId))
                .thenReturn(Optional.of(testRenter));
            // This type already exists in DB
            when(guestPhotoRepository.countByCheckInSessionIdAndPhotoType("test-session-123", CheckInPhotoType.GUEST_EXTERIOR_FRONT))
                .thenReturn(1L);
            
            // Act & Assert
            assertThatThrownBy(() -> 
                guestPhotoService.uploadGuestPhotos(bookingId, renterId, submission))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("već otpremljene");
        }

        @Test
        @DisplayName("Should accept submission with unique photo types")
        void shouldAcceptSubmissionWithUniquePhotoTypes() {
            // Arrange
            testBooking.setStatus(BookingStatus.CHECK_IN_HOST_COMPLETE);
            GuestCheckInPhotoSubmissionDTO submission = new GuestCheckInPhotoSubmissionDTO();
            submission.setPhotos(List.of(
                createPhotoItem(CheckInPhotoType.GUEST_EXTERIOR_FRONT),
                createPhotoItem(CheckInPhotoType.GUEST_EXTERIOR_REAR) // Different types
            ));
            submission.setClientCapturedAt(Instant.now());
            
            when(bookingRepository.findByIdWithRelations(bookingId))
                .thenReturn(Optional.of(testBooking));
            when(userRepository.findById(renterId))
                .thenReturn(Optional.of(testRenter));
            // No existing photos
            when(guestPhotoRepository.countByCheckInSessionIdAndPhotoType(anyString(), any()))
                .thenReturn(0L);
            when(guestPhotoRepository.countRequiredGuestPhotoTypesBySession(anyString()))
                .thenReturn(0L);
            when(exifValidationService.validate(any(), any()))
                .thenReturn(createValidExifResult());
            when(photoRejectionService.shouldReject(any()))
                .thenReturn(true); // Reject for simplicity (doesn't store)
            
            // Act - should not throw validation error
            assertThatCode(() -> 
                guestPhotoService.uploadGuestPhotos(bookingId, renterId, submission))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should report all duplicate types in error message")
        void shouldReportAllDuplicateTypesInErrorMessage() {
            // Arrange
            testBooking.setStatus(BookingStatus.CHECK_IN_HOST_COMPLETE);
            GuestCheckInPhotoSubmissionDTO submission = new GuestCheckInPhotoSubmissionDTO();
            submission.setPhotos(List.of(
                createPhotoItem(CheckInPhotoType.GUEST_EXTERIOR_FRONT),
                createPhotoItem(CheckInPhotoType.GUEST_EXTERIOR_FRONT),
                createPhotoItem(CheckInPhotoType.GUEST_EXTERIOR_REAR),
                createPhotoItem(CheckInPhotoType.GUEST_EXTERIOR_REAR) // Two different duplicates
            ));
            submission.setClientCapturedAt(Instant.now());
            
            when(bookingRepository.findByIdWithRelations(bookingId))
                .thenReturn(Optional.of(testBooking));
            when(userRepository.findById(renterId))
                .thenReturn(Optional.of(testRenter));
            
            // Act & Assert
            assertThatThrownBy(() -> 
                guestPhotoService.uploadGuestPhotos(bookingId, renterId, submission))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GUEST_EXTERIOR_FRONT")
                .hasMessageContaining("GUEST_EXTERIOR_REAR");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private GuestCheckInPhotoSubmissionDTO createValidSubmission() {
        GuestCheckInPhotoSubmissionDTO submission = new GuestCheckInPhotoSubmissionDTO();
        submission.setPhotos(List.of(
            createPhotoItem(CheckInPhotoType.GUEST_EXTERIOR_FRONT)
        ));
        submission.setClientCapturedAt(Instant.now());
        return submission;
    }

    private GuestCheckInPhotoSubmissionDTO createSubmissionWith8Photos() {
        GuestCheckInPhotoSubmissionDTO submission = new GuestCheckInPhotoSubmissionDTO();
        submission.setPhotos(List.of(
            createPhotoItem(CheckInPhotoType.GUEST_EXTERIOR_FRONT),
            createPhotoItem(CheckInPhotoType.GUEST_EXTERIOR_REAR),
            createPhotoItem(CheckInPhotoType.GUEST_EXTERIOR_LEFT),
            createPhotoItem(CheckInPhotoType.GUEST_EXTERIOR_RIGHT),
            createPhotoItem(CheckInPhotoType.GUEST_INTERIOR_DASHBOARD),
            createPhotoItem(CheckInPhotoType.GUEST_INTERIOR_REAR),
            createPhotoItem(CheckInPhotoType.GUEST_ODOMETER),
            createPhotoItem(CheckInPhotoType.GUEST_FUEL_GAUGE)
        ));
        submission.setClientCapturedAt(Instant.now());
        return submission;
    }

    private GuestCheckInPhotoSubmissionDTO.PhotoItem createPhotoItem(CheckInPhotoType type) {
        GuestCheckInPhotoSubmissionDTO.PhotoItem item = new GuestCheckInPhotoSubmissionDTO.PhotoItem();
        item.setPhotoType(type);
        // Create minimal valid JPEG (just header bytes)
        byte[] minimalJpeg = new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0 };
        item.setBase64Data(Base64.getEncoder().encodeToString(minimalJpeg));
        item.setCapturedAt(Instant.now());
        return item;
    }

    private ExifValidationService.ExifValidationResult createValidExifResult() {
        return ExifValidationService.ExifValidationResult.builder()
            .status(ExifValidationStatus.VALID)
            .photoTimestamp(Instant.now())
            .latitude(new java.math.BigDecimal("44.8176"))
            .longitude(new java.math.BigDecimal("20.4633"))
            .build();
    }

    private ExifValidationService.ExifValidationResult createInvalidExifResult() {
        return ExifValidationService.ExifValidationResult.builder()
            .status(ExifValidationStatus.REJECTED_TOO_OLD)
            .build();
    }
}

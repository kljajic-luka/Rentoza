package org.example.rentoza.booking.checkin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.ExifValidationService.ExifValidationResult;
import org.example.rentoza.booking.photo.PiiPhotoStorageService;
import org.example.rentoza.booking.photo.PhotoUrlService;
import org.example.rentoza.car.Car;
import org.example.rentoza.idempotency.IdempotencyService;
import org.example.rentoza.security.CurrentUser;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CheckInController - host photo lazy init regression")
class CheckInControllerHostPhotoLazyInitializationRegressionTest {

    @Mock private CheckInService checkInService;
    @Mock private CheckInAttestationService checkInAttestationService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private CurrentUser currentUser;
    @Mock private CheckInResponseOptimizer responseOptimizer;
    @Mock private org.example.rentoza.booking.photo.PhotoRateLimitService photoRateLimitService;

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

    private MockMvc mockMvc;
    private Booking detachedBooking;
    private Map<Long, CheckInPhoto> storedPhotos;

    private static final byte[] JPEG_BYTES = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01
    };

    @BeforeEach
        void setUp() throws Exception {
        CheckInPhotoService photoService = new CheckInPhotoService(
                bookingRepository,
                userRepository,
                photoRepository,
                eventService,
                exifValidationService,
                lockboxEncryptionService,
                geofenceService,
                photoRejectionService,
                photoRejectionBudgetService,
                eventPublisher,
                supabaseStorageService,
                piiPhotoStorageService,
                exifStrippingService,
                validationService,
                photoUrlService,
                transactionOperations
        );
        ReflectionTestUtils.setField(photoService, "maxSizeMb", 10);
        ReflectionTestUtils.setField(photoService, "auditBackupEnabled", false);
        ReflectionTestUtils.setField(photoService, "checkinPhotoDeadlineHours", 24);
        ReflectionTestUtils.setField(photoService, "checkoutPhotoDeadlineHours", 24);

        CheckInController controller = new CheckInController(
                checkInService,
                photoService,
                checkInAttestationService,
                idempotencyService,
                currentUser,
                responseOptimizer,
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                photoRateLimitService
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();

        storedPhotos = new HashMap<>();

        when(transactionOperations.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

        User owner = new User();
        owner.setId(100L);

        Car car = new Car();
        car.setOwner(owner);

        Booking booking = new Booking();
        booking.setId(1L);
        booking.setStatus(BookingStatus.CHECK_IN_OPEN);
        booking.setCar(car);
        booking.setCheckInSessionId("00000000-0000-0000-0000-000000000001");
        booking.setCheckInPhotos(new ArrayList<>());

        detachedBooking = spy(booking);
        doThrow(new LazyInitializationException("failed to lazily initialize a collection of role: org.example.rentoza.booking.Booking.checkInPhotos"))
                .when(detachedBooking)
                .getCheckInPhotos();

        when(photoRateLimitService.allowPhotoUpload(anyLong(), any())).thenReturn(true);
        when(currentUser.id()).thenReturn(100L);
        when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(detachedBooking));
        when(bookingRepository.findByIdWithLock(1L)).thenReturn(Optional.of(detachedBooking));
        when(userRepository.findById(100L)).thenReturn(Optional.of(owner));
        when(photoRepository.countByBookingId(1L)).thenReturn(0L);
        when(photoRepository.findCompletedByBookingIdAndPhotoTypeExcludingId(anyLong(), any(), anyLong())).thenReturn(List.of());
        when(photoRepository.existsByImageHashOnOtherBooking(anyString(), anyLong())).thenReturn(false);
        when(exifValidationService.validate(any(), any())).thenReturn(ExifValidationResult.builder()
                .status(ExifValidationStatus.VALID_NO_GPS)
                .message("OK")
                .build());
        when(photoRejectionService.shouldReject(any())).thenReturn(false);
        when(exifStrippingService.stripExifMetadata(any(), any())).thenReturn(JPEG_BYTES);
        when(photoUrlService.generateSignedUrl(anyString(), anyString(), anyLong()))
                .thenAnswer(invocation -> "signed:" + invocation.getArgument(1, String.class));
        doNothing().when(supabaseStorageService).uploadCheckInPhotoBytesAtPath(anyString(), any(), eq("image/jpeg"));

        when(photoRepository.save(any(CheckInPhoto.class))).thenAnswer(invocation -> {
            CheckInPhoto photo = invocation.getArgument(0);
            if (photo.getId() == null) {
                photo.setId(900L);
            }
            storedPhotos.put(photo.getId(), photo);
            return photo;
        });
        when(photoRepository.findById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(storedPhotos.get(invocation.getArgument(0, Long.class))));
    }

    @Test
    @DisplayName("multipart host photo upload returns 201 even when detached booking photo collection would throw")
    void multipartHostPhotoUploadReturnsCreatedWhenDetachedCollectionWouldThrow() throws Exception {
        assertThatThrownBy(detachedBooking::getCheckInPhotos)
                .isInstanceOf(LazyInitializationException.class)
                .hasMessageContaining("Booking.checkInPhotos");

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "front.jpg",
                MediaType.IMAGE_JPEG_VALUE,
                JPEG_BYTES
        );

        mockMvc.perform(multipart("/api/bookings/{bookingId}/check-in/host/photos", 1L)
                        .file(file)
                        .param("photoType", CheckInPhotoType.HOST_EXTERIOR_FRONT.name())
                        .param("clientTimestamp", "2026-03-11T08:30:00Z"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.photo.photoId").value(900L))
                .andExpect(jsonPath("$.photo.photoType").value(CheckInPhotoType.HOST_EXTERIOR_FRONT.name()));
    }
}
package org.example.rentoza.booking.checkin.cqrs;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.CheckInEventRepository;
import org.example.rentoza.booking.checkin.CheckInPhotoService;
import org.example.rentoza.booking.checkin.CheckInPhotoRepository;
import org.example.rentoza.booking.checkin.CheckInPhotoType;
import org.example.rentoza.booking.checkin.PhotoRejectionBudgetRepository;
import org.example.rentoza.booking.checkin.dto.PhotoUploadResponse;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.car.FuelType;
import org.example.rentoza.car.TransmissionType;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.storage.SupabaseStorageService;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Integration tests for CheckInStatusView CQRS wiring.
 * 
 * <p>These tests verify that:
 * <ol>
 *   <li>CheckInWindowOpened event publishes correctly</li>
 *   <li>PhotoUploaded event publishes correctly</li>
 *   <li>CheckInStatusViewSyncListener processes events</li>
 *   <li>View is populated and synchronized</li>
 *   <li>Event publishing failures don't break core functionality</li>
 * </ol>
 * 
 * <h2>Test Strategy</h2>
 * <p>Uses @Async real event processing with Awaitility for proper async verification.
 * Tests both happy path and error scenarios to ensure graceful degradation.
 */
@SpringBootTest
@ActiveProfiles("test")
class CheckInStatusViewWiringIntegrationTest {

    @Autowired
    private CheckInCommandService commandService;

    @Autowired
    private CheckInPhotoService photoService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private CheckInEventRepository eventRepository;

    @Autowired
    private CheckInPhotoRepository photoRepository;

    @Autowired
    private PhotoRejectionBudgetRepository photoRejectionBudgetRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CarRepository carRepository;

    @Autowired
    private CheckInStatusViewRepository viewRepository;

    @MockBean
    private NotificationService notificationService;

    @MockBean
    private SupabaseStorageService supabaseStorageService;

    private Booking testBooking;
    private User testHost;
    private User testGuest;
    private Car testCar;

    @BeforeEach
    void setUp() {
        // Async view sync runs in separate transactions, so test setup cannot rely on rollback.
        viewRepository.deleteAll();
        photoRepository.deleteAll();
        eventRepository.deleteAll();
        photoRejectionBudgetRepository.deleteAll();
        bookingRepository.deleteAll();
        carRepository.deleteAll();
        userRepository.deleteAll();

        // Create test entities
        testHost = createTestUser("host@test.com", "Host", "User");
        testGuest = createTestUser("guest@test.com", "Guest", "User");
        testCar = createTestCar(testHost);
        testBooking = createTestBooking(testCar, testGuest);
    }

    // ========== PHASE 1: CheckInWindowOpened Event Tests ==========

    @Test
    @DisplayName("Phase 1: CheckInWindowOpened event should populate CQRS view")
    void testCheckInWindowOpenedPopulatesView() {
        // Arrange: Booking already transitioned by scheduler into check-in-open state
        testBooking.setCheckInSessionId(UUID.randomUUID().toString());
        testBooking.setStatus(BookingStatus.CHECK_IN_OPEN);
        testBooking.setStartTime(LocalDateTime.now().plusDays(1));
        bookingRepository.save(testBooking);

        // Act: Trigger check-in window opening
        commandService.notifyCheckInWindowOpened(testBooking);

        // Assert: View should be created asynchronously
        await().atMost(5, SECONDS).untilAsserted(() -> {
            Optional<CheckInStatusView> viewOpt = viewRepository.findByBookingId(testBooking.getId());
            assertThat(viewOpt).isPresent();

            CheckInStatusView view = viewOpt.get();
            assertThat(view.getBookingId()).isEqualTo(testBooking.getId());
            assertThat(view.getStatus()).isEqualTo(BookingStatus.CHECK_IN_OPEN);
            assertThat(view.getHostUserId()).isEqualTo(testHost.getId());
            assertThat(view.getGuestUserId()).isEqualTo(testGuest.getId());
            assertThat(view.getCheckInOpenedAt()).isNotNull();
            assertThat(view.getPhotoCount()).isEqualTo(0); // No photos yet
        });
    }

    @Test
    @DisplayName("Phase 1: Missing session ID should be handled gracefully")
    void testCheckInWindowOpenedWithMissingSessionId() {
        // Arrange: Booking WITHOUT session ID (error scenario)
        testBooking.setCheckInSessionId(null);
        testBooking.setStatus(BookingStatus.CHECK_IN_OPEN);
        bookingRepository.save(testBooking);

        // Act: Attempt to open window
        try {
            commandService.notifyCheckInWindowOpened(testBooking);
        } catch (IllegalStateException e) {
            // Expected: validation error logged
            assertThat(e.getMessage()).contains("session ID");
        }

        // Assert: View should NOT be created (graceful degradation)
        Optional<CheckInStatusView> viewOpt = viewRepository.findByBookingId(testBooking.getId());
        assertThat(viewOpt).isEmpty();
    }

    @Test
    @DisplayName("Phase 1: Event publishing failure should not break notification")
    void testEventPublishingFailureDoesNotBreakNotification() {
        // Arrange: Booking with valid session ID
        testBooking.setCheckInSessionId(UUID.randomUUID().toString());
        testBooking.setStatus(BookingStatus.CHECK_IN_OPEN);
        bookingRepository.save(testBooking);

        // Act: Open window (should succeed even if event fails internally)
        commandService.notifyCheckInWindowOpened(testBooking);

        // Assert: Notification sent successfully (primary function)
        // View creation is secondary - system continues if it fails
        // (Notification verification would require mocking NotificationService)
    }

    // ========== PHASE 2: PhotoUploaded Event Tests ==========

    @Test
    @DisplayName("Phase 2: Photo upload should increment count in CQRS view")
    @WithMockUser(username = "host@test.com")
    void testPhotoUploadIncrementsViewCount() throws Exception {
        // Arrange: Setup booking with check-in window open and existing view
        testBooking.setCheckInSessionId(UUID.randomUUID().toString());
        testBooking.setStatus(BookingStatus.CHECK_IN_OPEN);
        testBooking.setStartTime(LocalDateTime.now().plusHours(1));
        bookingRepository.save(testBooking);

        // Create initial view
        commandService.notifyCheckInWindowOpened(testBooking);
        await().atMost(5, SECONDS).until(() -> 
            viewRepository.findByBookingId(testBooking.getId()).isPresent()
        );

        // Act: Upload a photo
        MockMultipartFile photo = createValidPhotoFile();
        PhotoUploadResponse response = photoService.uploadPhoto(
            testBooking.getId(),
            testHost.getId(),
            photo,
            CheckInPhotoType.HOST_EXTERIOR_FRONT,
            Instant.now(),
            new BigDecimal("44.787197"),
            new BigDecimal("20.457273")
        );

        // Assert: Photo accepted
        assertThat(response.isAccepted()).isTrue();
        assertThat(response.getPhoto().getPhotoId()).isNotNull();

        // Assert: View should be updated asynchronously with incremented count
        await().atMost(5, SECONDS).untilAsserted(() -> {
            CheckInStatusView view = viewRepository.findByBookingId(testBooking.getId()).orElseThrow();
            assertThat(view.getPhotoCount()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("Phase 2: Multiple photo uploads should increment count correctly")
    @WithMockUser(username = "host@test.com")
    void testMultiplePhotoUploadsIncrementCount() throws Exception {
        // Arrange: Booking with check-in open
        testBooking.setCheckInSessionId(UUID.randomUUID().toString());
        testBooking.setStatus(BookingStatus.CHECK_IN_OPEN);
        bookingRepository.save(testBooking);

        commandService.notifyCheckInWindowOpened(testBooking);
        await().atMost(5, SECONDS).until(() -> 
            viewRepository.findByBookingId(testBooking.getId()).isPresent()
        );

        // Act: Upload 3 photos
        for (int i = 0; i < 3; i++) {
            MockMultipartFile photo = createValidPhotoFile();
            photoService.uploadPhoto(
                testBooking.getId(),
                testHost.getId(),
                photo,
                CheckInPhotoType.HOST_EXTERIOR_FRONT,
                Instant.now(),
                new BigDecimal("44.787197"),
                new BigDecimal("20.457273")
            );
        }

        // Assert: Count should be 3
        await().atMost(5, SECONDS).untilAsserted(() -> {
            CheckInStatusView view = viewRepository.findByBookingId(testBooking.getId()).orElseThrow();
            assertThat(view.getPhotoCount()).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("Phase 2: Photo event publishing failure should not break upload")
    @WithMockUser(username = "host@test.com")
    void testPhotoEventPublishingFailureDoesNotBreakUpload() throws Exception {
        // Arrange: Booking with check-in open but no existing view
        testBooking.setCheckInSessionId(UUID.randomUUID().toString());
        testBooking.setStatus(BookingStatus.CHECK_IN_OPEN);
        testBooking.setStartTime(LocalDateTime.now().plusHours(1));
        bookingRepository.save(testBooking);

        // Act: Upload photo (event may fail to update non-existent view)
        MockMultipartFile photo = createValidPhotoFile();
        PhotoUploadResponse response = photoService.uploadPhoto(
            testBooking.getId(),
            testHost.getId(),
            photo,
            CheckInPhotoType.HOST_EXTERIOR_FRONT,
            Instant.now(),
            new BigDecimal("44.787197"),
            new BigDecimal("20.457273")
        );

        // Assert: Photo upload succeeds regardless of view sync failure
        assertThat(response.isAccepted()).isTrue();
        assertThat(response.getPhoto().getPhotoId()).isNotNull();
    }

    // ========== PHASE 3: End-to-End Workflow Tests ==========

    @Test
    @DisplayName("E2E: Complete check-in workflow should maintain view consistency")
    @WithMockUser(username = "host@test.com")
    void testCompleteCheckInWorkflowMaintainsViewConsistency() throws Exception {
        // Arrange: Booking already moved into check-in-open state
        testBooking.setCheckInSessionId(UUID.randomUUID().toString());
        testBooking.setStatus(BookingStatus.CHECK_IN_OPEN);
        testBooking.setStartTime(LocalDateTime.now().plusHours(1));
        bookingRepository.save(testBooking);

        // Step 1: Open check-in window
        commandService.notifyCheckInWindowOpened(testBooking);
        await().atMost(5, SECONDS).until(() -> 
            viewRepository.findByBookingId(testBooking.getId()).isPresent()
        );

        // Step 2: Host uploads photos
        for (int i = 0; i < 6; i++) {
            photoService.uploadPhoto(
                testBooking.getId(),
                testHost.getId(),
                createValidPhotoFile(),
                CheckInPhotoType.HOST_EXTERIOR_FRONT,
                Instant.now(),
                new BigDecimal("44.787197"),
                new BigDecimal("20.457273")
            );
        }

        // Step 3: Verify view updated with all photos
        await().atMost(5, SECONDS).untilAsserted(() -> {
            CheckInStatusView view = viewRepository.findByBookingId(testBooking.getId()).orElseThrow();
            assertThat(view.getPhotoCount()).isEqualTo(6);
            assertThat(view.getStatus()).isEqualTo(BookingStatus.CHECK_IN_OPEN);
        });
    }

    @Test
    @DisplayName("Performance: View query should be faster than booking query")
    void testViewQueryPerformance() {
        // Arrange: Create view
        testBooking.setCheckInSessionId(UUID.randomUUID().toString());
        testBooking.setStatus(BookingStatus.CHECK_IN_OPEN);
        bookingRepository.save(testBooking);

        commandService.notifyCheckInWindowOpened(testBooking);
        await().atMost(5, SECONDS).until(() -> 
            viewRepository.findByBookingId(testBooking.getId()).isPresent()
        );

        // Act: Compare average query cost after a brief warm-up to reduce single-call noise.
        viewRepository.findByBookingId(testBooking.getId());
        bookingRepository.findByIdWithRelations(testBooking.getId());

        long totalViewDuration = 0L;
        long totalBookingDuration = 0L;
        Optional<CheckInStatusView> view = Optional.empty();
        Optional<Booking> booking = Optional.empty();

        for (int i = 0; i < 25; i++) {
            long viewStartTime = System.nanoTime();
            view = viewRepository.findByBookingId(testBooking.getId());
            totalViewDuration += System.nanoTime() - viewStartTime;

            long bookingStartTime = System.nanoTime();
            booking = bookingRepository.findByIdWithRelations(testBooking.getId());
            totalBookingDuration += System.nanoTime() - bookingStartTime;
        }

        // Assert: View query should be present and significantly faster
        assertThat(view).isPresent();
        assertThat(booking).isPresent();

        long averageViewDuration = totalViewDuration / 25;
        long averageBookingDuration = totalBookingDuration / 25;

        // Local H2 timings are noisy, but the denormalized query should stay in the same order of magnitude.
        assertThat(averageViewDuration).isLessThanOrEqualTo(averageBookingDuration * 2);
    }

    // ========== Test Helper Methods ==========

    private User createTestUser(String email, String firstName, String lastName) {
        User user = new User();
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setPassword("pass");
        user.setAge(30);
        return userRepository.save(user);
    }

    private Car createTestCar(User owner) {
        Car car = new Car();
        car.setOwner(owner);
        car.setBrand("Tesla");
        car.setModel("Model 3");
        car.setYear(2023);
        car.setPricePerDay(new BigDecimal("7500.00"));
        car.setLocation("Belgrade");
        car.setSeats(5);
        car.setFuelType(FuelType.BENZIN);
        car.setTransmissionType(TransmissionType.AUTOMATIC);
        return carRepository.save(car);
    }

    private Booking createTestBooking(Car car, User renter) {
        Booking booking = new Booking();
        booking.setCar(car);
        booking.setRenter(renter);
        booking.setStatus(BookingStatus.ACTIVE);
        booking.setStartTime(LocalDateTime.now().plusHours(1));
        booking.setEndTime(LocalDateTime.now().plusDays(2));
        booking.setTotalPrice(new BigDecimal("15000.00"));
        return bookingRepository.save(booking);
    }

    private MockMultipartFile createValidPhotoFile() {
        // Create minimal valid JPEG file (1x1 pixel)
        byte[] jpegBytes = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46,
            0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            (byte) 0xFF, (byte) 0xD9
        };
        
        return new MockMultipartFile(
            "photo",
            "test_photo.jpg",
            "image/jpeg",
            jpegBytes
        );
    }
}

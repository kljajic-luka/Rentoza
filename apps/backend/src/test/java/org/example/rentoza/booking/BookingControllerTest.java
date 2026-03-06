package org.example.rentoza.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.rentoza.booking.dto.BookingRequestDTO;
import org.example.rentoza.booking.dto.BookingResponseDTO;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarBookingSettings;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.exception.BookingConflictException;
import org.example.rentoza.security.supabase.SupabaseJwtUtil;
import org.example.rentoza.user.DriverLicenseStatus;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * API tests for BookingController endpoints.
 * 
 * <h2>Endpoints Tested</h2>
 * <ul>
 *   <li>POST /api/bookings - Create booking</li>
 *   <li>GET /api/bookings/{id} - Get booking by ID</li>
 *   <li>GET /api/bookings/me - Get user's bookings</li>
 *   <li>POST /api/bookings/{id}/cancel - Cancel booking</li>
 *   <li>PUT /api/bookings/{id}/approve - Host approves booking</li>
 *   <li>PUT /api/bookings/{id}/decline - Host declines booking</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BookingControllerTest {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private CarRepository carRepository;

    @Autowired
    private UserRepository userRepository;

    @MockBean
    private SupabaseJwtUtil supabaseJwtUtil;

    private User testRenter;
    private User testHost;
    private Car testCar;

    @BeforeEach
    void setUp() {
        // Clean up test data
        bookingRepository.deleteAll();

        // Create test host
        testHost = new User();
        testHost.setEmail("host@test.com");
        testHost.setFirstName("Test");
        testHost.setLastName("Host");
        testHost.setAge(30);
        testHost = userRepository.save(testHost);

        // Create test renter
        testRenter = new User();
        testRenter.setEmail("renter@test.com");
        testRenter.setFirstName("Test");
        testRenter.setLastName("Renter");
        testRenter.setAge(25);
        testRenter.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);
        testRenter = userRepository.save(testRenter);

        // Create test car
        testCar = new Car();
        testCar.setOwner(testHost);
        testCar.setBrand("Tesla");
        testCar.setModel("Model 3");
        testCar.setYear(2023);
        testCar.setPricePerDay(BigDecimal.valueOf(5000));
        testCar.setAvailable(true);
        
        CarBookingSettings settings = new CarBookingSettings();
        settings.setAdvanceNoticeHours(1);
        settings.setMinTripHours(24);
        settings.setMaxTripDays(30);
        testCar.setBookingSettings(settings);
        
        testCar = carRepository.save(testCar);
    }

    // ========================================================================
    // CREATE BOOKING TESTS
    // ========================================================================

    @Nested
    @DisplayName("POST /api/bookings - Create Booking")
    class CreateBookingTests {

        @Test
        @WithMockUser(username = "renter@test.com")
        @DisplayName("Should create booking with valid request")
        void shouldCreateBookingWithValidRequest() throws Exception {
            // Given
            BookingRequestDTO request = createValidBookingRequest();

            // When
            ResultActions result = mockMvc.perform(post("/api/bookings")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)));

            // Then
                result.andExpect(status().isCreated())
                    .andExpect(header().exists("Location"))
                    .andExpect(jsonPath("$.carId").value(testCar.getId()))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        @Test
        @WithMockUser(username = "renter@test.com")
        @DisplayName("Should return 400 for invalid dates")
        void shouldReturn400ForInvalidDates() throws Exception {
            // Given: End time before start time
            BookingRequestDTO request = createValidBookingRequest();
            request.setEndTime(request.getStartTime().minusDays(1));

            // When
            ResultActions result = mockMvc.perform(post("/api/bookings")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)));

            // Then
            result.andExpect(status().isBadRequest());
        }

        @Test
        @WithMockUser(username = "renter@test.com")
        @DisplayName("Should return 400 for trip shorter than minimum")
        void shouldReturn400ForTripShorterThanMinimum() throws Exception {
            // Given: 12-hour trip (minimum is 24)
            BookingRequestDTO request = createValidBookingRequest();
            request.setEndTime(request.getStartTime().plusHours(12));

            // When
            ResultActions result = mockMvc.perform(post("/api/bookings")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)));

            // Then
            result.andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("Minimalno")));
        }

        @Test
        @DisplayName("Should return 401 for unauthenticated request")
        void shouldReturn401ForUnauthenticatedRequest() throws Exception {
            // Given
            BookingRequestDTO request = createValidBookingRequest();

            // When
            ResultActions result = mockMvc.perform(post("/api/bookings")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)));

            // Then
            result.andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(username = "renter@test.com")
        @DisplayName("Should return 409 when car already booked")
        void shouldReturn409WhenCarAlreadyBooked() throws Exception {
            // Given: Create existing booking
            Booking existingBooking = new Booking();
            existingBooking.setCar(testCar);
            existingBooking.setRenter(testRenter);
            LocalDateTime start = LocalDateTime.now(SERBIA_ZONE).plusDays(2).withHour(10);
            existingBooking.setStartTime(start);
            existingBooking.setEndTime(start.plusDays(3));
            existingBooking.setStatus(BookingStatus.ACTIVE);
            existingBooking.setTotalPrice(BigDecimal.valueOf(15000));
            bookingRepository.save(existingBooking);

            // Try to book same dates
            BookingRequestDTO request = new BookingRequestDTO();
            request.setCarId(testCar.getId());
            request.setStartTime(start.plusHours(2)); // Overlapping
            request.setEndTime(start.plusDays(2));

            // When
            ResultActions result = mockMvc.perform(post("/api/bookings")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)));

            // Then
            result.andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message", containsString("already booked")));
        }
    }

    // ========================================================================
    // GET BOOKING TESTS
    // ========================================================================

    @Nested
    @DisplayName("GET /api/bookings/{id} - Get Booking")
    class GetBookingTests {

        @Test
        @WithMockUser(username = "renter@test.com")
        @DisplayName("Should return booking for authorized user")
        void shouldReturnBookingForAuthorizedUser() throws Exception {
            // Given: Create booking
            Booking booking = createTestBooking();

            // When
            ResultActions result = mockMvc.perform(get("/api/bookings/" + booking.getId())
                    .accept(MediaType.APPLICATION_JSON));

            // Then
            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(booking.getId()))
                    .andExpect(jsonPath("$.carId").value(testCar.getId()));
        }

        @Test
        @WithMockUser(username = "renter@test.com")
        @DisplayName("Should return 404 for non-existent booking")
        void shouldReturn404ForNonExistentBooking() throws Exception {
            // When
            ResultActions result = mockMvc.perform(get("/api/bookings/999999")
                    .accept(MediaType.APPLICATION_JSON));

            // Then
            result.andExpect(status().isNotFound());
        }

        @Test
        @WithMockUser(username = "other@test.com")
        @DisplayName("Should return 403 for unauthorized user")
        void shouldReturn403ForUnauthorizedUser() throws Exception {
            // Given: Create booking owned by different user
            Booking booking = createTestBooking();

            // When: Different user tries to access
            ResultActions result = mockMvc.perform(get("/api/bookings/" + booking.getId())
                    .accept(MediaType.APPLICATION_JSON));

            // Then
            result.andExpect(status().isForbidden());
        }
    }

    // ========================================================================
    // LIST BOOKINGS TESTS
    // ========================================================================

    @Nested
    @DisplayName("GET /api/bookings/me - List User Bookings")
    class ListUserBookingsTests {

        @Test
        @WithMockUser(username = "renter@test.com")
        @DisplayName("Should return user's bookings")
        void shouldReturnUsersBookings() throws Exception {
            // Given: Create 3 bookings
            createTestBooking();
            createTestBooking();
            createTestBooking();

            // When
            ResultActions result = mockMvc.perform(get("/api/bookings/me")
                    .accept(MediaType.APPLICATION_JSON));

            // Then
            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(3)));
        }

        @Test
        @WithMockUser(username = "renter@test.com")
        @DisplayName("Should return empty list when no bookings")
        void shouldReturnEmptyListWhenNoBookings() throws Exception {
            // When
            ResultActions result = mockMvc.perform(get("/api/bookings/me")
                    .accept(MediaType.APPLICATION_JSON));

            // Then
            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    // ========================================================================
    // CANCEL BOOKING TESTS
    // ========================================================================

    @Nested
    @DisplayName("POST /api/bookings/{id}/cancel - Cancel Booking")
    class CancelBookingTests {

        @Test
        @WithMockUser(username = "renter@test.com")
        @DisplayName("Should cancel booking successfully")
        void shouldCancelBookingSuccessfully() throws Exception {
            // Given
            Booking booking = createTestBooking();

            // When
            ResultActions result = mockMvc.perform(post("/api/bookings/" + booking.getId() + "/cancel")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\": \"PLANS_CHANGED\", \"notes\": \"Travel plans changed\"}"));

            // Then
            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.refundToGuest").exists());
        }

        @Test
        @WithMockUser(username = "other@test.com")
        @DisplayName("Should return 403 when cancelling other user's booking")
        void shouldReturn403WhenCancellingOtherUsersBooking() throws Exception {
            // Given
            Booking booking = createTestBooking();

            // When: Different user tries to cancel
            ResultActions result = mockMvc.perform(post("/api/bookings/" + booking.getId() + "/cancel")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\": \"PLANS_CHANGED\"}"));

            // Then
            result.andExpect(status().isForbidden());
        }
    }

    // ========================================================================
    // HOST APPROVAL TESTS
    // ========================================================================

    @Nested
    @DisplayName("PUT /api/bookings/{id}/approve - Host Approval")
    class HostApprovalTests {

        @Test
        @WithMockUser(username = "host@test.com")
        @DisplayName("Should approve pending booking")
        void shouldApprovePendingBooking() throws Exception {
            // Given: Create pending booking
            Booking booking = createTestBooking();
            booking.setStatus(BookingStatus.PENDING_APPROVAL);
            bookingRepository.save(booking);

            // When
            ResultActions result = mockMvc.perform(put("/api/bookings/" + booking.getId() + "/approve")
                    .with(csrf()));

            // Then
            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        @Test
        @WithMockUser(username = "host@test.com")
        @DisplayName("Should decline pending booking")
        void shouldDeclinePendingBooking() throws Exception {
            // Given: Create pending booking
            Booking booking = createTestBooking();
            booking.setStatus(BookingStatus.PENDING_APPROVAL);
            bookingRepository.save(booking);

            // When: Decline uses PUT with reason as query param
            ResultActions result = mockMvc.perform(put("/api/bookings/" + booking.getId() + "/decline")
                    .with(csrf())
                    .param("reason", "Car unavailable"));

            // Then
            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DECLINED"));
        }

        @Test
        @WithMockUser(username = "renter@test.com")
        @DisplayName("Should return 403 when non-host tries to approve")
        void shouldReturn403WhenNonHostTriesToApprove() throws Exception {
            // Given
            Booking booking = createTestBooking();
            booking.setStatus(BookingStatus.PENDING_APPROVAL);
            bookingRepository.save(booking);

            // When: Renter (not host) tries to approve
            ResultActions result = mockMvc.perform(put("/api/bookings/" + booking.getId() + "/approve")
                    .with(csrf()));

            // Then
            result.andExpect(status().isForbidden());
        }
    }

    // ========================================================================
    // VALIDATION ERROR TESTS
    // ========================================================================

    @Nested
    @DisplayName("Validation Errors")
    class ValidationErrorTests {

        @Test
        @WithMockUser(username = "renter@test.com")
        @DisplayName("Should return 400 for missing carId")
        void shouldReturn400ForMissingCarId() throws Exception {
            // Given: Request without carId
            BookingRequestDTO request = createValidBookingRequest();
            request.setCarId(null);

            // When
            ResultActions result = mockMvc.perform(post("/api/bookings")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)));

            // Then
            result.andExpect(status().isBadRequest());
        }

        @Test
        @WithMockUser(username = "renter@test.com")
        @DisplayName("Should return 400 for missing start time")
        void shouldReturn400ForMissingStartTime() throws Exception {
            // Given
            BookingRequestDTO request = createValidBookingRequest();
            request.setStartTime(null);

            // When
            ResultActions result = mockMvc.perform(post("/api/bookings")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)));

            // Then
            result.andExpect(status().isBadRequest());
        }

        @Test
        @WithMockUser(username = "renter@test.com")
        @DisplayName("Should return validation errors in response body")
        void shouldReturnValidationErrorsInResponseBody() throws Exception {
            // Given: Multiple validation errors
            BookingRequestDTO request = new BookingRequestDTO();
            request.setCarId(null);
            request.setStartTime(null);
            request.setEndTime(null);

            // When
            ResultActions result = mockMvc.perform(post("/api/bookings")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)));

            // Then
            result.andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors").exists());
        }
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private BookingRequestDTO createValidBookingRequest() {
        BookingRequestDTO request = new BookingRequestDTO();
        request.setCarId(testCar.getId());
        LocalDateTime start = LocalDateTime.now(SERBIA_ZONE).plusDays(2).withHour(10).withMinute(0);
        request.setStartTime(start);
        request.setEndTime(start.plusDays(3));
        request.setInsuranceType("BASIC");
        request.setPrepaidRefuel(false);
        return request;
    }

    private Booking createTestBooking() {
        Booking booking = new Booking();
        booking.setCar(testCar);
        booking.setRenter(testRenter);
        LocalDateTime start = LocalDateTime.now(SERBIA_ZONE).plusDays(5).withHour(10);
        booking.setStartTime(start);
        booking.setEndTime(start.plusDays(3));
        booking.setStatus(BookingStatus.ACTIVE);
        booking.setTotalPrice(BigDecimal.valueOf(15000));
        booking.setInsuranceType("BASIC");
        return bookingRepository.save(booking);
    }
}

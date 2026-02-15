package org.example.rentoza.booking;

import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarBookingSettings;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.security.JwtUserPrincipal;
import org.example.rentoza.security.supabase.SupabaseJwtUtil;
import org.example.rentoza.user.DriverLicenseStatus;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BookingApprovalDeadlineIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private CarRepository carRepository;

    @Autowired
    private UserRepository userRepository;

    @MockBean
    private SupabaseJwtUtil supabaseJwtUtil;

    private User host;
    private User renter;
    private Car car;

    @BeforeEach
    void setUp() {
        bookingRepository.deleteAll();

        host = new User();
        host.setEmail("host-deadline@test.com");
        host.setFirstName("Host");
        host.setLastName("Deadline");
        host.setAge(35);
        host = userRepository.save(host);

        renter = new User();
        renter.setEmail("renter-deadline@test.com");
        renter.setFirstName("Renter");
        renter.setLastName("Deadline");
        renter.setAge(29);
        renter.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);
        renter = userRepository.save(renter);

        car = new Car();
        car.setOwner(host);
        car.setBrand("Skoda");
        car.setModel("Octavia");
        car.setYear(2022);
        car.setPricePerDay(new BigDecimal("5000.00"));
        car.setAvailable(true);
        car.setLocation("belgrade");

        CarBookingSettings settings = new CarBookingSettings();
        settings.setAdvanceNoticeHours(1);
        settings.setMinTripHours(24);
        settings.setMaxTripDays(30);
        settings.setInstantBookEnabled(false);
        car.setBookingSettings(settings);

        car = carRepository.save(car);
    }

    @Test
    @DisplayName("PUT /api/bookings/{id}/approve after deadline returns 409 and persists EXPIRED_SYSTEM")
    void approveAfterDeadline_returnsConflict_andPersistsSystemExpiry() throws Exception {
        Booking booking = new Booking();
        booking.setCar(car);
        booking.setRenter(renter);
        booking.setStartTime(LocalDateTime.now().plusDays(2));
        booking.setEndTime(LocalDateTime.now().plusDays(5));
        booking.setStatus(BookingStatus.PENDING_APPROVAL);
        booking.setDecisionDeadlineAt(LocalDateTime.now().minusMinutes(5));
        booking.setTotalPrice(new BigDecimal("15000.00"));
        booking = bookingRepository.save(booking);

        JwtUserPrincipal ownerPrincipal = JwtUserPrincipal.create(
                host.getId(),
                host.getEmail(),
                List.of("OWNER")
        );

        mockMvc.perform(put("/api/bookings/{id}/approve", booking.getId())
                        .with(csrf())
                        .with(SecurityMockMvcRequestPostProcessors.user(ownerPrincipal)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", containsString("expired")));

        Booking reloaded = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BookingStatus.EXPIRED_SYSTEM);
        assertThat(reloaded.getPaymentStatus()).isEqualTo("RELEASED");
        assertThat(reloaded.getDeclineReason()).contains("deadline");
        assertThat(reloaded.getDeclinedAt()).isNotNull();
    }
}

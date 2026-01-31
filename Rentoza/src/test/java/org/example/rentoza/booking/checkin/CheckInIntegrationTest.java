package org.example.rentoza.booking.checkin;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.dto.CheckInStatusDTO;
import org.example.rentoza.booking.checkin.dto.HandshakeConfirmationDTO;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.config.FeatureFlags;
import org.example.rentoza.user.DriverLicenseStatus;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import org.springframework.test.context.TestPropertySource;
import org.example.rentoza.car.storage.DocumentStorageStrategy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.generate-ddl=true",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.show-sql=true"
})
class CheckInIntegrationTest {

    @MockBean
    private DocumentStorageStrategy documentStorageStrategy;

    @Autowired
    private CheckInService checkInService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private CarRepository carRepository;

    @MockBean
    private FeatureFlags featureFlags;

    private User renter;
    private User host;
    private Car car;
    private Booking booking;

    @BeforeEach
    void setUp() {
        // Setup scenarios
        host = new User();
        host.setEmail("host@example.com");
        host.setFirstName("Host");
        host.setLastName("User");
        host.setPassword("pass");
        host = userRepository.save(host);

        renter = new User();
        renter.setEmail("renter@example.com");
        renter.setFirstName("Renter");
        renter.setLastName("User");
        renter.setPassword("pass"); // Assuming basic fields
        renter.setAge(25);
        renter.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);
        renter.setDriverLicenseExpiryDate(LocalDate.now().plusYears(1));
        renter = userRepository.save(renter);
        
        car = new Car();
        car.setOwner(host);
        car.setBrand("Fiat");
        car.setModel("Punto");
        car.setYear(2020);
        car.setPricePerDay(java.math.BigDecimal.valueOf(2000));
        car.setLocation("Belgrade");
        car.setSeats(5);
        car.setFuelType(org.example.rentoza.car.FuelType.BENZIN);
        car.setTransmissionType(org.example.rentoza.car.TransmissionType.MANUAL);
        carRepository.save(car);

        booking = new Booking();
        booking.setRenter(renter);
        booking.setCar(car);
        booking.setStatus(BookingStatus.CHECK_IN_COMPLETE);
        booking.setStartTime(LocalDateTime.now().plusHours(1));
        booking.setEndTime(LocalDateTime.now().plusDays(2));
        booking = bookingRepository.save(booking);
    }

    @Test
    @DisplayName("Integration: Handshake blocked when license expires during trip (Strict Check)")
    void testBookingBlockedWhenLicenseExpiresBeforeCheckIn() {
        // Arrange
        // Renter's license expires tomorrow, trip ends in 2 days
        renter.setDriverLicenseExpiryDate(LocalDate.now().plusDays(1)); 
        userRepository.save(renter);

        when(featureFlags.isStrictCheckinEnabled()).thenReturn(true);
        when(featureFlags.isFeatureEnabledForUser(anyLong())).thenReturn(true); // If needed

        HandshakeConfirmationDTO dto = new HandshakeConfirmationDTO();
        dto.setBookingId(booking.getId());
        dto.setConfirmed(true);

        // Act & Assert
        // We expect ValidationException or similar from CheckInService -> RenterVerificationService check
        assertThrows(org.example.rentoza.exception.ValidationException.class, () -> {
            checkInService.confirmHandshake(dto, renter.getId());
        });
    }

    @Test
    @DisplayName("Integration: Handshake allowed when license is valid")
    void testBookingAllowedWhenLicenseValid() {
        // Arrange
        renter.setDriverLicenseExpiryDate(LocalDate.now().plusYears(1));
        userRepository.save(renter);

        when(featureFlags.isStrictCheckinEnabled()).thenReturn(true);

        HandshakeConfirmationDTO dto = new HandshakeConfirmationDTO();
        dto.setBookingId(booking.getId());
        dto.setConfirmed(true);

        // Act
        // This relies on host confirmation too for status change to IN_TRIP 
        // but verify it doesn't throw validation error
        CheckInStatusDTO result = checkInService.confirmHandshake(dto, renter.getId());
        
        // Assert
        assertNotNull(result);
        // Status might still be CHECK_IN_COMPLETE if host hasn't confirmed, 
        // but important part is no exception thrown.
    }
}

package org.example.rentoza.security;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingSecurityServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private CarRepository carRepository;
    @Mock private CurrentUser currentUser;

    private BookingSecurityService service;

    @BeforeEach
    void setUp() {
        service = new BookingSecurityService(bookingRepository, carRepository, currentUser);
    }

    @Test
    @DisplayName("reauthorization access: renter allowed")
    void reauth_access_renter_allowed() {
        Booking booking = booking(10L, 20L);
        when(currentUser.isAdmin()).thenReturn(false);
        when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(booking));

        assertThat(service.canReauthorizeBookingPayment(1L, 10L)).isTrue();
    }

    @Test
    @DisplayName("reauthorization access: host forbidden")
    void reauth_access_host_forbidden() {
        Booking booking = booking(10L, 20L);
        when(currentUser.isAdmin()).thenReturn(false);
        when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(booking));

        assertThat(service.canReauthorizeBookingPayment(1L, 20L)).isFalse();
    }

    @Test
    @DisplayName("reauthorization access: admin allowed")
    void reauth_access_admin_allowed() {
        when(currentUser.isAdmin()).thenReturn(true);

        assertThat(service.canReauthorizeBookingPayment(1L, 999L)).isTrue();
    }

    private Booking booking(Long renterId, Long hostId) {
        User renter = new User();
        renter.setId(renterId);
        User owner = new User();
        owner.setId(hostId);
        Car car = new Car();
        car.setOwner(owner);

        Booking booking = new Booking();
        booking.setRenter(renter);
        booking.setCar(car);
        return booking;
    }
}

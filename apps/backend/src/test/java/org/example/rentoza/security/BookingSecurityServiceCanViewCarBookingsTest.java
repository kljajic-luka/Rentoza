package org.example.rentoza.security;

import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for W5: canViewCarBookings real ownership check.
 */
@ExtendWith(MockitoExtension.class)
class BookingSecurityServiceCanViewCarBookingsTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private CarRepository carRepository;

    @Mock
    private CurrentUser currentUser;

    @InjectMocks
    private BookingSecurityService bookingSecurity;

    @Test
    @DisplayName("W5: Admin can view any car's bookings")
    void adminCanViewAnyCarsBookings() {
        when(currentUser.isAdmin()).thenReturn(true);

        assertThat(bookingSecurity.canViewCarBookings(1L, 999L)).isTrue();
        verify(carRepository, never()).findByIdAndOwnerId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("W5: Car owner can view their car's bookings")
    void ownerCanViewOwnCarsBookings() {
        when(currentUser.isAdmin()).thenReturn(false);
        when(carRepository.findByIdAndOwnerId(1L, 42L)).thenReturn(Optional.of(new Car()));

        assertThat(bookingSecurity.canViewCarBookings(1L, 42L)).isTrue();
    }

    @Test
    @DisplayName("W5: Non-owner cannot view car bookings")
    void nonOwnerCannotViewCarsBookings() {
        when(currentUser.isAdmin()).thenReturn(false);
        when(carRepository.findByIdAndOwnerId(1L, 99L)).thenReturn(Optional.empty());

        assertThat(bookingSecurity.canViewCarBookings(1L, 99L)).isFalse();
    }

    @Test
    @DisplayName("W5: Non-existent car returns false")
    void nonExistentCarReturnsFalse() {
        when(currentUser.isAdmin()).thenReturn(false);
        when(carRepository.findByIdAndOwnerId(999L, 42L)).thenReturn(Optional.empty());

        assertThat(bookingSecurity.canViewCarBookings(999L, 42L)).isFalse();
    }
}

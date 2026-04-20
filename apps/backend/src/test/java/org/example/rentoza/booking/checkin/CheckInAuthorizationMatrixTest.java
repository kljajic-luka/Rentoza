package org.example.rentoza.booking.checkin;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.car.Car;
import org.example.rentoza.security.JwtUserPrincipal;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckInAuthorizationMatrixTest {

    @Mock private BookingRepository bookingRepository;

    private CheckInAuthorization authorization;

    @BeforeEach
    void setUp() {
        authorization = new CheckInAuthorization(bookingRepository);
    }

    @Test
    void shouldEnforceGuestReadUploadAndAdminRecoveryMatrix() {
        Booking booking = booking();
        when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(booking));

        var hostAuth = auth(11L, "ROLE_USER");
        var guestAuth = auth(22L, "ROLE_USER");
        var outsiderAuth = auth(33L, "ROLE_USER");
        var adminAuth = auth(44L, "ROLE_ADMIN");

        assertThat(authorization.canUploadGuestCheckInPhoto(1L, guestAuth)).isTrue();
        assertThat(authorization.canUploadGuestCheckInPhoto(1L, hostAuth)).isFalse();

        assertThat(authorization.canReadGuestCheckInPhoto(1L, hostAuth)).isTrue();
        assertThat(authorization.canReadGuestCheckInPhoto(1L, guestAuth)).isTrue();
        assertThat(authorization.canReadGuestCheckInPhoto(1L, outsiderAuth)).isFalse();

        assertThat(authorization.canReadCheckInAttestation(1L, hostAuth)).isTrue();
        assertThat(authorization.canReadCheckInAttestation(1L, guestAuth)).isTrue();
        assertThat(authorization.canReadCheckInAttestation(1L, outsiderAuth)).isFalse();

        assertThat(authorization.canRunAdminRecovery(adminAuth)).isTrue();
        assertThat(authorization.canRunAdminRecovery(hostAuth)).isFalse();
    }

    private Booking booking() {
        Booking booking = new Booking();
        booking.setId(1L);
        Car car = new Car();
        User host = new User();
        host.setId(11L);
        car.setOwner(host);
        booking.setCar(car);

        User guest = new User();
        guest.setId(22L);
        booking.setRenter(guest);
        return booking;
    }

    private UsernamePasswordAuthenticationToken auth(Long userId, String role) {
        JwtUserPrincipal principal = JwtUserPrincipal.create(userId, "u@example.com", List.of(role.replace("ROLE_", "")));
        return new UsernamePasswordAuthenticationToken(principal, "n/a", List.of(new SimpleGrantedAuthority(role)));
    }
}

package org.example.rentoza.security;

import org.example.rentoza.booking.BookingController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G3: Verifies that canViewCarBookings is exercised through an actual
 * {@link PreAuthorize} evaluation path on the BookingController endpoint.
 *
 * <p>Regression guard for audit finding W5 (commit 318c411).
 * Before G3, the {@code @PreAuthorize} on getBookingsForCar only checked
 * {@code hasAnyRole('OWNER', 'ADMIN')} — canViewCarBookings existed but
 * was never invoked at the authorization layer.
 */
class BookingControllerPreAuthorizeCarBookingsTest {

    @Test
    @DisplayName("G3: getBookingsForCar uses @bookingSecurity.canViewCarBookings in @PreAuthorize")
    void getBookingsForCar_usesCanViewCarBookingsInPreAuthorize() throws Exception {
        Method method = BookingController.class.getDeclaredMethod(
                "getBookingsForCar", Long.class);

        PreAuthorize preAuth = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuth)
                .as("getBookingsForCar must have @PreAuthorize annotation")
                .isNotNull();

        assertThat(preAuth.value())
                .as("@PreAuthorize must delegate to bookingSecurity.canViewCarBookings for ownership check")
                .contains("@bookingSecurity.canViewCarBookings(#carId, authentication.principal.id)");

        assertThat(preAuth.value())
                .as("@PreAuthorize must include admin bypass")
                .contains("hasRole('ADMIN')");
    }

    @Test
    @DisplayName("G3: canViewCarBookings method exists in BookingSecurityService with correct signature")
    void canViewCarBookings_methodExistsWithCorrectSignature() throws Exception {
        Method method = BookingSecurityService.class.getDeclaredMethod(
                "canViewCarBookings", Long.class, Long.class);

        assertThat(method.getReturnType())
                .as("canViewCarBookings must return boolean")
                .isEqualTo(boolean.class);
    }

    @Test
    @DisplayName("G3: BookingSecurityService is registered as 'bookingSecurity' bean")
    void bookingSecurityService_hasCorrectBeanName() {
        var componentAnnotation = BookingSecurityService.class.getAnnotation(
                org.springframework.stereotype.Component.class);

        assertThat(componentAnnotation)
                .as("BookingSecurityService must be a @Component")
                .isNotNull();

        assertThat(componentAnnotation.value())
                .as("Bean name must be 'bookingSecurity' to match SpEL expression")
                .isEqualTo("bookingSecurity");
    }
}

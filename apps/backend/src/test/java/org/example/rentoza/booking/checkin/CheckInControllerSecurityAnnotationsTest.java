package org.example.rentoza.booking.checkin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class CheckInControllerSecurityAnnotationsTest {

    @Test
    @DisplayName("forceOpenCheckInWindow is restricted to admins only")
    void forceOpenCheckInWindow_isRestrictedToAdminsOnly() throws Exception {
        Method method = CheckInController.class.getDeclaredMethod("forceOpenCheckInWindow", Long.class);

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    @DisplayName("mutating endpoints delegate booking actor checks through checkInAuthorization")
    void mutatingEndpoints_delegateActorChecksThroughCheckInAuthorization() throws Exception {
        assertPreAuthorize("completeHostCheckIn", new Class[]{Long.class, org.example.rentoza.booking.checkin.dto.HostCheckInSubmissionDTO.class, String.class},
                "@checkInAuthorization.canManageHostCheckIn(#bookingId, authentication)");
        assertPreAuthorize("confirmLicenseVerifiedInPerson", new Class[]{Long.class, String.class},
                "@checkInAuthorization.canManageHostCheckIn(#bookingId, authentication)");
        assertPreAuthorize("acknowledgeCondition", new Class[]{Long.class, org.example.rentoza.booking.checkin.dto.GuestConditionAcknowledgmentDTO.class, String.class},
                "@checkInAuthorization.canAcknowledgeGuestCondition(#bookingId, authentication)");
        assertPreAuthorize("confirmHandshake", new Class[]{Long.class, org.example.rentoza.booking.checkin.dto.HandshakeConfirmationDTO.class, String.class},
                "@checkInAuthorization.canConfirmHandshake(#bookingId, authentication)");
        assertPreAuthorize("revealLockboxCode", new Class[]{Long.class, Double.class, Double.class},
                "@checkInAuthorization.canRevealLockbox(#bookingId, authentication)");
    }

    private void assertPreAuthorize(String methodName, Class<?>[] parameterTypes, String expectedValue) throws Exception {
        Method method = CheckInController.class.getDeclaredMethod(methodName, parameterTypes);
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize)
                .as(methodName + " must declare @PreAuthorize")
                .isNotNull();
        assertThat(preAuthorize.value())
                .as(methodName + " must enforce the expected actor authorization")
                .isEqualTo(expectedValue);
    }
}
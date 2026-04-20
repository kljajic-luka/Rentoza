package org.example.rentoza.booking.checkin;

import jakarta.servlet.http.HttpServletRequest;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PhotoRejectionBudgetServiceTest {

    @Mock private PhotoRejectionBudgetRepository budgetRepository;
    @Mock private UserRepository userRepository;
    @Mock private CheckInEventService eventService;

    private PhotoRejectionBudgetService service;

    @BeforeEach
    void setUp() {
        service = new PhotoRejectionBudgetService(budgetRepository, userRepository, eventService);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Device-Fingerprint", "device-abc");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldBlockFourthAttemptInsideCooldown() {
        Booking booking = new Booking();
        booking.setId(99L);
        booking.setCheckInSessionId("session-1");

        User user = new User();
        user.setId(10L);

        AtomicReference<PhotoRejectionBudget> state = new AtomicReference<>();

        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        when(budgetRepository.findByBookingIdAndUserIdAndActorRoleAndPhotoTypeAndIpAddressHashAndDeviceFingerprintHash(
                eq(99L), eq(10L), eq(CheckInActorRole.GUEST), eq(CheckInPhotoType.GUEST_EXTERIOR_FRONT), anyString(), anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(state.get()));
        when(budgetRepository.save(any(PhotoRejectionBudget.class))).thenAnswer(invocation -> {
            PhotoRejectionBudget saved = invocation.getArgument(0);
            state.set(saved);
            return saved;
        });

        service.registerRejection(booking, 10L, CheckInActorRole.GUEST, CheckInPhotoType.GUEST_EXTERIOR_FRONT, "NO_EXIF_DATA");
        service.registerRejection(booking, 10L, CheckInActorRole.GUEST, CheckInPhotoType.GUEST_EXTERIOR_FRONT, "NO_EXIF_DATA");
        service.registerRejection(booking, 10L, CheckInActorRole.GUEST, CheckInPhotoType.GUEST_EXTERIOR_FRONT, "NO_EXIF_DATA");

        assertThatThrownBy(() -> service.assertWithinBudget(booking, 10L, CheckInActorRole.GUEST, CheckInPhotoType.GUEST_EXTERIOR_FRONT))
                .isInstanceOf(PhotoRejectionBudgetExceededException.class);

        verify(eventService).recordEvent(
                eq(booking),
                eq("session-1"),
                eq(CheckInEventType.PHOTO_REJECTION_BUDGET_EXCEEDED),
                eq(10L),
                eq(CheckInActorRole.GUEST),
                anyMap()
        );
    }
}

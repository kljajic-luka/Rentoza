package org.example.rentoza.booking.checkin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.dto.CheckInStatusDTO;
import org.example.rentoza.booking.checkin.dto.HandshakeConfirmationDTO;
import org.example.rentoza.idempotency.IdempotencyService;
import org.example.rentoza.idempotency.IdempotencyService.IdempotencyResult;
import org.example.rentoza.idempotency.IdempotencyService.IdempotencyStatus;
import org.example.rentoza.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CheckInController - Cached idempotency response body")
class CheckInControllerCachedIdempotencyResponseTest {

    @Mock private CheckInService checkInService;
    @Mock private CheckInPhotoService photoService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private CurrentUser currentUser;
    @Mock private CheckInResponseOptimizer responseOptimizer;
    @Mock private org.example.rentoza.booking.photo.PhotoRateLimitService photoRateLimitService;

    private ObjectMapper objectMapper;
    private CheckInController controller;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        controller = new CheckInController(
                checkInService, photoService, idempotencyService,
                currentUser, responseOptimizer, objectMapper, new SimpleMeterRegistry(),
                photoRateLimitService);
    }

    @Test
    @DisplayName("confirmHandshake returns cached status body for completed idempotent request")
    void confirmHandshake_returnsCachedStatusBody() throws Exception {
        Long bookingId = 77L;
        Long userId = 200L;
        String key = "550e8400-e29b-41d4-a716-446655440000";

        CheckInStatusDTO cachedStatus = CheckInStatusDTO.builder()
                .bookingId(bookingId)
                .status(BookingStatus.IN_TRIP)
                .handshakeComplete(true)
                .hostConfirmedHandshake(true)
                .guestConfirmedHandshake(true)
                .build();

        IdempotencyResult result = IdempotencyResult.builder()
                .status(IdempotencyStatus.COMPLETED)
                .httpStatus(HttpStatus.OK.value())
                .responseBody(objectMapper.writeValueAsString(cachedStatus))
                .build();

        when(currentUser.id()).thenReturn(userId);
        when(idempotencyService.checkIdempotency(key, userId)).thenReturn(Optional.of(result));

        HandshakeConfirmationDTO request = new HandshakeConfirmationDTO();
        request.setBookingId(bookingId);
        request.setConfirmed(true);

        ResponseEntity<CheckInStatusDTO> response = controller.confirmHandshake(bookingId, request, key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(BookingStatus.IN_TRIP);
        assertThat(response.getBody().isHandshakeComplete()).isTrue();
        verify(checkInService, never()).confirmHandshake(any(), anyLong());
    }

    @Test
    @DisplayName("confirmHandshake returns 409 when idempotency key is still processing")
    void confirmHandshake_returnsConflictWhenProcessing() {
        Long bookingId = 77L;
        Long userId = 200L;
        String key = "550e8400-e29b-41d4-a716-446655440000";

        IdempotencyResult result = IdempotencyResult.builder()
                .status(IdempotencyStatus.PROCESSING)
                .httpStatus(HttpStatus.CONFLICT.value())
                .build();

        when(currentUser.id()).thenReturn(userId);
        when(idempotencyService.checkIdempotency(key, userId)).thenReturn(Optional.of(result));

        HandshakeConfirmationDTO request = new HandshakeConfirmationDTO();
        request.setBookingId(bookingId);
        request.setConfirmed(true);

        ResponseEntity<CheckInStatusDTO> response = controller.confirmHandshake(bookingId, request, key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNull();
        verify(checkInService, never()).confirmHandshake(any(), anyLong());
    }
}

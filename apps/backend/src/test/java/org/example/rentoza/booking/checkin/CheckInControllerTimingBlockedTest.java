package org.example.rentoza.booking.checkin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.checkin.dto.PhotoUploadResponse;
import org.example.rentoza.idempotency.IdempotencyService;
import org.example.rentoza.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests that CheckInController returns HTTP 409 with the correct
 * CHECKIN_TOO_EARLY payload when photo upload is timing-blocked.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CheckInController – Timing blocked 409 response")
class CheckInControllerTimingBlockedTest {

    @Mock private CheckInService checkInService;
    @Mock private CheckInPhotoService photoService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private CurrentUser currentUser;
    @Mock private CheckInResponseOptimizer responseOptimizer;
    @Mock private org.example.rentoza.booking.photo.PhotoRateLimitService photoRateLimitService;

    private CheckInController controller;

    @BeforeEach
    void setUp() {
        when(photoRateLimitService.allowPhotoUpload(anyLong(), any())).thenReturn(true);
        controller = new CheckInController(
                checkInService, photoService, idempotencyService,
                currentUser, responseOptimizer, new ObjectMapper(), new SimpleMeterRegistry(),
                photoRateLimitService);
    }

    @Test
    @DisplayName("uploadHostPhoto returns 409 with CHECKIN_TOO_EARLY when timing blocked")
    void shouldReturn409WithTimingBlockedPayload() throws Exception {
        // Arrange
        long bookingId = 42L;
        LocalDateTime earliestAllowed = LocalDateTime.of(2025, 6, 15, 11, 0);
        long minutesRemaining = 45;

        when(currentUser.id()).thenReturn(1L);
        when(photoService.uploadPhoto(eq(bookingId), eq(1L), any(), any(), any(), any(), any()))
                .thenThrow(new CheckInTimingBlockedException(minutesRemaining, earliestAllowed));

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        // Act
        ResponseEntity<PhotoUploadResponse> response = controller.uploadHostPhoto(
                bookingId, file, CheckInPhotoType.HOST_EXTERIOR_FRONT,
                null, null, null);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();

        PhotoUploadResponse body = response.getBody();
        assertThat(body.isAccepted()).isFalse();
        assertThat(body.getHttpStatus()).isEqualTo(409);
        assertThat(body.getErrorCodes()).containsExactly("CHECKIN_TOO_EARLY");
        assertThat(body.getMinutesUntilAllowed()).isEqualTo(45L);
        assertThat(body.getEarliestAllowedTime()).isEqualTo("2025-06-15T11:00");
        assertThat(body.getUserMessage()).contains("11:00");
    }

    @Test
    @DisplayName("uploadHostPhoto returns 201 for accepted photos (no timing issue)")
    void shouldReturn201ForAcceptedPhotos() throws Exception {
        // Arrange
        long bookingId = 42L;
        PhotoUploadResponse accepted = PhotoUploadResponse.builder()
                .accepted(true)
                .httpStatus(201)
                .userMessage("Fotografija je uspešno sačuvana.")
                .build();

        when(currentUser.id()).thenReturn(1L);
        when(photoService.uploadPhoto(eq(bookingId), eq(1L), any(), any(), any(), any(), any()))
                .thenReturn(accepted);

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        // Act
        ResponseEntity<PhotoUploadResponse> response = controller.uploadHostPhoto(
                bookingId, file, CheckInPhotoType.HOST_EXTERIOR_FRONT,
                null, null, null);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isAccepted()).isTrue();
    }
}

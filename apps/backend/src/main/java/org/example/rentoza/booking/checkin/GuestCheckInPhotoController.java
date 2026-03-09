package org.example.rentoza.booking.checkin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.checkin.dto.CheckInPhotoDTO;
import org.example.rentoza.booking.checkin.dto.GuestCheckInPhotoResponseDTO;
import org.example.rentoza.booking.checkin.dto.GuestCheckInPhotoSubmissionDTO;
import org.example.rentoza.security.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * REST API for guest check-in photo operations (dual-party verification).
 * 
 * <p>When a guest arrives for pickup, they capture photos of the vehicle
 * to confirm the condition matches what the host documented. This creates
 * bilateral photographic evidence for any disputes.
 * 
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>POST /api/bookings/{bookingId}/guest-checkin-photos - Upload guest photos</li>
 *   <li>GET /api/bookings/{bookingId}/guest-checkin-photos - Get all guest photos</li>
 *   <li>GET /api/guest-checkin/photos/{sessionId}/{filename} - Serve photo file</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Guest Check-In Photos", description = "APIs for guest check-in photo capture (dual-party verification)")
public class GuestCheckInPhotoController {

    private final GuestCheckInPhotoService guestPhotoService;
    private final CurrentUser currentUser;
    private final org.example.rentoza.booking.photo.PhotoRateLimitService photoRateLimitService;

    @Value("${app.checkin.photo.upload-dir:uploads/checkin}")
    private String uploadDir;

    /**
     * Upload guest check-in photos for dual-party verification.
     * 
     * <p>When the guest arrives, they capture the same 8 required photos as the host:
     * <ul>
     *   <li>4 exterior angles (front, rear, left, right)</li>
     *   <li>2 interior shots (dashboard, rear seats)</li>
     *   <li>1 odometer reading</li>
     *   <li>1 fuel gauge reading</li>
     * </ul>
     * 
     * <p>The system will automatically detect discrepancies between host and guest photos.
     */
    @PostMapping("/api/bookings/{bookingId}/guest-checkin-photos")
    @PreAuthorize("@checkInAuthorization.canUploadGuestCheckInPhoto(#bookingId, authentication)")
    @Operation(
        summary = "Upload guest check-in photos",
        description = "Guest uploads photos to confirm vehicle condition at pickup. " +
                      "Photos are validated and compared with host photos for discrepancies."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "201",
            description = "Photos uploaded successfully",
            content = @Content(schema = @Schema(implementation = GuestCheckInPhotoResponseDTO.class))
        ),
        @ApiResponse(responseCode = "400", description = "Validation failed or all photos rejected"),
        @ApiResponse(responseCode = "403", description = "User is not the guest for this booking"),
        @ApiResponse(responseCode = "404", description = "Booking not found"),
        @ApiResponse(responseCode = "409", description = "Booking not in correct status for guest photos")
    })
    public ResponseEntity<GuestCheckInPhotoResponseDTO> uploadGuestPhotos(
            @Parameter(description = "Booking ID") @PathVariable Long bookingId,
            @Valid @RequestBody GuestCheckInPhotoSubmissionDTO submission) {

        Long userId = currentUser.id();

        // WI-12: Rate limit photo uploads
        String clientIp = getClientIp();
        if (!photoRateLimitService.allowPhotoUpload(userId, clientIp)) {
            log.warn("[GuestCheckIn] Upload rate limit exceeded: userId={}, ip={}", userId, clientIp);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "600")
                    .build();
        }

        log.info("[GuestCheckIn] Uploading photos: booking={}, user={}, photoCount={}",
            bookingId, userId, submission.getPhotos().size());
        
        GuestCheckInPhotoResponseDTO response = guestPhotoService.uploadGuestPhotos(
            bookingId, userId, submission);
        
        return ResponseEntity.status(response.getHttpStatus()).body(response);
    }

    /**
     * Get all guest check-in photos for a booking.
     */
    @GetMapping("/api/bookings/{bookingId}/guest-checkin-photos")
    @PreAuthorize("@checkInAuthorization.canReadGuestCheckInPhoto(#bookingId, authentication)")
    @Operation(
        summary = "Get guest check-in photos",
        description = "Retrieve all photos uploaded by the guest during check-in. " +
                      "Accessible by both host and guest."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Photos retrieved successfully",
            content = @Content(schema = @Schema(implementation = CheckInPhotoDTO.class))
        ),
        @ApiResponse(responseCode = "403", description = "User doesn't have access to this booking"),
        @ApiResponse(responseCode = "404", description = "Booking not found")
    })
    public ResponseEntity<List<CheckInPhotoDTO>> getGuestPhotos(
            @Parameter(description = "Booking ID") @PathVariable Long bookingId) {
        
        List<CheckInPhotoDTO> photos = guestPhotoService.getGuestPhotos(bookingId, currentUser.id());
        return ResponseEntity.ok(photos);
    }

    @ExceptionHandler(PhotoRejectionBudgetExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRejectionBudgetExceeded(PhotoRejectionBudgetExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(Map.of(
                        "error", "PHOTO_REJECTION_COOLDOWN",
                        "message", ex.getMessage(),
                        "retryAfterSeconds", ex.getRetryAfterSeconds(),
                        "cooldownUntil", ex.getCooldownUntil().atZone(ZoneId.of("Europe/Belgrade")).toString()
                ));
    }

    /**
     * Get client IP address from request context.
     */
    private String getClientIp() {
        try {
            org.springframework.web.context.request.ServletRequestAttributes attributes =
                    (org.springframework.web.context.request.ServletRequestAttributes)
                    org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return "UNKNOWN";
            }
            jakarta.servlet.http.HttpServletRequest request = attributes.getRequest();
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isEmpty()) {
                return xff.split(",")[0].trim();
            }
            return request.getRemoteAddr() != null ? request.getRemoteAddr() : "UNKNOWN";
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }
}

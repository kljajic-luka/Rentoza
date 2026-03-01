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
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

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
    @PreAuthorize("isAuthenticated()")
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
    @PreAuthorize("isAuthenticated()")
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

    /**
     * Serve a guest check-in photo file.
     */
    @GetMapping("/api/guest-checkin/photos/{sessionId}/{filename}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Serve guest check-in photo file")
    public ResponseEntity<Resource> servePhoto(
            @PathVariable String sessionId,
            @PathVariable String filename) {
        
        // Sanitize inputs to prevent directory traversal
        if (containsPathTraversal(sessionId) || containsPathTraversal(filename)) {
            log.warn("[GuestCheckIn] Rejected directory traversal attempt: session={}, file={}", 
                sessionId, filename);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        
        try {
            // Construct the file path
            String guestUploadDir = uploadDir.replace("checkin", "guest-checkin");
            Path filePath = Paths.get(guestUploadDir, sessionId, filename).normalize();
            
            // Verify the path is within uploadDir (extra security)
            Path uploadDirPath = Paths.get(guestUploadDir).toAbsolutePath().normalize();
            Path absoluteFilePath = filePath.toAbsolutePath().normalize();
            
            if (!absoluteFilePath.startsWith(uploadDirPath)) {
                log.warn("[GuestCheckIn] Path escape attempt: {}", filePath);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            
            // Check file exists
            if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
                log.debug("[GuestCheckIn] Photo not found: {}", filePath);
                return ResponseEntity.notFound().build();
            }
            
            // Load file as resource
            Resource resource = new FileSystemResource(filePath);
            
            // Determine content type
            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = "image/jpeg";
            }
            
            log.debug("[GuestCheckIn] Serving photo: {}, type: {}", filePath, contentType);
            
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CACHE_CONTROL, "max-age=3600, public")
                    .body(resource);
                    
        } catch (Exception e) {
            log.error("[GuestCheckIn] Error serving photo: session={}, file={}, error={}",
                sessionId, filename, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Check if a string contains path traversal characters.
     */
    private boolean containsPathTraversal(String input) {
        return input == null ||
               input.contains("..") ||
               input.contains("/") ||
               input.contains("\\") ||
               input.contains("%2e") ||
               input.contains("%2f") ||
               input.contains("%5c");
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

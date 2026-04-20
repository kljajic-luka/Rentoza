package org.example.rentoza.booking.checkout;

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
import org.example.rentoza.booking.checkin.CheckInPhotoRepository;
import org.example.rentoza.booking.checkin.dto.CheckInPhotoDTO;
import org.example.rentoza.booking.checkout.dto.HostCheckoutPhotoResponseDTO;
import org.example.rentoza.booking.checkout.dto.HostCheckoutPhotoSubmissionDTO;
import org.example.rentoza.booking.photo.PhotoAccessService;
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
 * REST API for host checkout photo operations (dual-party verification).
 * 
 * <p>When the vehicle is returned, the host captures photos to verify
 * the return condition matches what the guest documented. This creates
 * bilateral photographic evidence for any damage disputes.
 * 
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>POST /api/bookings/{bookingId}/host-checkout-photos - Upload host checkout photos</li>
 *   <li>GET /api/bookings/{bookingId}/host-checkout-photos - Get all host checkout photos</li>
 *   <li>GET /api/host-checkout/photos/{sessionId}/{filename} - Serve photo file</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Host Checkout Photos", description = "APIs for host checkout photo capture (dual-party verification)")
public class HostCheckoutPhotoController {

    private final HostCheckoutPhotoService hostCheckoutPhotoService;
    private final CurrentUser currentUser;
    private final CheckInPhotoRepository photoRepository;
    private final PhotoAccessService photoAccessService;
    private final org.example.rentoza.booking.photo.PhotoRateLimitService photoRateLimitService;

    @Value("${app.checkout.photo.upload-dir:uploads/checkout}")
    private String uploadDir;

    /**
     * Upload host checkout photos for dual-party verification.
     * 
     * <p>When the vehicle is returned, the host captures the same 8 required photos as the guest:
     * <ul>
     *   <li>4 exterior angles (front, rear, left, right)</li>
     *   <li>2 interior shots (dashboard, rear seats)</li>
     *   <li>1 odometer reading</li>
     *   <li>1 fuel gauge reading</li>
     * </ul>
     * 
     * <p>The system will:
     * <ul>
     *   <li>Detect discrepancies between guest and host checkout photos</li>
     *   <li>Compare with check-in photos to detect condition changes</li>
     *   <li>Flag new damage for review</li>
     * </ul>
     */
    @PostMapping("/api/bookings/{bookingId}/host-checkout-photos")
    @PreAuthorize("isAuthenticated()")
    @Operation(
        summary = "Upload host checkout photos",
        description = "Host uploads photos to verify vehicle return condition. " +
                      "Photos are compared with guest checkout and check-in photos for damage detection."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "201",
            description = "Photos uploaded successfully",
            content = @Content(schema = @Schema(implementation = HostCheckoutPhotoResponseDTO.class))
        ),
        @ApiResponse(responseCode = "400", description = "Validation failed or all photos rejected"),
        @ApiResponse(responseCode = "403", description = "User is not the host for this booking"),
        @ApiResponse(responseCode = "404", description = "Booking not found"),
        @ApiResponse(responseCode = "409", description = "Booking not in correct status for host checkout photos")
    })
    public ResponseEntity<HostCheckoutPhotoResponseDTO> uploadHostCheckoutPhotos(
            @Parameter(description = "Booking ID") @PathVariable Long bookingId,
            @Valid @RequestBody HostCheckoutPhotoSubmissionDTO submission) {

        Long userId = currentUser.id();

        // WI-12: Rate limit photo uploads
        String clientIp = getClientIp();
        if (!photoRateLimitService.allowPhotoUpload(userId, clientIp)) {
            log.warn("[HostCheckout] Upload rate limit exceeded: userId={}, ip={}", userId, clientIp);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "600")
                    .build();
        }

        log.info("[HostCheckout] Uploading photos: booking={}, user={}, photoCount={}",
            bookingId, userId, submission.getPhotos().size());
        
        HostCheckoutPhotoResponseDTO response = hostCheckoutPhotoService.uploadHostCheckoutPhotos(
            bookingId, userId, submission);
        
        return ResponseEntity.status(response.getHttpStatus()).body(response);
    }

    /**
     * Get all host checkout photos for a booking.
     */
    @GetMapping("/api/bookings/{bookingId}/host-checkout-photos")
    @PreAuthorize("isAuthenticated()")
    @Operation(
        summary = "Get host checkout photos",
        description = "Retrieve all photos uploaded by the host during checkout. " +
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
    public ResponseEntity<List<CheckInPhotoDTO>> getHostCheckoutPhotos(
            @Parameter(description = "Booking ID") @PathVariable Long bookingId) {
        
        List<CheckInPhotoDTO> photos = hostCheckoutPhotoService.getHostCheckoutPhotos(
            bookingId, currentUser.id());
        return ResponseEntity.ok(photos);
    }

    /**
     * Serve a host checkout photo file.
     * 
     * <p>Verifies the requesting user is a participant in the booking
     * before serving the photo.
     */
    @GetMapping("/api/host-checkout/photos/{sessionId}/{filename}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Serve host checkout photo file")
    public ResponseEntity<Resource> servePhoto(
            @PathVariable String sessionId,
            @PathVariable String filename) {
        
        Long userId = currentUser.id();
        
        // Sanitize inputs to prevent directory traversal
        if (containsPathTraversal(sessionId) || containsPathTraversal(filename)) {
            log.warn("[HostCheckout] Rejected directory traversal attempt: session={}, file={}, user={}", 
                sessionId, filename, userId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        
        try {
            // Verify participant access
            java.util.List<Long> bookingIds = photoRepository.findBookingIdsBySessionId(sessionId);
            Long bookingId = (bookingIds != null && !bookingIds.isEmpty()) ? bookingIds.get(0) : null;
            
            if (bookingId != null && !photoAccessService.canUserAccessBooking(bookingId, userId)) {
                log.warn("[HostCheckout] Unauthorized photo access: user={}, booking={}, session={}",
                        userId, bookingId, sessionId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            
            // Construct the file path
            String hostCheckoutUploadDir = uploadDir.replace("checkout", "host-checkout");
            Path filePath = Paths.get(hostCheckoutUploadDir, sessionId, filename).normalize();
            
            // Verify the path is within uploadDir (extra security)
            Path uploadDirPath = Paths.get(hostCheckoutUploadDir).toAbsolutePath().normalize();
            Path absoluteFilePath = filePath.toAbsolutePath().normalize();
            
            if (!absoluteFilePath.startsWith(uploadDirPath)) {
                log.warn("[HostCheckout] Path escape attempt: {}", filePath);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            
            // Check file exists
            if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
                log.debug("[HostCheckout] Photo not found: {}", filePath);
                return ResponseEntity.notFound().build();
            }
            
            // Load file as resource
            Resource resource = new FileSystemResource(filePath);
            
            // Determine content type
            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = "image/jpeg";
            }
            
            log.debug("[HostCheckout] Serving photo: {}, user: {}, type: {}", filePath, userId, contentType);
            
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CACHE_CONTROL, "max-age=3600, public")
                    .body(resource);
                    
        } catch (Exception e) {
            log.error("[HostCheckout] Error serving photo: session={}, file={}, error={}",
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

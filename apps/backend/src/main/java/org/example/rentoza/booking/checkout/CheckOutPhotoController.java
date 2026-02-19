package org.example.rentoza.booking.checkout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.checkin.CheckInPhotoRepository;
import org.example.rentoza.booking.photo.PhotoAccessService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Controller for serving checkout photo files.
 * 
 * <p>This endpoint serves uploaded checkout photos from the file system.
 * Photos are organized by session ID (UUID) to prevent conflicts.
 * 
 * <p><b>Security:</b> Verifies requesting user is a booking participant
 * (host or guest) before serving photos.
 * 
 * <h2>URL Pattern</h2>
 * <pre>
 * GET /api/checkout/photos/{sessionId}/{filename}
 * </pre>
 */
@RestController
@RequestMapping("/api/checkout/photos")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class CheckOutPhotoController {

    private final CheckInPhotoRepository photoRepository;
    private final PhotoAccessService photoAccessService;

    // Default to sibling directory of checkin uploads if not specified
    @Value("${app.checkout.photo.upload-dir:uploads/checkout}")
    private String uploadDir;

    /**
     * Serve a checkout photo file.
     * 
     * <p>Verifies the requesting user is a participant in the booking
     * before serving the photo.
     * 
     * @param sessionId The checkout session ID (UUID)
     * @param filename The photo filename
     * @return The photo file as binary response
     */
    @GetMapping("/{sessionId}/{filename}")
    @ResponseBody
    public ResponseEntity<Resource> servePhoto(
            @PathVariable String sessionId,
            @PathVariable String filename) {
        
        // Extract user ID from JWT token
        Long currentUserId = extractCurrentUserId();
        if (currentUserId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        // Sanitize inputs to prevent directory traversal
        if (containsPathTraversal(sessionId) || containsPathTraversal(filename)) {
            log.warn("[CheckOut] Rejected directory traversal attempt: session={}, file={}, user={}", 
                sessionId, filename, currentUserId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        
        try {
            // Verify participant access
            Long bookingId = resolveBookingIdFromSessionId(sessionId);
            if (bookingId != null && !photoAccessService.canUserAccessBooking(bookingId, currentUserId)) {
                log.warn("[CheckOut] Unauthorized photo access: user={}, booking={}, session={}",
                        currentUserId, bookingId, sessionId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            
            // Construct the file path
            Path filePath = Paths.get(uploadDir, sessionId, filename).normalize();
            
            // Verify the path is within uploadDir (extra security)
            Path uploadDirPath = Paths.get(uploadDir).toAbsolutePath().normalize();
            Path absoluteFilePath = filePath.toAbsolutePath().normalize();
            
            if (!absoluteFilePath.startsWith(uploadDirPath)) {
                log.warn("[CheckOut] Path escape attempt: {}", filePath);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            
            // Check file exists
            if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
                log.debug("[CheckOut] Photo not found: {}", filePath);
                return ResponseEntity.notFound().build();
            }
            
            // Load file as resource
            Resource resource = new FileSystemResource(filePath);
            
            // Determine content type
            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = "image/jpeg";
            }
            
            log.debug("[CheckOut] Serving photo: {}, user: {}, type: {}", filePath, currentUserId, contentType);
            
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CACHE_CONTROL, "max-age=3600, public")
                    .body(resource);
                    
        } catch (IOException e) {
            log.error("[CheckOut] Error serving photo: session={}, file={}", sessionId, filename, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Resolve booking ID from a session ID by querying the photo repository.
     */
    private Long resolveBookingIdFromSessionId(String sessionId) {
        List<Long> bookingIds = photoRepository.findBookingIdsBySessionId(sessionId);
        if (bookingIds != null && !bookingIds.isEmpty()) {
            return bookingIds.get(0);
        }
        log.debug("[CheckOut] No booking found for session: {}", sessionId);
        return null;
    }

    /**
     * Extract current user ID from JWT authentication.
     */
    private Long extractCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            try {
                return Long.parseLong(jwt.getClaimAsString("sub"));
            } catch (Exception e) {
                log.warn("[CheckOut] Could not extract user ID from JWT", e);
            }
        }
        return null;
    }

    private boolean containsPathTraversal(String segment) {
        return segment == null ||
               segment.contains("..") ||
               segment.contains("/") ||
               segment.contains("\\") ||
               segment.contains("%2e") || 
               segment.contains("%2f") || 
               segment.contains("%5c");
    }
}

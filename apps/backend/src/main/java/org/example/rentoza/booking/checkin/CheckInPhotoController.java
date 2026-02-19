package org.example.rentoza.booking.checkin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.photo.*;
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
 * Controller for serving check-in photo files.
 * 
 * <p><b>P0-1 FIX:</b> All endpoints now verify the requesting user is a participant
 * in the booking. Previously, any authenticated user could access any photo.
 * 
 * <p><b>P0-3 FIX:</b> Implements rate limiting and audit logging for all photo access.
 * 
 * <h2>URL Pattern</h2>
 * <pre>
 * GET /api/checkin/photos/{sessionId}/{filename}
 * </pre>
 * 
 * <h2>Security</h2>
 * <ul>
 *   <li>Requires authentication</li>
 *   <li>Verifies user is booking host or guest</li>
 *   <li>Enforces booking status rules (when photos are visible)</li>
 *   <li>Rate limits to 100 requests per 10 minutes per user</li>
 *   <li>Logs all access for audit trails</li>
 *   <li>Path traversal attacks blocked</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/checkin/photos")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class CheckInPhotoController {

    @Value("${app.checkin.photo.upload-dir:uploads/checkin}")
    private String uploadDir;

    @Value("${storage.mode:local}")
    private String storageMode;

    private final PhotoAccessService photoAccessService;
    private final PhotoRateLimitService rateLimitService;
    private final PhotoAccessLogService accessLogService;
    private final PhotoUrlService photoUrlService;
    private final CheckInPhotoRepository photoRepository;

    /**
     * Serve a check-in photo file.
     * 
     * <p><b>P0-1 FIX:</b> Verifies user is booking participant before serving photo.
     * <p><b>P0-3 FIX:</b> Applies rate limiting and logs access.
     * 
     * @param sessionId The check-in session ID (UUID)
     * @param filename The photo filename
     * @return The photo file as binary response
     */
    @GetMapping("/{sessionId}/{filename}")
    @ResponseBody
    public ResponseEntity<Resource> servePhoto(
            @PathVariable String sessionId,
            @PathVariable String filename) {
        
        // Extract user ID from JWT token
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long currentUserId = null;
        
        if (auth != null && auth.getPrincipal() instanceof Jwt) {
            Jwt jwt = (Jwt) auth.getPrincipal();
            try {
                currentUserId = Long.parseLong(jwt.getClaimAsString("sub"));
            } catch (Exception e) {
                log.warn("[CheckIn-P0-1] Could not extract user ID from JWT", e);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
        } else {
            log.warn("[CheckIn-P0-1] No JWT authentication found");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        String clientIp = getClientIp();
        
        // Sanitize inputs to prevent directory traversal
        if (containsPathTraversal(sessionId) || containsPathTraversal(filename)) {
            log.warn("[CheckIn-P0-1] Rejected directory traversal attempt: session={}, file={}, user={}", 
                sessionId, filename, currentUserId);
            accessLogService.logPhotoAccessDenied(currentUserId, null, null, 400, "TRAVERSAL_ATTEMPT");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        
        try {
            // P0-3: Apply rate limiting
            if (!rateLimitService.allowPhotoAccess(currentUserId, clientIp)) {
                log.warn("[CheckIn-P0-3] Rate limit exceeded for user: {} from IP: {}", currentUserId, clientIp);
                accessLogService.logRateLimitViolation(currentUserId, null);
                
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "600")
                        .body(null);
            }
            
            // Construct the file path
            Path filePath = Paths.get(uploadDir, sessionId, filename).normalize();
            
            // Verify the path is within uploadDir (extra security)
            Path uploadDirPath = Paths.get(uploadDir).toAbsolutePath().normalize();
            Path absoluteFilePath = filePath.toAbsolutePath().normalize();
            
            if (!absoluteFilePath.startsWith(uploadDirPath)) {
                log.warn("[CheckIn-P0-1] Path escape attempt: {}, user={}", filePath, currentUserId);
                accessLogService.logPhotoAccessDenied(currentUserId, null, null, 403, "PATH_ESCAPE");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            
            // Check file exists
            if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
                log.debug("[CheckIn] Photo not found: {}, user={}", filePath, currentUserId);
                accessLogService.logPhotoAccessDenied(currentUserId, null, null, 404, "NOT_FOUND");
                return ResponseEntity.notFound().build();
            }
            
            // P0-1: Verify user has access to this photo
            // Extract booking ID from session ID lookup (you may need to add a query method)
            Long photoId = extractPhotoIdFromPath(filePath);
            Long bookingId = extractBookingIdFromSessionId(sessionId);
            
            if (!photoAccessService.canUserAccessBooking(bookingId, currentUserId)) {
                log.warn("[CheckIn-P0-1] Unauthorized photo access attempt: user={}, booking={}, session={}", 
                    currentUserId, bookingId, sessionId);
                accessLogService.logPhotoAccessDenied(currentUserId, bookingId, photoId, 403, "NOT_PARTICIPANT");
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(null);
            }
            
            // Load file as resource
            Resource resource = new FileSystemResource(filePath);
            
            // Determine content type
            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = "image/jpeg";
            }
            
            log.debug("[CheckIn-P0-1] Serving photo: {}, user: {}, booking: {}", 
                filePath, currentUserId, bookingId);
            
            // Log access for audit trail
            accessLogService.logPhotoAccess(currentUserId, bookingId, photoId, "GET_SINGLE");
            
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CACHE_CONTROL, "max-age=3600, public")
                    .body(resource);
                    
        } catch (Exception e) {
            log.error("[CheckIn-P0-1] Error serving photo: session={}, file={}, user={}", 
                sessionId, filename, currentUserId, e);
            accessLogService.logPhotoAccessDenied(currentUserId, null, null, 500, "INTERNAL_ERROR");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Check if a path segment contains directory traversal patterns.
     */
    private boolean containsPathTraversal(String segment) {
        return segment == null ||
                segment.contains("..") ||
                segment.contains("/") ||
                segment.contains("\\") ||
                segment.contains("%2e") || // URL-encoded .
                segment.contains("%2f") || // URL-encoded /
                segment.contains("%5c");   // URL-encoded \
    }

    /**
     * Extract photo ID from file path for audit logging.
     * In the current implementation, we may not have photo ID without a DB query.
     * Return null for now; could be enhanced later.
     */
    private Long extractPhotoIdFromPath(Path filePath) {
        // Could implement: query DB for photo by session ID and filename
        // For now, return null
        return null;
    }

    /**
     * Extract booking ID from session ID.
     * Session ID is created per check-in session and should be linked to booking.
     * Queries the photo repository to find which booking owns photos for this session.
     */
    private Long extractBookingIdFromSessionId(String sessionId) {
        List<Long> bookingIds = photoRepository.findBookingIdsBySessionId(sessionId);
        if (bookingIds != null && !bookingIds.isEmpty()) {
            return bookingIds.get(0);
        }
        log.warn("[CheckIn-P0-1] No booking found for session ID: {}", sessionId);
        return null;
    }

    /**
     * Get client IP address from request context.
     * Handles proxies and load balancers via X-Forwarded-For.
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
            
            // Check X-Forwarded-For header (load balancer / proxy)
            String xfForwardedFor = request.getHeader("X-Forwarded-For");
            if (xfForwardedFor != null && !xfForwardedFor.isEmpty()) {
                return xfForwardedFor.split(",")[0].trim();
            }
            
            // Check X-Client-IP header
            String xClientIp = request.getHeader("X-Client-IP");
            if (xClientIp != null && !xClientIp.isEmpty()) {
                return xClientIp;
            }
            
            // Fall back to direct remote address
            return request.getRemoteAddr() != null ? request.getRemoteAddr() : "UNKNOWN";
        } catch (Exception e) {
            log.debug("[CheckIn] Error extracting client IP", e);
            return "UNKNOWN";
        }
    }
}

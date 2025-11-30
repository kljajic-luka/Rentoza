package org.example.rentoza.booking.checkin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Controller for serving check-in photo files.
 * 
 * <p>This endpoint serves uploaded check-in photos from the file system.
 * Photos are organized by session ID (UUID) to prevent conflicts.
 * 
 * <h2>URL Pattern</h2>
 * <pre>
 * GET /api/checkin/photos/{sessionId}/{filename}
 * </pre>
 * 
 * <h2>Security</h2>
 * <ul>
 *   <li>Requires authentication</li>
 *   <li>Path traversal attacks are blocked</li>
 *   <li>Only files within the upload directory are served</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/checkin/photos")
@PreAuthorize("isAuthenticated()")
@Slf4j
public class CheckInPhotoController {

    @Value("${app.checkin.photo.upload-dir:uploads/checkin}")
    private String uploadDir;

    /**
     * Serve a check-in photo file.
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
        
        // Sanitize inputs to prevent directory traversal
        if (containsPathTraversal(sessionId) || containsPathTraversal(filename)) {
            log.warn("[CheckIn] Rejected directory traversal attempt: session={}, file={}", 
                sessionId, filename);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        
        try {
            // Construct the file path
            Path filePath = Paths.get(uploadDir, sessionId, filename).normalize();
            
            // Verify the path is within uploadDir (extra security)
            Path uploadDirPath = Paths.get(uploadDir).toAbsolutePath().normalize();
            Path absoluteFilePath = filePath.toAbsolutePath().normalize();
            
            if (!absoluteFilePath.startsWith(uploadDirPath)) {
                log.warn("[CheckIn] Path escape attempt: {}", filePath);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            
            // Check file exists
            if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
                log.debug("[CheckIn] Photo not found: {}", filePath);
                return ResponseEntity.notFound().build();
            }
            
            // Load file as resource
            Resource resource = new FileSystemResource(filePath);
            
            // Determine content type
            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                // Default to JPEG for check-in photos
                contentType = "image/jpeg";
            }
            
            log.debug("[CheckIn] Serving photo: {}, type: {}", filePath, contentType);
            
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CACHE_CONTROL, "max-age=3600, public") // Cache for 1 hour
                    .body(resource);
                    
        } catch (IOException e) {
            log.error("[CheckIn] Error serving photo: session={}, file={}", sessionId, filename, e);
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
}

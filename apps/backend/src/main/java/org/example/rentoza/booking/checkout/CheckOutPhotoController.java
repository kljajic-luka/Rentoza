package org.example.rentoza.booking.checkout;

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
 * Controller for serving checkout photo files.
 * 
 * <p>This endpoint serves uploaded checkout photos from the file system.
 * Photos are organized by session ID (UUID) to prevent conflicts.
 * 
 * <h2>URL Pattern</h2>
 * <pre>
 * GET /api/checkout/photos/{sessionId}/{filename}
 * </pre>
 */
@RestController
@RequestMapping("/api/checkout/photos")
@PreAuthorize("isAuthenticated()")
@Slf4j
public class CheckOutPhotoController {

    // Default to sibling directory of checkin uploads if not specified
    @Value("${app.checkout.photo.upload-dir:uploads/checkout}")
    private String uploadDir;

    /**
     * Serve a checkout photo file.
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
        
        // Sanitize inputs to prevent directory traversal
        if (containsPathTraversal(sessionId) || containsPathTraversal(filename)) {
            log.warn("[CheckOut] Rejected directory traversal attempt: session={}, file={}", 
                sessionId, filename);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        
        try {
            // Construct the file path
            Path filePath = Paths.get(uploadDir, sessionId, filename).normalize();
            
            // Verify the path is within uploadDir (extra security)
            Path uploadDirPath = Paths.get(uploadDir).toAbsolutePath().normalize();
            Path absoluteFilePath = filePath.toAbsolutePath().normalize();
            
            // Note: If uploadDir doesn't exist yet, toAbsolutePath might behave oddly if relative?
            // But we assume it's created by service.
            
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
            
            log.debug("[CheckOut] Serving photo: {}, type: {}", filePath, contentType);
            
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CACHE_CONTROL, "max-age=3600, public")
                    .body(resource);
                    
        } catch (IOException e) {
            log.error("[CheckOut] Error serving photo: session={}, file={}", sessionId, filename, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
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

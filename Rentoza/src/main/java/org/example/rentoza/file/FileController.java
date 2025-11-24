package org.example.rentoza.file;


import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileStorageService fileStorageService;
    
    public FileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @PostMapping("/upload")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> uploadFile(
            @RequestParam("file") MultipartFile file,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal
    ) {
        try {
            // Verify authentication (handled by PreAuthorize, but double check if needed)
            if (principal == null) {
                return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
            }

            // Validate file is not empty
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "File is required"));
            }

            // Validate file size (max 10MB as configured in properties)
            if (file.getSize() > 10 * 1024 * 1024) {
                return ResponseEntity.badRequest().body(Map.of("error", "File size exceeds 10MB limit"));
            }

            // Validate file type (images only for car photos)
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                return ResponseEntity.badRequest().body(Map.of("error", "Only image files are allowed"));
            }

            // SECURITY: Validate actual file content (magic bytes) to prevent file type spoofing
            try {
                byte[] fileBytes = file.getBytes();
                if (fileBytes.length < 12) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Invalid file"));
                }

                if (!isValidImageFile(fileBytes)) {
                    return ResponseEntity.badRequest().body(Map.of("error", "File content does not match image format"));
                }
            } catch (IOException e) {
                return ResponseEntity.status(500).body(Map.of("error", "File validation failed"));
            }

            String url = fileStorageService.saveFile(file);
            return ResponseEntity.ok(url);

        } catch (RuntimeException e) {
            return ResponseEntity.status(500).body(Map.of("error", "File upload failed: " + e.getMessage()));
        }
    }

    /**
     * Validates file content by checking magic bytes (file signatures).
     * Prevents attackers from uploading malicious files with spoofed Content-Type headers.
     *
     * @param fileBytes the raw bytes of the uploaded file
     * @return true if the file is a valid image format (PNG, JPEG, GIF, or WebP)
     */
    private boolean isValidImageFile(byte[] fileBytes) {
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (fileBytes.length >= 8 &&
            fileBytes[0] == (byte)0x89 && fileBytes[1] == 0x50 &&
            fileBytes[2] == 0x4E && fileBytes[3] == 0x47 &&
            fileBytes[4] == 0x0D && fileBytes[5] == 0x0A &&
            fileBytes[6] == 0x1A && fileBytes[7] == 0x0A) {
            return true;
        }

        // JPEG: FF D8 FF
        if (fileBytes.length >= 3 &&
            fileBytes[0] == (byte)0xFF && fileBytes[1] == (byte)0xD8 &&
            fileBytes[2] == (byte)0xFF) {
            return true;
        }

        // GIF: 47 49 46 38 (GIF8)
        if (fileBytes.length >= 4 &&
            fileBytes[0] == 0x47 && fileBytes[1] == 0x49 &&
            fileBytes[2] == 0x46 && fileBytes[3] == 0x38) {
            return true;
        }

        // WebP: 52 49 46 46 ... 57 45 42 50 (RIFF...WEBP)
        if (fileBytes.length >= 12 &&
            fileBytes[0] == 0x52 && fileBytes[1] == 0x49 &&
            fileBytes[2] == 0x46 && fileBytes[3] == 0x46 &&
            fileBytes[8] == 0x57 && fileBytes[9] == 0x45 &&
            fileBytes[10] == 0x42 && fileBytes[11] == 0x50) {
            return true;
        }

        return false;
    }
}

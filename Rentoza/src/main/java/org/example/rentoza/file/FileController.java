package org.example.rentoza.file;

import org.example.rentoza.security.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileStorageService fileStorageService;
    private final JwtUtil jwtUtil;

    public FileController(FileStorageService fileStorageService, JwtUtil jwtUtil) {
        this.fileStorageService = fileStorageService;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        try {
            // Verify authentication
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
            }

            String token = authHeader.substring(7);
            String email = jwtUtil.getEmailFromToken(token);

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

            String url = fileStorageService.saveFile(file);
            return ResponseEntity.ok(url);

        } catch (RuntimeException e) {
            return ResponseEntity.status(500).body(Map.of("error", "File upload failed: " + e.getMessage()));
        }
    }
}

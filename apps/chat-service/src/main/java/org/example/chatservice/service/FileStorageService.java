package org.example.chatservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;

/**
 * File storage service for chat attachments.
 * 
 * Validates:
 * - MIME type whitelist (images + PDF only)
 * - File size limit (10MB)
 * - Filename sanitization
 * 
 * Storage: Local filesystem (swap for GCS/S3 in production via interface).
 */
@Service
@Slf4j
public class FileStorageService {

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "application/pdf"
    );

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".pdf"
    );

    @Value("${chat.uploads.directory:./chat-uploads}")
    private String uploadDirectory;

    @Value("${chat.uploads.base-url:/api/attachments}")
    private String baseUrl;

    /**
     * Upload a file attachment with full validation.
     * 
     * @param file The uploaded file
     * @param bookingId The booking ID (for directory organization)
     * @param userId The uploading user ID (for audit)
     * @return The public URL to access the uploaded file
     * @throws IllegalArgumentException if file fails validation
     */
    public String uploadAttachment(MultipartFile file, Long bookingId, Long userId) {
        // 1. Null/empty check
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty or null");
        }

        // 2. Size validation
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                    String.format("File too large: %d bytes (max: %d bytes / 10MB)", 
                            file.getSize(), MAX_FILE_SIZE));
        }

        // 3. MIME type validation
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_MIME_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException(
                    String.format("Invalid file type: %s. Allowed: %s", 
                            contentType, ALLOWED_MIME_TYPES));
        }

        // 4. Extension validation (defense-in-depth against MIME spoofing)
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            originalFilename = "attachment";
        }
        String extension = getFileExtension(originalFilename).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                    String.format("Invalid file extension: %s. Allowed: %s", 
                            extension, ALLOWED_EXTENSIONS));
        }

        // 5. Malware/content scanning stub
        //    Production: Integrate with ClamAV, Google Safe Browsing API, or Cloud DLP
        //    For now, perform basic magic-byte validation to ensure content matches declared type
        try {
            scanForMalware(file, contentType);
        } catch (IOException e) {
            log.error("[Upload] Malware scan I/O error for user {} booking {}: {}", userId, bookingId, e.getMessage());
            throw new IllegalArgumentException("File could not be validated. Please try again.");
        }

        // 6. Generate safe filename (UUID-based, prevents path traversal)
        String safeFilename = UUID.randomUUID().toString() + extension;
        String subDirectory = "booking-" + bookingId;

        try {
            // Ensure upload directory exists
            Path uploadPath = Paths.get(uploadDirectory, subDirectory);
            Files.createDirectories(uploadPath);

            // Save file
            Path filePath = uploadPath.resolve(safeFilename);
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            log.info("[Upload] User {} uploaded {} ({}, {} bytes) for booking {}", 
                    userId, safeFilename, contentType, file.getSize(), bookingId);

            // Return the access URL
            return baseUrl + "/" + subDirectory + "/" + safeFilename;

        } catch (IOException e) {
            log.error("[Upload] Failed to store file for booking {}: {}", bookingId, e.getMessage());
            throw new RuntimeException("Failed to store file", e);
        }
    }

    /**
     * Retrieve a file by its relative path.
     * 
     * @param relativePath The path relative to upload directory
     * @return The file as a byte array
     * @throws IOException if file not found or read error
     */
    public byte[] getFile(String relativePath) throws IOException {
        // Sanitize path to prevent directory traversal
        Path filePath = Paths.get(uploadDirectory).resolve(relativePath).normalize();
        if (!filePath.startsWith(Paths.get(uploadDirectory).normalize())) {
            throw new SecurityException("Path traversal attempt detected");
        }
        return Files.readAllBytes(filePath);
    }

    /**
     * Get the content type for a file path.
     */
    public String getContentType(String relativePath) throws IOException {
        Path filePath = Paths.get(uploadDirectory).resolve(relativePath).normalize();
        String type = Files.probeContentType(filePath);
        return type != null ? type : "application/octet-stream";
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot < 0) return "";
        return filename.substring(lastDot);
    }

    /**
     * Basic malware/content scanning.
     * 
     * Validates file magic bytes match declared MIME type.
     * Production: Replace with ClamAV, Google Cloud DLP, or VirusTotal API integration.
     *
     * @param file The uploaded file
     * @param declaredContentType The declared MIME type
     * @throws IllegalArgumentException if content appears malicious
     * @throws IOException if file cannot be read
     */
    private void scanForMalware(MultipartFile file, String declaredContentType) throws IOException {
        byte[] header = new byte[Math.min(12, (int) file.getSize())];
        try (var is = file.getInputStream()) {
            int read = is.read(header);
            if (read < 4) {
                throw new IllegalArgumentException("File too small to validate");
            }
        }

        // Verify magic bytes match declared type
        boolean valid = switch (declaredContentType.toLowerCase()) {
            case "image/jpeg" -> header[0] == (byte) 0xFF && header[1] == (byte) 0xD8;
            case "image/png" -> header[0] == (byte) 0x89 && header[1] == (byte) 0x50
                    && header[2] == (byte) 0x4E && header[3] == (byte) 0x47;
            case "image/gif" -> header[0] == (byte) 0x47 && header[1] == (byte) 0x49
                    && header[2] == (byte) 0x46;
            case "image/webp" -> header.length >= 12
                    && header[0] == (byte) 0x52 && header[1] == (byte) 0x49  // "RI"
                    && header[8] == (byte) 0x57 && header[9] == (byte) 0x45; // "WE"
            case "application/pdf" -> header[0] == (byte) 0x25 && header[1] == (byte) 0x50
                    && header[2] == (byte) 0x44 && header[3] == (byte) 0x46; // "%PDF"
            default -> false;
        };

        if (!valid) {
            log.warn("[Security] Magic byte mismatch: declared={}, actual header={}",
                    declaredContentType, bytesToHex(header, 4));
            throw new IllegalArgumentException(
                    "File content does not match declared type. Possible disguised file.");
        }

        log.debug("[Upload] Malware scan passed for {} file ({} bytes)", declaredContentType, file.getSize());
        // TODO: Production — integrate ClamAV or cloud-based scanning here
    }

    private static String bytesToHex(byte[] bytes, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(len, bytes.length); i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString().trim();
    }

    public static long getMaxFileSize() {
        return MAX_FILE_SIZE;
    }

    public static Set<String> getAllowedMimeTypes() {
        return ALLOWED_MIME_TYPES;
    }
}

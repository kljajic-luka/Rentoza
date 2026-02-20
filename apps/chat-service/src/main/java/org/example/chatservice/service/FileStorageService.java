package org.example.chatservice.service;

import lombok.extern.slf4j.Slf4j;
import org.example.chatservice.exception.StorageUpstreamException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Chat attachment storage service.
 *
 * <p><strong>Provider selection</strong> (property {@code chat.storage.provider}):</p>
 * <ul>
 *   <li>{@code supabase} (default) — stores objects in a private Supabase Storage bucket.
 *       Requires {@code SUPABASE_URL} + {@code SUPABASE_SERVICE_ROLE_KEY} env vars.</li>
 *   <li>{@code local} — writes to local filesystem under {@code chat.uploads.directory}
 *       (suitable for local dev / integration tests without external deps).</li>
 * </ul>
 *
 * <p>All uploads pass: size limit → MIME whitelist → extension whitelist →
 * magic-byte validation → UUID filename generation (no user path components).</p>
 */
@Service
@Slf4j
public class FileStorageService {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024; // 10 MB

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

    /** Extension → MIME type (derived from our controlled whitelist — no file-system probe needed). */
    private static final Map<String, String> EXTENSION_TO_MIME = Map.of(
            ".jpg",  "image/jpeg",
            ".jpeg", "image/jpeg",
            ".png",  "image/png",
            ".gif",  "image/gif",
            ".webp", "image/webp",
            ".pdf",  "application/pdf"
    );

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    /** {@code supabase} or {@code local}. */
    @Value("${chat.storage.provider:supabase}")
    private String storageProvider;

    /** Local-mode only — filesystem directory for uploads. */
    @Value("${chat.uploads.directory:./chat-uploads}")
    private String uploadDirectory;

    /**
     * Base URL prefix for attachment access links.
     * Must match the path handled by {@code GET /api/attachments/**}.
     */
    @Value("${chat.uploads.base-url:/api/attachments}")
    private String baseUrl;

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final SupabaseStorageClient supabaseStorageClient;

    public FileStorageService(SupabaseStorageClient supabaseStorageClient) {
        this.supabaseStorageClient = supabaseStorageClient;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Validate, scan, and store an attachment.  Returns the relative path that
     * the controller exposes as the download URL suffix
     * (e.g. {@code booking-7/3f2a…uuid….jpg}).
     *
     * @throws IllegalArgumentException  on validation / content-policy failure
     * @throws StorageUpstreamException  on Supabase 5 xx / connectivity failure
     */
    public String uploadAttachment(MultipartFile file, Long bookingId, Long userId) {
        validateNotEmpty(file);
        validateSize(file);

        String contentType = file.getContentType() == null
                ? "application/octet-stream"
                : file.getContentType().toLowerCase();
        validateMimeType(contentType);

        String originalFilename = file.getOriginalFilename() != null
                ? file.getOriginalFilename() : "attachment";
        String extension = getFileExtension(originalFilename).toLowerCase();
        validateExtension(extension);

        try {
            scanForMalware(file, contentType);
        } catch (IOException e) {
            log.error("[Upload] Scan I/O error for user {} booking {}: {}", userId, bookingId, e.getMessage());
            throw new IllegalArgumentException("File could not be validated. Please try again.");
        }

        String objectKey = "booking-" + bookingId + "/" + UUID.randomUUID() + extension;

        if ("supabase".equalsIgnoreCase(storageProvider)) {
            storeInSupabase(file, objectKey, contentType, userId, bookingId);
        } else {
            storeLocally(file, objectKey, userId, bookingId);
        }

        log.info("[Upload] provider={} user={} booking={} key={} size={} mime={}",
                storageProvider, userId, bookingId, objectKey, file.getSize(), contentType);
        return baseUrl + "/" + objectKey;
    }

    /**
     * Fetch attachment bytes by relative path (the portion after {@code /api/attachments/}).
     * <p>
     * In Supabase mode the path doubles as the Storage object key.
     *
     * @throws FileNotFoundException     if the object does not exist
     * @throws StorageUpstreamException  on Supabase 5 xx / connectivity failure
     * @throws IOException               on local-FS read failure
     */
    public byte[] getFile(String relativePath) throws IOException {
        String safe = sanitizePath(relativePath);

        if ("supabase".equalsIgnoreCase(storageProvider)) {
            return supabaseStorageClient.download(safe);
        }

        Path base = Paths.get(uploadDirectory).normalize();
        Path resolved = base.resolve(safe).normalize();
        if (!resolved.startsWith(base)) {
            throw new SecurityException("Path traversal attempt detected");
        }
        if (!Files.exists(resolved)) {
            throw new FileNotFoundException("File not found: " + safe);
        }
        return Files.readAllBytes(resolved);
    }

    /**
     * Returns the MIME type for a stored object.  Uses the extension map only —
     * no filesystem probe, so it works equally for Supabase and local paths.
     */
    public String getContentType(String relativePath) {
        String ext = getFileExtension(relativePath).toLowerCase();
        return EXTENSION_TO_MIME.getOrDefault(ext, "application/octet-stream");
    }

    // -------------------------------------------------------------------------
    // Private — validation helpers
    // -------------------------------------------------------------------------

    private void validateNotEmpty(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty or null");
        }
    }

    private void validateSize(MultipartFile file) {
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                    String.format("File too large: %d bytes (max %d bytes / 10 MB)",
                            file.getSize(), MAX_FILE_SIZE));
        }
    }

    private void validateMimeType(String contentType) {
        if (!ALLOWED_MIME_TYPES.contains(contentType)) {
            throw new IllegalArgumentException(
                    String.format("Invalid file type: %s. Allowed: %s", contentType, ALLOWED_MIME_TYPES));
        }
    }

    private void validateExtension(String extension) {
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                    String.format("Invalid file extension: %s. Allowed: %s", extension, ALLOWED_EXTENSIONS));
        }
    }

    /**
     * Rejects paths containing {@code ..}, null bytes, or backslashes to prevent
     * directory-traversal regardless of storage provider.
     */
    private String sanitizePath(String path) {
        if (path == null || path.contains("..") || path.contains("\0") || path.contains("\\")) {
            throw new SecurityException("Invalid attachment path");
        }
        // Strip a leading slash if present so the path can be used as a Storage key
        return path.startsWith("/") ? path.substring(1) : path;
    }

    // -------------------------------------------------------------------------
    // Private — storage backends
    // -------------------------------------------------------------------------

    private void storeInSupabase(MultipartFile file, String objectKey,
                                 String contentType, Long userId, Long bookingId) {
        try {
            byte[] data = file.getBytes();
            supabaseStorageClient.upload(objectKey, data, contentType);
        } catch (IOException e) {
            log.error("[Upload] getBytes() failed for user {} booking {}: {}", userId, bookingId, e.getMessage());
            throw new StorageUpstreamException("Could not read uploaded file bytes", e);
        }
    }

    private void storeLocally(MultipartFile file, String objectKey,
                               Long userId, Long bookingId) {
        try {
            Path dest = Paths.get(uploadDirectory, objectKey);
            Files.createDirectories(dest.getParent());
            Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("[Upload] Local store failed for user {} booking {}: {}", userId, bookingId, e.getMessage());
            throw new RuntimeException("Failed to store file locally", e);
        }
    }

    // -------------------------------------------------------------------------
    // Private — content scanning
    // -------------------------------------------------------------------------

    /**
     * Magic-byte validation to ensure file content matches declared MIME type.
     * <p>
     * Production hardening: replace / augment with ClamAV or Google Cloud DLP.
     *
     * @throws IllegalArgumentException if magic bytes are inconsistent
     * @throws IOException              if file bytes cannot be read
     */
    private void scanForMalware(MultipartFile file, String declaredContentType) throws IOException {
        byte[] header = new byte[Math.min(12, (int) file.getSize())];
        try (var is = file.getInputStream()) {
            int read = is.read(header);
            if (read < 4) {
                throw new IllegalArgumentException("File too small to validate");
            }
        }

        boolean valid = switch (declaredContentType.toLowerCase()) {
            case "image/jpeg" ->
                    header[0] == (byte) 0xFF && header[1] == (byte) 0xD8;
            case "image/png" ->
                    header[0] == (byte) 0x89 && header[1] == (byte) 0x50
                            && header[2] == (byte) 0x4E && header[3] == (byte) 0x47;
            case "image/gif" ->
                    header[0] == (byte) 0x47 && header[1] == (byte) 0x49 && header[2] == (byte) 0x46;
            case "image/webp" ->
                    header.length >= 12
                            && header[0] == (byte) 0x52 && header[1] == (byte) 0x49
                            && header[8] == (byte) 0x57 && header[9] == (byte) 0x45;
            case "application/pdf" ->
                    header[0] == (byte) 0x25 && header[1] == (byte) 0x50
                            && header[2] == (byte) 0x44 && header[3] == (byte) 0x46; // %PDF
            default -> false;
        };

        if (!valid) {
            log.warn("[Security] Magic-byte mismatch: declared={} header={}",
                    declaredContentType, bytesToHex(header, 4));
            throw new IllegalArgumentException(
                    "File content does not match declared type. Possible disguised file.");
        }

        log.debug("[Upload] Scan passed: {} ({} bytes)", declaredContentType, file.getSize());
    }

    // -------------------------------------------------------------------------
    // Private — misc helpers
    // -------------------------------------------------------------------------

    private static String getFileExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot);
    }

    private static String bytesToHex(byte[] bytes, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(len, bytes.length); i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString().trim();
    }

    // Exposed for tests / contract verification
    public static long getMaxFileSize()            { return MAX_FILE_SIZE; }
    public static Set<String> getAllowedMimeTypes() { return ALLOWED_MIME_TYPES; }
}

package org.example.rentoza.user;

import net.coobird.thumbnailator.Thumbnails;
import org.example.rentoza.storage.SupabaseStorageService;
import org.example.rentoza.user.dto.ProfilePictureResultDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;

/**
 * Service for handling profile picture uploads with security-first approach.
 *
 * <p>All profile pictures are stored in Supabase Storage (user-avatars bucket).
 *
 * Security measures:
 * - MIME type validation (JPEG, PNG, WebP only)
 * - Magic byte validation (prevents Content-Type spoofing)
 * - File size limit (4MB max)
 * - EXIF metadata stripping (removes GPS, camera info, timestamps)
 * - Image resizing and compression (reduces attack surface)
 */
@Service
public class ProfilePictureService {

    private static final Logger log = LoggerFactory.getLogger(ProfilePictureService.class);

    // Security constraints
    private static final long MAX_FILE_SIZE = 4 * 1024 * 1024; // 4MB
    private static final int MAX_WIDTH = 512;
    private static final int MAX_HEIGHT = 512;
    private static final float JPEG_QUALITY = 0.75f;

    // Allowed MIME types (strict whitelist)
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    // Explicitly blocked MIME types (defense in depth)
    private static final Set<String> BLOCKED_MIME_TYPES = Set.of(
            "image/svg+xml",    // XSS attack vector
            "image/heic",       // No browser support, complex parsing
            "image/heif",       // Same as HEIC
            "image/gif",        // Animation complexity, larger files
            "image/bmp",        // No compression, large files
            "image/tiff"        // Complex format, security concerns
    );

    private final SupabaseStorageService supabaseStorageService;
    
    @Autowired
    public ProfilePictureService(SupabaseStorageService supabaseStorageService) {
        this.supabaseStorageService = supabaseStorageService;
        log.info("✅ Profile pictures storage initialized (SUPABASE mode)");
    }

    /**
     * Upload and process a profile picture for the specified user.
     *
     * @param userId The authenticated user's ID
     * @param file   The uploaded image file
     * @return ProfilePictureResultDTO containing the URL with cache-busting timestamp
     * @throws ProfilePictureException if validation or processing fails
     */
    public ProfilePictureResultDTO uploadProfilePicture(Long userId, MultipartFile file) {
        log.info("📸 Processing profile picture upload for user {}", userId);

        // Step 1: Validate the uploaded file
        validateFile(file);

        try {
            // Step 2: Read and validate image content
            byte[] fileBytes = file.getBytes();
            validateMagicBytes(fileBytes);

            // Step 3: Process the image (resize, compress, strip metadata)
            byte[] processedImage = processImage(fileBytes);

            // Step 4: Upload to Supabase Storage
            String profilePictureUrl = supabaseStorageService.uploadUserAvatarBytes(userId, processedImage);

            log.info("✅ Profile picture uploaded for user {}: {} ({} bytes → {} bytes)",
                    userId, profilePictureUrl, fileBytes.length, processedImage.length);

            return new ProfilePictureResultDTO(profilePictureUrl);

        } catch (IOException e) {
            log.error("❌ Failed to process profile picture for user {}: {}", userId, e.getMessage());
            throw new ProfilePictureException("Failed to process image: " + e.getMessage());
        }
    }

    /**
     * Validate file metadata (size, MIME type, empty check).
     */
    private void validateFile(MultipartFile file) {
        // Check if file is provided
        if (file == null || file.isEmpty()) {
            throw new ProfilePictureException("No file provided");
        }

        // Check file size (4MB max)
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ProfilePictureException(
                    String.format("File size exceeds limit. Maximum allowed: %dMB, received: %.2fMB",
                            MAX_FILE_SIZE / (1024 * 1024),
                            file.getSize() / (1024.0 * 1024.0))
            );
        }

        // Get content type
        String contentType = file.getContentType();
        if (contentType == null) {
            throw new ProfilePictureException("Unable to determine file type");
        }

        // Normalize content type (remove charset, etc.)
        contentType = contentType.split(";")[0].trim().toLowerCase();

        // Check against blocked types first (defense in depth)
        if (BLOCKED_MIME_TYPES.contains(contentType)) {
            throw new ProfilePictureException(
                    "File type not allowed: " + contentType + ". Supported formats: JPEG, PNG, WebP"
            );
        }

        // Check against allowed types (strict whitelist)
        if (!ALLOWED_MIME_TYPES.contains(contentType)) {
            throw new ProfilePictureException(
                    "Unsupported file type: " + contentType + ". Supported formats: JPEG, PNG, WebP"
            );
        }
    }

    /**
     * Validate file content using magic bytes (file signatures).
     * Prevents Content-Type header spoofing attacks.
     */
    private void validateMagicBytes(byte[] fileBytes) {
        if (fileBytes.length < 12) {
            throw new ProfilePictureException("Invalid image file: file too small");
        }

        boolean isValid = isJpeg(fileBytes) || isPng(fileBytes) || isWebP(fileBytes);

        if (!isValid) {
            throw new ProfilePictureException(
                    "File content does not match a valid image format. " +
                    "Ensure the file is a genuine JPEG, PNG, or WebP image."
            );
        }
    }

    /**
     * Check for JPEG magic bytes: FF D8 FF
     */
    private boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3 &&
                bytes[0] == (byte) 0xFF &&
                bytes[1] == (byte) 0xD8 &&
                bytes[2] == (byte) 0xFF;
    }

    /**
     * Check for PNG magic bytes: 89 50 4E 47 0D 0A 1A 0A
     */
    private boolean isPng(byte[] bytes) {
        return bytes.length >= 8 &&
                bytes[0] == (byte) 0x89 &&
                bytes[1] == 0x50 &&
                bytes[2] == 0x4E &&
                bytes[3] == 0x47 &&
                bytes[4] == 0x0D &&
                bytes[5] == 0x0A &&
                bytes[6] == 0x1A &&
                bytes[7] == 0x0A;
    }

    /**
     * Check for WebP magic bytes: RIFF....WEBP
     */
    private boolean isWebP(byte[] bytes) {
        return bytes.length >= 12 &&
                bytes[0] == 0x52 && // R
                bytes[1] == 0x49 && // I
                bytes[2] == 0x46 && // F
                bytes[3] == 0x46 && // F
                bytes[8] == 0x57 && // W
                bytes[9] == 0x45 && // E
                bytes[10] == 0x42 && // B
                bytes[11] == 0x50;  // P
    }

    /**
     * Process the image: read, strip metadata, resize, and compress.
     *
     * Security notes:
     * - Reading into BufferedImage strips all metadata (EXIF, GPS, etc.)
     * - Creating a new RGB image ensures no hidden data is preserved
     * - Resizing reduces potential attack surface in complex images
     */
    private byte[] processImage(byte[] originalBytes) throws IOException {
        // Read original image (this inherently strips EXIF when we create a new BufferedImage)
        BufferedImage originalImage = ImageIO.read(new ByteArrayInputStream(originalBytes));
        if (originalImage == null) {
            throw new ProfilePictureException("Unable to read image data. File may be corrupted.");
        }

        int originalWidth = originalImage.getWidth();
        int originalHeight = originalImage.getHeight();

        // Validate minimum dimensions
        if (originalWidth < 50 || originalHeight < 50) {
            throw new ProfilePictureException("Image too small. Minimum dimensions: 50x50 pixels");
        }

        // Validate maximum dimensions (prevent decompression bombs)
        if (originalWidth > 10000 || originalHeight > 10000) {
            throw new ProfilePictureException("Image too large. Maximum dimensions: 10000x10000 pixels");
        }

        // Create clean BufferedImage (strips all metadata)
        BufferedImage cleanImage = stripMetadata(originalImage);

        // Calculate new dimensions maintaining aspect ratio
        int newWidth = originalWidth;
        int newHeight = originalHeight;

        if (originalWidth > MAX_WIDTH || originalHeight > MAX_HEIGHT) {
            double widthRatio = (double) MAX_WIDTH / originalWidth;
            double heightRatio = (double) MAX_HEIGHT / originalHeight;
            double ratio = Math.min(widthRatio, heightRatio);

            newWidth = (int) (originalWidth * ratio);
            newHeight = (int) (originalHeight * ratio);
        }

        // Use Thumbnailator for high-quality resize and JPEG compression
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        Thumbnails.of(cleanImage)
                .size(newWidth, newHeight)
                .outputFormat("jpg")
                .outputQuality(JPEG_QUALITY)
                .toOutputStream(outputStream);

        byte[] result = outputStream.toByteArray();

        log.debug("Image processed: {}x{} → {}x{}, {} bytes → {} bytes",
                originalWidth, originalHeight, newWidth, newHeight,
                originalBytes.length, result.length);

        return result;
    }

    /**
     * Strip all metadata from image by creating a clean copy.
     * This removes EXIF data including GPS coordinates, camera info, timestamps, etc.
     */
    private BufferedImage stripMetadata(BufferedImage original) {
        // Create a new BufferedImage without any metadata
        // Using TYPE_INT_RGB (no alpha) since we're outputting as JPEG
        BufferedImage clean = new BufferedImage(
                original.getWidth(),
                original.getHeight(),
                BufferedImage.TYPE_INT_RGB
        );

        // Draw original onto clean canvas with white background (for transparent PNGs)
        Graphics2D g2d = clean.createGraphics();
        try {
            // Set rendering hints for quality
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Fill with white background (handles transparent PNGs)
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, original.getWidth(), original.getHeight());

            // Draw original image
            g2d.drawImage(original, 0, 0, null);
        } finally {
            g2d.dispose();
        }

        return clean;
    }

    /**
     * Delete profile picture for a user from Supabase Storage.
     */
    public void deleteProfilePicture(Long userId, String currentAvatarUrl) {
        if (currentAvatarUrl != null && !currentAvatarUrl.isBlank()) {
            try {
                supabaseStorageService.deleteUserAvatar(currentAvatarUrl);
                log.info("🗑️ Deleted profile picture for user {}", userId);
            } catch (Exception e) {
                log.warn("⚠️ Failed to delete profile picture for user {}: {}", userId, e.getMessage());
            }
        }
    }

    /**
     * Custom exception for profile picture operations.
     */
    public static class ProfilePictureException extends RuntimeException {
        public ProfilePictureException(String message) {
            super(message);
        }
    }
}

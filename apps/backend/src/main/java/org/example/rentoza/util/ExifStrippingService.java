package org.example.rentoza.util;

import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Service for stripping EXIF metadata from images.
 * 
 * <p>Used to protect user privacy by removing GPS coordinates, camera info, 
 * and other potentially sensitive metadata from photos before public storage.
 * 
 * <h2>VAL-001: EXIF GPS Privacy Stripping</h2>
 * <p>Check-in photos may contain GPS coordinates that reveal user home addresses.
 * This service strips all EXIF metadata while preserving image quality:
 * <ul>
 *   <li><b>JPEG:</b> Uses Apache Commons Imaging ExifRewriter for lossless stripping</li>
 *   <li><b>PNG/WebP:</b> Uses ImageIO read/write which strips metadata automatically</li>
 * </ul>
 * 
 * <h2>Quality Preservation</h2>
 * <p>JPEG images are processed without recompression - only EXIF segments are removed.
 * This ensures no quality degradation for damage documentation photos.
 * 
 * <h2>Error Handling</h2>
 * <p>Fail-open policy: If stripping fails, original image is returned with warning log.
 * Privacy degradation is preferable to blocking user check-in.
 * 
 * @author AI Code Agent
 * @since 2026-01-31
 * @see org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter
 */
@Service
public class ExifStrippingService {
    
    private static final Logger log = LoggerFactory.getLogger(ExifStrippingService.class);
    
    // Supported JPEG MIME types
    private static final String MIME_JPEG = "image/jpeg";
    private static final String MIME_JPG = "image/jpg";
    
    // Supported non-JPEG types (metadata stripped via ImageIO)
    private static final String MIME_PNG = "image/png";
    private static final String MIME_WEBP = "image/webp";
    
    /**
     * Strip all EXIF metadata from an image while preserving quality.
     * 
     * <p>Processing strategy:
     * <ul>
     *   <li><b>JPEG:</b> Lossless EXIF removal using Apache Commons Imaging</li>
     *   <li><b>PNG/WebP:</b> ImageIO read/write (inherently strips metadata)</li>
     *   <li><b>Other:</b> Returns original with warning</li>
     * </ul>
     * 
     * @param imageBytes Original image bytes with EXIF metadata
     * @param contentType MIME type (e.g., "image/jpeg", "image/png")
     * @return Image bytes with EXIF stripped, or original if stripping fails
     */
    public byte[] stripExifMetadata(byte[] imageBytes, String contentType) {
        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("[ExifStripping] Empty image bytes provided, returning as-is");
            return imageBytes;
        }
        
        if (contentType == null) {
            log.warn("[ExifStripping] No content type provided, returning original");
            return imageBytes;
        }
        
        String normalizedType = contentType.toLowerCase().split(";")[0].trim();
        
        try {
            // JPEG: Use lossless EXIF removal
            if (MIME_JPEG.equals(normalizedType) || MIME_JPG.equals(normalizedType)) {
                return stripJpegExif(imageBytes);
            }
            
            // PNG/WebP: Use ImageIO (strips metadata automatically)
            if (MIME_PNG.equals(normalizedType) || MIME_WEBP.equals(normalizedType)) {
                return stripNonJpegMetadata(imageBytes, normalizedType);
            }
            
            // Unsupported type: Return original with warning
            log.warn("[ExifStripping] Unsupported content type: {}. Returning original.", normalizedType);
            return imageBytes;
            
        } catch (Exception e) {
            // Fail-open: Return original rather than blocking upload
            log.warn("[ExifStripping] Failed to strip EXIF metadata, returning original. " +
                    "ContentType={}, Size={} bytes, Reason: {}", 
                    normalizedType, imageBytes.length, e.getMessage());
            return imageBytes;
        }
    }
    
    /**
     * Strip EXIF from JPEG using Apache Commons Imaging (lossless).
     * 
     * <p>Uses ExifRewriter to remove EXIF APP1 segment without recompressing
     * the image data. This preserves full image quality.
     * 
     * @param jpegBytes Original JPEG bytes
     * @return JPEG bytes without EXIF, or original if stripping fails
     * @throws IOException if EXIF removal fails
     */
    private byte[] stripJpegExif(byte[] jpegBytes) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        
        try {
            // Apache Commons Imaging: Lossless EXIF removal
            new ExifRewriter().removeExifMetadata(jpegBytes, outputStream);
            
            byte[] stripped = outputStream.toByteArray();
            log.debug("[ExifStripping] JPEG EXIF stripped: {} bytes → {} bytes (saved {} bytes)", 
                    jpegBytes.length, stripped.length, jpegBytes.length - stripped.length);
            
            return stripped;
            
        } catch (Exception e) {
            // ExifRewriter can fail on malformed EXIF - fallback to ImageIO
            log.debug("[ExifStripping] ExifRewriter failed, falling back to ImageIO: {}", e.getMessage());
            return stripNonJpegMetadata(jpegBytes, "image/jpeg");
        }
    }
    
    /**
     * Strip metadata from PNG/WebP using ImageIO read/write.
     * 
     * <p>ImageIO.read() creates a BufferedImage without metadata.
     * Writing back to bytes produces a clean image without EXIF/XMP.
     * 
     * <p>Note: This may cause slight quality changes for lossy formats.
     * For check-in photos, PNG is rarely used (JPEG is standard).
     * 
     * @param imageBytes Original image bytes
     * @param contentType MIME type for output format
     * @return Image bytes without metadata
     * @throws IOException if image processing fails
     */
    private byte[] stripNonJpegMetadata(byte[] imageBytes, String contentType) throws IOException {
        // Read into BufferedImage (strips all metadata)
        BufferedImage originalImage = ImageIO.read(new ByteArrayInputStream(imageBytes));
        
        if (originalImage == null) {
            log.warn("[ExifStripping] ImageIO failed to read image, returning original");
            return imageBytes;
        }
        
        // Create clean image (no alpha for JPEG compatibility)
        BufferedImage cleanImage = new BufferedImage(
                originalImage.getWidth(),
                originalImage.getHeight(),
                BufferedImage.TYPE_INT_RGB
        );
        
        // Draw onto clean canvas with white background
        Graphics2D g2d = cleanImage.createGraphics();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, 
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, 
                    RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, originalImage.getWidth(), originalImage.getHeight());
            g2d.drawImage(originalImage, 0, 0, null);
        } finally {
            g2d.dispose();
        }
        
        // Write to output format
        String format = getImageFormat(contentType);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(cleanImage, format, outputStream);
        
        byte[] stripped = outputStream.toByteArray();
        log.debug("[ExifStripping] {} metadata stripped: {} bytes → {} bytes", 
                format.toUpperCase(), imageBytes.length, stripped.length);
        
        return stripped;
    }
    
    /**
     * Get ImageIO format string from MIME type.
     * 
     * @param contentType MIME type
     * @return Format string (jpg, png, webp)
     */
    private String getImageFormat(String contentType) {
        if (contentType == null) {
            return "jpg";
        }
        
        return switch (contentType.toLowerCase()) {
            case MIME_PNG -> "png";
            case MIME_WEBP -> "webp";
            default -> "jpg";
        };
    }
    
    /**
     * Check if the given content type supports EXIF metadata.
     * 
     * @param contentType MIME type to check
     * @return true if format may contain EXIF
     */
    public boolean supportsExif(String contentType) {
        if (contentType == null) return false;
        String normalized = contentType.toLowerCase().split(";")[0].trim();
        return MIME_JPEG.equals(normalized) || MIME_JPG.equals(normalized);
    }
}

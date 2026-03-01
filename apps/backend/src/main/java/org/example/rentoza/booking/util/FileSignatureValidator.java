package org.example.rentoza.booking.util;

/**
 * Shared utility for validating file magic bytes (file signatures).
 *
 * <p>Content-Type headers can be spoofed; magic bytes provide a reliable
 * secondary check that the uploaded data genuinely matches the claimed format.
 *
 * <h2>Supported Formats</h2>
 * <ul>
 *   <li>JPEG ({@code FF D8 FF})</li>
 *   <li>PNG ({@code 89 50 4E 47 0D 0A 1A 0A})</li>
 *   <li>HEIC/HEIF/AVIF (ISO BMFF {@code ftyp} box with brand heic/heix/mif1/msf1/avif)</li>
 *   <li>WebP ({@code RIFF....WEBP})</li>
 *   <li>PDF ({@code %PDF} / {@code 25 50 44 46})</li>
 * </ul>
 */
public final class FileSignatureValidator {

    private FileSignatureValidator() {
        // utility class
    }

    /**
     * Validate that the given byte data matches an accepted image (or PDF) signature.
     *
     * @param data     raw file bytes (at least 12 bytes required)
     * @param mimeType the declared MIME type (used in the error message only)
     * @throws IllegalArgumentException if validation fails
     */
    public static void validateImageSignature(byte[] data, String mimeType) {
        if (data == null || data.length < 12) {
            throw new IllegalArgumentException(
                    "File data is too short to be a valid image (minimum 12 bytes required)");
        }

        if (isJpeg(data) || isPng(data) || isHeic(data) || isWebP(data) || isPdf(data)) {
            return; // valid
        }

        throw new IllegalArgumentException(
                "Invalid file signature for declared type '" + mimeType +
                "'. Accepted formats: JPEG, PNG, HEIC/HEIF, WebP, PDF");
    }

    // ---- JPEG: FF D8 FF ----
    private static boolean isJpeg(byte[] h) {
        return h[0] == (byte) 0xFF &&
               h[1] == (byte) 0xD8 &&
               h[2] == (byte) 0xFF;
    }

    // ---- PNG: 89 50 4E 47 0D 0A 1A 0A ----
    private static boolean isPng(byte[] h) {
        return h[0] == (byte) 0x89 &&
               h[1] == (byte) 0x50 &&  // 'P'
               h[2] == (byte) 0x4E &&  // 'N'
               h[3] == (byte) 0x47 &&  // 'G'
               h[4] == (byte) 0x0D &&
               h[5] == (byte) 0x0A &&
               h[6] == (byte) 0x1A &&
               h[7] == (byte) 0x0A;
    }

    // ---- HEIC/HEIF/AVIF: ISO BMFF ftyp box ----
    private static boolean isHeic(byte[] h) {
        // ftyp at offset 4-7
        if (h[4] != 'f' || h[5] != 't' || h[6] != 'y' || h[7] != 'p') {
            return false;
        }
        // brand at offset 8-11
        String brand = new String(h, 8, 4);
        return brand.equals("heic") || brand.equals("heix") ||
               brand.equals("mif1") || brand.equals("msf1") ||
               brand.equals("avif");
    }

    // ---- WebP: RIFF....WEBP ----
    private static boolean isWebP(byte[] h) {
        return h[0] == 'R' && h[1] == 'I' &&
               h[2] == 'F' && h[3] == 'F' &&
               h[8] == 'W' && h[9] == 'E' &&
               h[10] == 'B' && h[11] == 'P';
    }

    // ---- PDF: %PDF (25 50 44 46) ----
    private static boolean isPdf(byte[] h) {
        return h[0] == (byte) 0x25 &&  // '%'
               h[1] == (byte) 0x50 &&  // 'P'
               h[2] == (byte) 0x44 &&  // 'D'
               h[3] == (byte) 0x46;    // 'F'
    }
}

package org.example.chatservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * B1 FIX: Default scanning implementation using magic-byte validation.
 *
 * <p>Verifies that the first bytes of an uploaded file match the expected
 * "magic bytes" for the declared MIME type.  This catches trivially
 * disguised uploads (e.g. HTML renamed to .jpg) but is <em>not</em> a
 * substitute for a real antivirus engine.</p>
 *
 * <p>Active when {@code chat.scanning.provider} is absent or set to
 * {@code magic-byte}.  For production hardening swap in a ClamAV or
 * Google Cloud DLP implementation of {@link FileScanningService}.</p>
 */
@Component
@ConditionalOnProperty(name = "chat.scanning.provider", havingValue = "magic-byte", matchIfMissing = true)
@Slf4j
public class MagicByteScanningService implements FileScanningService {

    private static final String SCANNER_NAME = "magic-byte";

    @Override
    public ScanResult scan(MultipartFile file, String declaredContentType) throws IOException {
        byte[] header = new byte[Math.min(12, (int) file.getSize())];
        try (var is = file.getInputStream()) {
            int read = is.read(header);
            if (read < 4) {
                return ScanResult.infected("File too small to validate", SCANNER_NAME);
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
            String reason = String.format(
                    "File content does not match declared type. declared=%s header=%s",
                    declaredContentType, bytesToHex(header, 4));
            log.warn("[Security] Magic-byte mismatch: {}", reason);
            return ScanResult.infected(reason, SCANNER_NAME);
        }

        log.debug("[Scan] Passed: {} ({} bytes)", declaredContentType, file.getSize());
        return ScanResult.clean(SCANNER_NAME);
    }

    private static String bytesToHex(byte[] bytes, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(len, bytes.length); i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString().trim();
    }
}

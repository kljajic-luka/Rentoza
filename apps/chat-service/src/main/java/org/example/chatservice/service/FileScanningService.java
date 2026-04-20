package org.example.chatservice.service;

import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

/**
 * B1 FIX: Abstraction for file content scanning before storage.
 * Default: magic-byte validation (built-in).
 * Production: ClamAV or Google Cloud DLP integration.
 */
public interface FileScanningService {

    /**
     * Scan file for malware/policy violations before storage.
     * @param file The uploaded file
     * @param declaredContentType The MIME type declared by the client
     * @return ScanResult with verdict and details
     * @throws IOException if file bytes cannot be read
     */
    ScanResult scan(MultipartFile file, String declaredContentType) throws IOException;

    /**
     * Result of a file scan.
     */
    record ScanResult(
        Verdict verdict,
        String reason,
        String scannerName
    ) {
        public enum Verdict { CLEAN, INFECTED, SUSPICIOUS, ERROR }

        public static ScanResult clean(String scannerName) {
            return new ScanResult(Verdict.CLEAN, null, scannerName);
        }

        public static ScanResult infected(String reason, String scannerName) {
            return new ScanResult(Verdict.INFECTED, reason, scannerName);
        }

        public static ScanResult suspicious(String reason, String scannerName) {
            return new ScanResult(Verdict.SUSPICIOUS, reason, scannerName);
        }

        public static ScanResult error(String reason, String scannerName) {
            return new ScanResult(Verdict.ERROR, reason, scannerName);
        }

        public boolean isAllowed() {
            return verdict == Verdict.CLEAN;
        }
    }
}

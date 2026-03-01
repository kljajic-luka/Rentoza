package org.example.rentoza.booking.photo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.storage.SupabaseStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

import org.example.rentoza.booking.util.FileSignatureValidator;

/**
 * P0-2 FIX: Dedicated service for storing PII photos securely.
 * 
 * <p>CRITICAL SECURITY: PII photos (ID documents, passports, licenses) MUST ONLY
 * be stored in Supabase encrypted buckets. Local filesystem storage is forbidden.
 * 
 * <h2>Enforcement</h2>
 * <ul>
 *   <li>Throws exception if Supabase is not available</li>
 *   <li>No fallback to local filesystem</li>
 *   <li>Logs all PII storage for compliance</li>
 * </ul>
 * 
 * <h2>PII Document Types</h2>
 * <ul>
 *   <li>ID_DOCUMENT (national ID, passport, etc.)</li>
 *   <li>DRIVER_LICENSE (vehicle operator's license)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PiiPhotoStorageService {

    private final SupabaseStorageService supabaseStorageService;

    @Value("${storage.mode:local}")
    private String storageMode;

    @Value("${storage.supabase.enabled:false}")
    private boolean supabaseEnabled;

    @Value("${app.checkin.photo.upload-dir:uploads/checkin}")
    private String legacyUploadDir;

    /**
     * Store a PII photo (ID document) securely in Supabase only.
     * 
     * @param bucketName The bucket (must be "checkin_pii" or "checkout_pii")
     * @param storageKey The file path within bucket
     * @param photoBytes The image data
     * @param mimeType The image type (image/jpeg, image/png, etc.)
     * @throws IllegalStateException if Supabase is not enabled
     * @throws IOException if storage fails
     */
    public void storePiiPhoto(String bucketName, String storageKey, byte[] photoBytes, String mimeType)
            throws IOException {

        // WI-7: Validate magic bytes before upload to prevent malicious file injection
        FileSignatureValidator.validateImageSignature(photoBytes, mimeType);

        // Validate configuration
        if (!supabaseEnabled) {
            log.error("[PII-Storage] CRITICAL: Supabase is not enabled. Cannot store PII. bucketName={}", bucketName);
            throw new IllegalStateException(
                "Supabase storage must be enabled for PII photo uploads. " +
                "Please configure storage.supabase.enabled=true"
            );
        }
        
        if (!"supabase".equalsIgnoreCase(storageMode)) {
            log.error("[PII-Storage] CRITICAL: Storage mode is not 'supabase'. Current: {}. Cannot store PII.", storageMode);
            throw new IllegalStateException(
                "PII photos require Supabase storage. Local filesystem is not permitted. " +
                "Configure storage.mode=supabase"
            );
        }
        
        // Validate bucket name to prevent accidental storage in public buckets
        if (!bucketName.toLowerCase().contains("pii")) {
            log.error("[PII-Storage] CRITICAL: Attempted to store PII in non-PII bucket: {}", bucketName);
            throw new IllegalArgumentException(
                "PII photos must be stored in an encrypted PII bucket (bucket name must contain 'pii')"
            );
        }
        
        try {
            log.info("[PII-Storage] Storing PII photo to Supabase: bucket={}, key={}, size={} bytes",
                bucketName, storageKey, photoBytes.length);
            
            // Upload to Supabase (encrypted at rest) using the ID photo method
            supabaseStorageService.uploadIdPhoto(storageKey, photoBytes, mimeType);
            
            log.info("[PII-Storage] Successfully stored PII photo: bucket={}, key={}", bucketName, storageKey);
            
        } catch (IOException e) {
            log.error("[PII-Storage] Failed to store PII photo to Supabase: bucket={}, key={}", 
                bucketName, storageKey, e);
            throw new IOException("Nije moguće pohraniti identifikacijski dokument. Pokušajte ponovo.", e);
        }
    }

    /**
     * Verify PII storage configuration before application starts.
     * Called during startup to catch configuration errors early.
     * 
     * @throws IllegalStateException if configuration is invalid
     */
    public void validateConfiguration() {
        if (!supabaseEnabled) {
            throw new IllegalStateException(
                "CRITICAL: PII photo storage requires Supabase to be enabled. " +
                "Set storage.supabase.enabled=true in configuration."
            );
        }
        
        if (!storageMode.equalsIgnoreCase("supabase")) {
            throw new IllegalStateException(
                "CRITICAL: PII photo storage requires storage.mode=supabase. " +
                "Current value: " + storageMode
            );
        }
        
        log.info("[PII-Storage] Configuration validated: Supabase enabled and configured correctly");
    }

    /**
     * Get the bucket name for a PII document type.
     * 
     * @param documentType Type of document (e.g., "DRIVER_LICENSE", "ID_DOCUMENT")
     * @return The Supabase bucket name
     */
    public String getBucketForDocumentType(String documentType) {
        return "checkin_pii";  // Single encrypted PII bucket for all document types
    }

    /**
     * Check if Supabase is available (connectivity test).
     * 
     * @return true if Supabase is reachable
     */
    public boolean isSupabaseAvailable() {
        try {
            // This would call a health check endpoint on Supabase
            // For now, just check configuration
            return supabaseEnabled && "supabase".equalsIgnoreCase(storageMode);
        } catch (Exception e) {
            log.error("[PII-Storage] Supabase availability check failed", e);
            return false;
        }
    }
}

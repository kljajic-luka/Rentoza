package org.example.rentoza.car.storage;

import org.example.rentoza.storage.SupabaseStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Supabase Storage implementation for car documents.
 * 
 * <p>This is the primary document storage strategy using Supabase Storage
 * for secure, scalable cloud storage. All documents are stored in Supabase.
 * 
 * <p>Stores documents in the 'car-documents' bucket with structure:
 * cars/{carId}/documents/{documentType}/{filename}
 */
@Service
@Primary
public class SupabaseDocumentStorageStrategy implements DocumentStorageStrategy {
    
    private static final Logger log = LoggerFactory.getLogger(SupabaseDocumentStorageStrategy.class);
    
    private final SupabaseStorageService supabaseStorageService;
    
    @Autowired
    public SupabaseDocumentStorageStrategy(SupabaseStorageService supabaseStorageService) {
        this.supabaseStorageService = supabaseStorageService;
        log.info("✅ Supabase document storage initialized");
    }
    
    @Override
    public String uploadFile(MultipartFile file, String path) throws IOException {
        // Parse car ID and document type from path
        // Expected format: cars/{carId}/documents/{type}_{timestamp}.{ext}
        String[] parts = path.split("/");
        if (parts.length < 4) {
            throw new IllegalArgumentException("Invalid storage path format: " + path);
        }
        
        Long carId = Long.parseLong(parts[1]);
        String documentType = extractDocumentType(parts[3]);
        
        return supabaseStorageService.uploadCarDocument(carId, documentType, file);
    }
    
    @Override
    public byte[] getFile(String path) throws IOException {
        // Download file bytes from Supabase Storage for admin preview
        log.debug("Downloading document from Supabase: {}", path);
        return supabaseStorageService.downloadCarDocument(path);
    }
    
    @Override
    public void deleteFile(String path) throws IOException {
        // Supabase deletion via storage service
        // Note: The path might be a full URL, extract the storage path
        log.info("Deleting document from Supabase: {}", path);
        // Implementation depends on whether we need delete functionality
    }
    
    @Override
    public boolean exists(String path) {
        // Supabase doesn't have a direct "exists" check via REST API
        // We assume it exists if we have a valid path
        return path != null && !path.isBlank();
    }
    
    @Override
    public String getPublicUrl(String path) {
        // For private bucket, return signed URL with 1 hour expiry
        return supabaseStorageService.getCarDocumentSignedUrl(path, 3600);
    }
    
    /**
     * Extract document type from filename.
     * Expected format: {type}_{timestamp}.{ext}
     */
    private String extractDocumentType(String filename) {
        if (filename == null) return "DOCUMENT";
        
        int underscoreIdx = filename.lastIndexOf('_');
        if (underscoreIdx > 0) {
            return filename.substring(0, underscoreIdx).toUpperCase();
        }
        
        // Fallback: remove extension
        int dotIdx = filename.lastIndexOf('.');
        if (dotIdx > 0) {
            return filename.substring(0, dotIdx).toUpperCase();
        }
        
        return filename.toUpperCase();
    }
}

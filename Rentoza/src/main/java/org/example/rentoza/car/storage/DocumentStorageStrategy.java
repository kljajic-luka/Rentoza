package org.example.rentoza.car.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Strategy interface for document storage.
 * 
 * <p>Allows switching between local storage (dev) and S3 (production)
 * without changing service logic.
 * 
 * <p>Usage:
 * <ul>
 *   <li>LocalDocumentStorageStrategy - Development/alpha testing</li>
 *   <li>S3DocumentStorageStrategy - Production (future)</li>
 * </ul>
 */
public interface DocumentStorageStrategy {
    
    /**
     * Upload file to storage.
     * 
     * @param file Multipart file from upload
     * @param path Relative path (e.g., "cars/123/documents/registration.pdf")
     * @return Full URL or path to stored file
     * @throws IOException if upload fails
     */
    String uploadFile(MultipartFile file, String path) throws IOException;
    
    /**
     * Get file content by path.
     * 
     * @param path Path returned from uploadFile
     * @return File bytes
     * @throws IOException if file not found or read fails
     */
    byte[] getFile(String path) throws IOException;
    
    /**
     * Delete file from storage.
     * 
     * @param path Path to delete
     * @throws IOException if delete fails
     */
    void deleteFile(String path) throws IOException;
    
    /**
     * Check if file exists.
     * 
     * @param path Path to check
     * @return true if exists
     */
    boolean exists(String path);
    
    /**
     * Get public URL for file (if applicable).
     * For local storage, returns file:// or relative path.
     * For S3, returns signed URL.
     * 
     * @param path Stored file path
     * @return Accessible URL
     */
    String getPublicUrl(String path);
}

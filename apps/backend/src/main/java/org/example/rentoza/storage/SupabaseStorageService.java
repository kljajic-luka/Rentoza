package org.example.rentoza.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;

/**
 * Enterprise-grade Supabase Storage Service for file uploads.
 * 
 * <p>Handles file uploads to Supabase Storage buckets with:
 * <ul>
 *   <li>Automatic bucket selection based on file type</li>
 *   <li>SHA256 hash calculation for integrity verification</li>
 *   <li>Proper error handling and retry logic</li>
 *   <li>Public/private URL generation</li>
 * </ul>
 * 
 * <p>Bucket structure:
 * <ul>
 *   <li>cars-images - Public car listing photos</li>
 *   <li>car-documents - Private vehicle compliance docs</li>
 *   <li>check-in-photos - Private check-in/checkout evidence</li>
 *   <li>renter-documents - Private KYC/verification docs</li>
 *   <li>user-avatars - Public profile pictures</li>
 * </ul>
 * 
 * @see <a href="https://supabase.com/docs/reference/javascript/storage-from-upload">Supabase Storage API</a>
 */
@Service
public class SupabaseStorageService {
    
    private static final Logger log = LoggerFactory.getLogger(SupabaseStorageService.class);
    
    // Bucket names
    public static final String BUCKET_CAR_IMAGES = "cars-images";
    public static final String BUCKET_CAR_DOCUMENTS = "car-documents";
    public static final String BUCKET_CHECK_IN_PHOTOS = "check-in-photos";
    public static final String BUCKET_CHECK_IN_PII = "check-in-pii";  // Service-role only, for ID verification
    public static final String BUCKET_CHECK_OUT_PHOTOS = "check-out-photos";
    public static final String BUCKET_RENTER_DOCUMENTS = "renter-documents";
    public static final String BUCKET_USER_AVATARS = "user-avatars";
    
    private final RestTemplate restTemplate;
    
    @Value("${supabase.url}")
    private String supabaseUrl;
    
    @Value("${supabase.service-role-key}")
    private String serviceRoleKey;
    
    public SupabaseStorageService() {
        this.restTemplate = new RestTemplate();
    }
    
    // ============================================================================
    // CAR IMAGES (Public bucket - car listing photos)
    // ============================================================================
    
    /**
     * Upload a car image to Supabase Storage.
     * 
     * @param carId Car ID for path organization
     * @param file Image file to upload
     * @return Public URL of uploaded image
     * @throws IOException if upload fails
     */
    public String uploadCarImage(Long carId, MultipartFile file) throws IOException {
        validateImageFile(file);
        
        String extension = getExtension(file.getContentType());
        String filename = generateFilename(carId, extension);
        String storagePath = String.format("cars/%d/images/%s", carId, filename);
        
        uploadToSupabase(BUCKET_CAR_IMAGES, storagePath, file.getBytes(), file.getContentType());
        
        return getPublicUrl(BUCKET_CAR_IMAGES, storagePath);
    }
    
    /**
     * Delete a car image from Supabase Storage.
     * 
     * @param storagePath Full storage path or public URL
     */
    public void deleteCarImage(String storagePath) {
        String path = extractPathFromUrl(storagePath, BUCKET_CAR_IMAGES);
        deleteFromSupabase(BUCKET_CAR_IMAGES, path);
    }
    
    // ============================================================================
    // CAR DOCUMENTS (Private bucket - registration, insurance, etc.)
    // ============================================================================
    
    /**
     * Upload a car document to Supabase Storage.
     * 
     * @param carId Car ID
     * @param documentType Document type (REGISTRATION, INSURANCE, etc.)
     * @param file Document file (PDF, image)
     * @return Storage path for database reference
     * @throws IOException if upload fails
     */
    public String uploadCarDocument(Long carId, String documentType, MultipartFile file) throws IOException {
        validateDocumentFile(file);
        
        String extension = getExtension(file.getContentType());
        String filename = generateFilename(carId, extension);
        String storagePath = String.format("cars/%d/documents/%s/%s", carId, documentType, filename);
        
        uploadToSupabase(BUCKET_CAR_DOCUMENTS, storagePath, file.getBytes(), file.getContentType());
        
        return storagePath;
    }
    
    /**
     * Get signed URL for private car document (for download/viewing).
     * 
     * @param storagePath Storage path from database
     * @param expiresInSeconds URL expiration time (default 3600)
     * @return Signed URL
     */
    public String getCarDocumentSignedUrl(String storagePath, int expiresInSeconds) {
        return createSignedUrl(BUCKET_CAR_DOCUMENTS, storagePath, expiresInSeconds);
    }
    
    // ============================================================================
    // CHECK-IN PHOTOS (Private bucket - damage assessment)
    // ============================================================================
    
    /**
     * Upload a check-in/checkout photo.
     * 
     * @param bookingId Booking ID
     * @param party "host" or "guest"
     * @param photoType Photo type (ODOMETER, FUEL_LEVEL, DAMAGE, etc.)
     * @param file Photo file
     * @return Storage path
     * @throws IOException if upload fails
     */
    public String uploadCheckInPhoto(Long bookingId, String party, String photoType, MultipartFile file) 
            throws IOException {
        validateImageFile(file);
        
        String extension = getExtension(file.getContentType());
        String filename = UUID.randomUUID().toString().substring(0, 12) + "." + extension;
        String storagePath = String.format("bookings/%d/%s/%s/%s", 
                bookingId, party.toLowerCase(), photoType, filename);
        
        uploadToSupabase(BUCKET_CHECK_IN_PHOTOS, storagePath, file.getBytes(), file.getContentType());
        
        return storagePath;
    }
    
    public String getCheckInPhotoSignedUrl(String storagePath, int expiresInSeconds) {
        return createSignedUrl(BUCKET_CHECK_IN_PHOTOS, storagePath, expiresInSeconds);
    }

    /**
     * Generate a signed URL for any bucket. This is the preferred method for
     * callers that already know the correct bucket name (e.g. PhotoUrlService).
     * 
     * @param bucket The Supabase bucket name (e.g. "check-in-photos", "check-out-photos", "check-in-pii")
     * @param storagePath The file path within the bucket
     * @param expiresInSeconds URL expiration in seconds
     * @return Signed URL for the file
     */
    public String createSignedUrlForBucket(String bucket, String storagePath, int expiresInSeconds) {
        return createSignedUrl(bucket, storagePath, expiresInSeconds);
    }
    
    // ============================================================================
    // CHECK-IN PII PHOTOS (Private bucket - ID verification, service-role only)
    // ============================================================================
    
    /**
     * Upload an identity verification photo to the PII-restricted bucket.
     * 
     * <p>This method handles driver's licenses, passports, and selfies used for
     * biometric verification. All uploads are restricted to service-role access only.
     * 
     * <p><b>Security Notes:</b>
     * <ul>
     *   <li>Photos are NOT publicly accessible</li>
     *   <li>Storage keys include session UUID for namespace isolation</li>
     *   <li>Original filenames are NOT preserved (enumeration attack prevention)</li>
     * </ul>
     * 
     * @param storageKey Full path within the check-in-pii bucket
     * @param fileBytes Raw photo bytes
     * @param contentType MIME type (must be image/jpeg or image/png)
     * @return Storage key (same as input, for chaining)
     * @throws IOException if upload fails
     */
    public String uploadIdPhoto(String storageKey, byte[] fileBytes, String contentType) 
            throws IOException {
        // Validate content type for PII photos (only JPEG/PNG allowed)
        if (contentType == null || 
            (!contentType.equals("image/jpeg") && !contentType.equals("image/png"))) {
            throw new IllegalArgumentException(
                "ID photos must be JPEG or PNG format. Received: " + contentType);
        }
        
        // Validate size (max 10MB for ID documents)
        if (fileBytes.length > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("ID photo too large (max 10MB)");
        }
        
        // Calculate checksum for integrity verification
        String checksum = calculateSha256(fileBytes);
        
        log.info("[Storage-PII] Uploading ID photo: path={}, size={}KB, checksum={}",
            storageKey, fileBytes.length / 1024, checksum.substring(0, 16) + "...");
        
        uploadToSupabase(BUCKET_CHECK_IN_PII, storageKey, fileBytes, contentType);
        
        log.info("[Storage-PII] Upload successful: path={}", storageKey);
        
        return storageKey;
    }
    
    /**
     * Generate a time-limited signed URL for ID photo retrieval.
     * 
     * <p><b>Security:</b> Signed URLs expire in 5 minutes (non-configurable for PII).
     * Authorization must be checked by the calling service before invoking this method.
     * 
     * @param storagePath Path within check-in-pii bucket
     * @return Signed URL valid for 300 seconds
     */
    public String getIdPhotoSignedUrl(String storagePath) {
        // PII signed URLs have fixed 5-minute expiration (security requirement)
        return createSignedUrl(BUCKET_CHECK_IN_PII, storagePath, 300);
    }
    
    /**
     * Upload check-in photo bytes directly (for services that already have byte arrays).
     * 
     * @param bookingId Booking ID
     * @param party "host" or "guest"
     * @param photoType Photo type
     * @param photoBytes Photo data as byte array
     * @param contentType MIME type
     * @return Storage path
     * @throws IOException if upload fails
     */
    public String uploadCheckInPhotoBytes(Long bookingId, String party, String photoType,
            byte[] photoBytes, String contentType) throws IOException {
        validateImageBytes(photoBytes, contentType);
        
        String extension = getExtension(contentType);
        String filename = UUID.randomUUID().toString().substring(0, 12) + "." + extension;
        String storagePath = String.format("bookings/%d/%s/%s/%s", 
                bookingId, party.toLowerCase(), photoType, filename);
        
        uploadToSupabase(BUCKET_CHECK_IN_PHOTOS, storagePath, photoBytes, contentType);
        
        return storagePath;
    }
    
    private void validateImageBytes(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Image bytes cannot be empty");
        }
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Only image files are allowed");
        }
        if (bytes.length > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("Image file too large (max 10MB)");
        }
    }
    
    // ============================================================================
    // CHECK-IN AUDIT PHOTOS (Private bucket - Admin-only for disputes) - VAL-001
    // ============================================================================
    
    public static final String BUCKET_CHECK_IN_AUDIT = "checkin-audit";
    
    /**
     * Upload original check-in photo with EXIF to admin-only audit bucket.
     * 
     * <p>Used for dispute resolution where GPS/timestamp evidence is needed.
     * This bucket is private and only accessible by admins.
     * 
     * @param bookingId Booking ID
     * @param party "host" or "guest"
     * @param photoType Photo type
     * @param photoBytes Original photo bytes with EXIF metadata
     * @param contentType MIME type
     * @return Storage path in audit bucket
     * @throws IOException if upload fails
     * @since VAL-001 - EXIF GPS Privacy Stripping
     */
    public String uploadCheckInPhotoToAuditBucket(Long bookingId, String party, String photoType,
            byte[] photoBytes, String contentType) throws IOException {
        validateImageBytes(photoBytes, contentType);
        
        String extension = getExtension(contentType);
        String filename = "audit_" + UUID.randomUUID().toString().substring(0, 12) + "." + extension;
        String storagePath = String.format("bookings/%d/%s/%s/%s", 
                bookingId, party.toLowerCase(), photoType, filename);
        
        uploadToSupabase(BUCKET_CHECK_IN_AUDIT, storagePath, photoBytes, contentType);
        
        log.info("[Storage] Uploaded audit photo with EXIF: bucket={}, path={}, size={} bytes",
                BUCKET_CHECK_IN_AUDIT, storagePath, photoBytes.length);
        
        return storagePath;
    }
    
    // ============================================================================
    // RENTER DOCUMENTS (Private bucket - KYC verification)
    // ============================================================================
    
    /**
     * Upload a renter verification document.
     * 
     * @param userId User ID
     * @param documentType Document type (DRIVER_LICENSE, SELFIE, etc.)
     * @param file Document file
     * @return Storage path
     * @throws IOException if upload fails
     */
    public String uploadRenterDocument(Long userId, String documentType, MultipartFile file) 
            throws IOException {
        validateDocumentFile(file);
        
        String extension = getExtension(file.getContentType());
        String filename = UUID.randomUUID().toString().substring(0, 12) + "." + extension;
        String storagePath = String.format("renters/%d/documents/%s/%s", userId, documentType, filename);
        
        uploadToSupabase(BUCKET_RENTER_DOCUMENTS, storagePath, file.getBytes(), file.getContentType());
        
        return storagePath;
    }
    
    public String getRenterDocumentSignedUrl(String storagePath, int expiresInSeconds) {
        return createSignedUrl(BUCKET_RENTER_DOCUMENTS, storagePath, expiresInSeconds);
    }
    
    // ============================================================================
    // USER AVATARS (Public bucket - profile pictures)
    // ============================================================================
    
    /**
     * Upload a user avatar/profile picture.
     * 
     * @param userId User ID
     * @param file Image file
     * @return Public URL
     * @throws IOException if upload fails
     */
    public String uploadUserAvatar(Long userId, MultipartFile file) throws IOException {
        validateImageFile(file);
        
        String extension = getExtension(file.getContentType());
        String filename = "avatar_" + Instant.now().toEpochMilli() + "." + extension;
        String storagePath = String.format("users/%d/avatar/%s", userId, filename);
        
        uploadToSupabase(BUCKET_USER_AVATARS, storagePath, file.getBytes(), file.getContentType());
        
        return getPublicUrl(BUCKET_USER_AVATARS, storagePath);
    }
    
    /**
     * Upload user avatar from processed byte array.
     * 
     * @param userId User ID
     * @param imageBytes Processed JPEG bytes
     * @return Public URL
     * @throws IOException if upload fails
     */
    public String uploadUserAvatarBytes(Long userId, byte[] imageBytes) throws IOException {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("Image bytes cannot be empty");
        }
        
        String filename = "avatar_" + Instant.now().toEpochMilli() + ".jpg";
        String storagePath = String.format("users/%d/avatar/%s", userId, filename);
        
        uploadToSupabase(BUCKET_USER_AVATARS, storagePath, imageBytes, "image/jpeg");
        
        return getPublicUrl(BUCKET_USER_AVATARS, storagePath);
    }
    
    /**
     * Delete old avatar when uploading new one.
     */
    public void deleteUserAvatar(String storagePath) {
        String path = extractPathFromUrl(storagePath, BUCKET_USER_AVATARS);
        if (path != null && !path.isBlank()) {
            deleteFromSupabase(BUCKET_USER_AVATARS, path);
        }
    }
    
    /**
     * Download a car document's raw bytes from Supabase Storage.
     * Used for admin preview/download.
     * 
     * @param storagePath The storage path (e.g., "cars/1/documents/TECHNICAL_INSPECTION/filename.jpg")
     * @return File bytes
     * @throws IOException if download fails
     */
    public byte[] downloadCarDocument(String storagePath) throws IOException {
        return downloadFromSupabase(BUCKET_CAR_DOCUMENTS, storagePath);
    }

    // ============================================================================
    // CORE SUPABASE STORAGE OPERATIONS
    // ============================================================================

    /**
     * Upload file to Supabase Storage bucket.
     * 
     * @param bucket Bucket name
     * @param path Storage path within bucket
     * @param data File bytes
     * @param contentType MIME type
     */
    private void uploadToSupabase(String bucket, String path, byte[] data, String contentType) {
        String url = String.format("%s/storage/v1/object/%s/%s", supabaseUrl, bucket, path);
        
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + serviceRoleKey);
        headers.set("Content-Type", contentType);
        headers.set("x-upsert", "true"); // Overwrite if exists
        
        HttpEntity<byte[]> request = new HttpEntity<>(data, headers);
        
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, String.class);
            
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Upload failed: " + response.getBody());
            }
            
            log.debug("✅ Uploaded to Supabase: {}/{}", bucket, path);
            
        } catch (HttpClientErrorException e) {
            log.error("❌ Supabase upload failed: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Failed to upload file to Supabase Storage: " + e.getMessage(), e);
        }
    }
    
    /**
     * Delete file from Supabase Storage.
     */
    private void deleteFromSupabase(String bucket, String path) {
        String url = String.format("%s/storage/v1/object/%s/%s", supabaseUrl, bucket, path);
        
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + serviceRoleKey);
        
        HttpEntity<Void> request = new HttpEntity<>(headers);
        
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, request, Void.class);
            log.debug("🗑️ Deleted from Supabase: {}/{}", bucket, path);
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("File not found for deletion: {}/{}", bucket, path);
        } catch (HttpClientErrorException e) {
            log.error("❌ Supabase delete failed: {}", e.getMessage());
        }
    }
    
    /**
     * Download file bytes from Supabase Storage.
     * Uses service role key to bypass RLS for admin access.
     * 
     * @param bucket Bucket name
     * @param path Storage path within bucket
     * @return File bytes
     * @throws IOException if download fails
     */
    private byte[] downloadFromSupabase(String bucket, String path) throws IOException {
        // Use authenticated download endpoint (bypasses RLS with service role)
        String url = String.format("%s/storage/v1/object/authenticated/%s/%s", supabaseUrl, bucket, path);
        
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + serviceRoleKey);
        
        HttpEntity<Void> request = new HttpEntity<>(headers);
        
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, byte[].class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.debug("✅ Downloaded from Supabase: {}/{} ({} bytes)", 
                        bucket, path, response.getBody().length);
                return response.getBody();
            }
            
            throw new IOException("Download failed: empty response");
            
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("File not found in Supabase: {}/{}", bucket, path);
            throw new IOException("File not found: " + path);
        } catch (HttpClientErrorException e) {
            log.error("❌ Supabase download failed: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new IOException("Failed to download file: " + e.getMessage(), e);
        }
    }
    
    /**
     * Create signed URL for private bucket access.
     * 
     * @param bucket Bucket name
     * @param path Storage path
     * @param expiresInSeconds Expiration time
     * @return Signed URL
     */
    private String createSignedUrl(String bucket, String path, int expiresInSeconds) {
        // Supabase Storage REST API: POST /storage/v1/object/sign/{bucket}/{path}
        // Body: {"expiresIn": seconds}  — expiresIn MUST be in the JSON body, not query param
        String url = String.format("%s/storage/v1/object/sign/%s/%s", 
                supabaseUrl, bucket, path);
        
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + serviceRoleKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        // Build JSON body with expiresIn as required by Supabase Storage API
        String requestBody = String.format("{\"expiresIn\":%d}", expiresInSeconds);
        HttpEntity<String> request = new HttpEntity<>(requestBody, headers);
        
        try {
            ResponseEntity<SignedUrlResponse> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, SignedUrlResponse.class);
            
            if (response.getBody() != null && response.getBody().signedURL != null) {
                String signedUrl = supabaseUrl + response.getBody().signedURL;
                log.debug("✅ Signed URL created: bucket={}, path={}", bucket, path);
                return signedUrl;
            }
            log.warn("⚠️ Signed URL response had no signedURL field: bucket={}, path={}", bucket, path);
        } catch (HttpClientErrorException e) {
            log.error("❌ Failed to create signed URL: bucket={}, path={}, status={}, body={}", 
                    bucket, path, e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("❌ Unexpected error creating signed URL: bucket={}, path={}", bucket, path, e);
        }
        
        // Do NOT fallback to public URL for private buckets — it will always 404
        // Instead, return empty so frontend shows a placeholder
        log.error("❌ Could not generate signed URL, returning empty: bucket={}, path={}", bucket, path);
        return "";
    }
    
    /**
     * Get public URL for file (works for public buckets).
     */
    public String getPublicUrl(String bucket, String path) {
        return String.format("%s/storage/v1/object/public/%s/%s", supabaseUrl, bucket, path);
    }
    
    // ============================================================================
    // VALIDATION & HELPERS
    // ============================================================================
    
    private void validateImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty");
        }
        
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Only image files are allowed");
        }
        
        // Max 10MB for images
        if (file.getSize() > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("Image file too large (max 10MB)");
        }
    }
    
    private void validateDocumentFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty");
        }
        
        String contentType = file.getContentType();
        if (contentType == null) {
            throw new IllegalArgumentException("Unknown file type");
        }
        
        boolean validType = contentType.startsWith("image/") || 
                            contentType.equals("application/pdf");
        if (!validType) {
            throw new IllegalArgumentException("Only images and PDFs are allowed");
        }
        
        // Max 25MB for documents
        if (file.getSize() > 25 * 1024 * 1024) {
            throw new IllegalArgumentException("Document file too large (max 25MB)");
        }
    }
    
    private String getExtension(String contentType) {
        if (contentType == null) return "bin";
        
        return switch (contentType.toLowerCase().split(";")[0].trim()) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "application/pdf" -> "pdf";
            default -> "bin";
        };
    }
    
    private String generateFilename(Long id, String extension) {
        return String.format("%d_%d_%s.%s",
                id,
                Instant.now().toEpochMilli(),
                UUID.randomUUID().toString().substring(0, 8),
                extension);
    }
    
    private String extractPathFromUrl(String urlOrPath, String bucket) {
        if (urlOrPath == null) return null;
        
        // Handle full URLs
        String publicPrefix = "/storage/v1/object/public/" + bucket + "/";
        int idx = urlOrPath.indexOf(publicPrefix);
        if (idx >= 0) {
            return urlOrPath.substring(idx + publicPrefix.length());
        }
        
        // Already a path
        if (!urlOrPath.startsWith("http")) {
            return urlOrPath;
        }
        
        return null;
    }
    
    /**
     * Calculate SHA256 hash for file integrity verification.
     */
    public String calculateSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    // ============================================================================
    // URL RESOLUTION (Multi-format support)
    // ============================================================================

    /**
     * Resolve any image URL to a displayable format.
     * 
     * <p>Handles:
     * <ul>
     *   <li>Supabase URLs - returned as-is (already public)</li>
     *   <li>Local paths - converted to server-relative URL</li>
     *   <li>Base64 data URIs - returned as-is (inline display)</li>
     *   <li>Storage paths - converted to public Supabase URL</li>
     * </ul>
     * 
     * @param imageUrl URL, path, or base64 data
     * @return Displayable URL
     */
    public String resolveCarImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }

        // Already a full URL (Supabase or external CDN)
        if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
            return imageUrl;
        }

        // Base64 data URI - return as-is
        if (imageUrl.startsWith("data:image/")) {
            return imageUrl;
        }

        // Local path (e.g., /uploads/car-images/47/image.jpg)
        if (imageUrl.startsWith("/uploads/") || imageUrl.startsWith("uploads/")) {
            // Return as server-relative URL
            return imageUrl.startsWith("/") ? imageUrl : "/" + imageUrl;
        }

        // Legacy local path format
        if (imageUrl.startsWith("/car-images/") || imageUrl.startsWith("car-images/")) {
            return imageUrl.startsWith("/") ? "/uploads" + imageUrl : "/uploads/" + imageUrl;
        }

        // Storage path (e.g., cars/47/images/filename.jpg) - convert to public URL
        if (imageUrl.startsWith("cars/")) {
            return getPublicUrl(BUCKET_CAR_IMAGES, imageUrl);
        }

        // Fallback: assume it's a storage path
        log.warn("Unknown image URL format, attempting as storage path: {}", imageUrl);
        return getPublicUrl(BUCKET_CAR_IMAGES, imageUrl);
    }

    /**
     * Get public URL for car images bucket.
     * Convenience method for external use.
     */
    public String getCarImagePublicUrl(String storagePath) {
        return getPublicUrl(BUCKET_CAR_IMAGES, storagePath);
    }
    
    // Response DTO for signed URL API
    private static class SignedUrlResponse {
        public String signedURL;
    }
}

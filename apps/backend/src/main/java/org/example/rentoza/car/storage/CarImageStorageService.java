package org.example.rentoza.car.storage;

import jakarta.annotation.PostConstruct;
import org.example.rentoza.storage.SupabaseStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Car Image Storage Service using Supabase Storage.
 * 
 * <p>All car images are stored in Supabase Storage for secure, scalable cloud storage.
 * Legacy local filesystem support has been removed.
 */
@Service
public class CarImageStorageService {

    private static final Logger log = LoggerFactory.getLogger(CarImageStorageService.class);

    private static final int MAX_IMAGES = 10;
    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024L * 1024L; // 10MB

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );
    
    @Autowired
    private SupabaseStorageService supabaseStorageService;

    @PostConstruct
    public void init() {
        if (supabaseStorageService == null) {
            throw new IllegalStateException("SupabaseStorageService is required for car image storage");
        }
        log.info("✅ Car images storage initialized (SUPABASE mode)");
    }

    /**
     * Store car images to Supabase Storage.
     * 
     * @param carId Car ID for path organization
     * @param images List of image files to store
     * @return List of public URLs for stored images
     */
    public List<String> storeCarImages(long carId, List<MultipartFile> images) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("Bar jedna fotografija auta je obavezna");
        }
        if (images.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("Maksimalno " + MAX_IMAGES + " slika je dozvoljeno");
        }

        // Route to appropriate storage backend
        if (supabaseStorageService != null) {
            return storeToSupabase(carId, images);
        } else {
            throw new IllegalStateException("Supabase storage service is not configured");
        }
    }
    
    /**
     * Store images to Supabase Storage (production mode).
     */
    private List<String> storeToSupabase(long carId, List<MultipartFile> images) {
        List<String> storedUrls = new ArrayList<>(images.size());
        
        try {
            for (MultipartFile image : images) {
                validateImage(image);
                String publicUrl = supabaseStorageService.uploadCarImage(carId, image);
                storedUrls.add(publicUrl);
                log.debug("✅ Uploaded car image to Supabase: {}", publicUrl);
            }
            return storedUrls;
        } catch (IOException e) {
            // Best-effort cleanup of uploaded files on failure
            for (String url : storedUrls) {
                try {
                    supabaseStorageService.deleteCarImage(url);
                } catch (Exception ignored) {
                    // ignore cleanup failures
                }
            }
            throw new RuntimeException("Greška pri čuvanju fotografija auta u Supabase", e);
        }
    }
    
    /**
     * Store images to local filesystem (development mode).
     */
    private void validateImage(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Nevažeća slika (prazan fajl)");
        }

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("Slika ne može biti veća od 10MB");
        }

        String contentType = file.getContentType();
        if (contentType == null) {
            throw new IllegalArgumentException("Nije moguće odrediti tip fajla");
        }
        contentType = contentType.split(";")[0].trim().toLowerCase();
        if (!ALLOWED_MIME_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Dozvoljeni formati slika: JPEG, PNG, WebP");
        }

        validateMagicBytes(file);
    }

    private void validateMagicBytes(MultipartFile file) throws IOException {
        byte[] header = new byte[12];
        int bytesRead;
        try (var in = file.getInputStream()) {
            bytesRead = in.read(header);
        }
        if (bytesRead < 12) {
            throw new IllegalArgumentException("Nevažeća slika (fajl je prekratak)");
        }

        boolean valid = isJpeg(header) || isPng(header) || isWebP(header);
        if (!valid) {
            throw new IllegalArgumentException("Nevažeća slika. Dozvoljeni formati: JPEG, PNG, WebP");
        }
    }

    private boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3 &&
                bytes[0] == (byte) 0xFF &&
                bytes[1] == (byte) 0xD8 &&
                bytes[2] == (byte) 0xFF;
    }

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
}

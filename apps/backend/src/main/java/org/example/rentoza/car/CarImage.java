package org.example.rentoza.car;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Car image entity for Supabase Storage integration.
 * 
 * <p>Maps to the {@code car_images} table which stores references to images
 * uploaded to Supabase Storage ({@code cars-images} bucket).
 * 
 * <p><b>Storage Architecture:</b>
 * <ul>
 *   <li>Image files stored in Supabase Storage (public bucket: cars-images)</li>
 *   <li>This entity stores the public URL reference</li>
 *   <li>Display order enables drag-and-drop reordering in UI</li>
 * </ul>
 * 
 * @see org.example.rentoza.storage.SupabaseStorageService#uploadCarImage
 */
@Entity
@Table(name = "car_images", indexes = {
        @Index(name = "idx_car_images_car", columnList = "car_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CarImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Car this image belongs to.
     * CASCADE DELETE: images are deleted when car is deleted.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "car_id", nullable = false)
    private Car car;

    /**
     * Public URL of the image in Supabase Storage.
     * 
     * <p>Format: {@code https://<project>.supabase.co/storage/v1/object/public/cars-images/cars/<carId>/images/<filename>}
     * 
     * <p>For legacy support, may also contain:
     * <ul>
     *   <li>Local filesystem paths: {@code /car-images/47/image.jpg}</li>
     *   <li>Base64 data URIs: {@code data:image/png;base64,...}</li>
     * </ul>
     */
    @Column(name = "image_url", nullable = false, columnDefinition = "TEXT")
    private String imageUrl;

    /**
     * Display order for UI gallery (0-based).
     * Primary image has displayOrder=0.
     */
    @Column(name = "display_order")
    @Builder.Default
    private Integer displayOrder = 0;

    /**
     * When the image was uploaded.
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // ========== HELPER METHODS ==========

    /**
     * Check if this is the primary (first) image.
     */
    public boolean isPrimary() {
        return displayOrder != null && displayOrder == 0;
    }

    /**
     * Check if this is a Supabase Storage URL.
     */
    public boolean isSupabaseUrl() {
        return imageUrl != null && imageUrl.contains("supabase.co/storage");
    }

    /**
     * Check if this is a legacy Base64 data URI.
     */
    public boolean isBase64() {
        return imageUrl != null && imageUrl.startsWith("data:image/");
    }

    /**
     * Check if this is a local filesystem path.
     */
    public boolean isLocalPath() {
        return imageUrl != null && 
               !isSupabaseUrl() && 
               !isBase64() && 
               (imageUrl.startsWith("/car-images/") || imageUrl.startsWith("car-images/"));
    }
}

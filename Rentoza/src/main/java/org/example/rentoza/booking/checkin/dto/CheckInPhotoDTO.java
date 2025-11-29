package org.example.rentoza.booking.checkin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.booking.checkin.CheckInPhotoType;
import org.example.rentoza.booking.checkin.ExifValidationStatus;

import java.time.LocalDateTime;

/**
 * Response DTO for check-in photos.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckInPhotoDTO {

    private Long photoId;
    private CheckInPhotoType photoType;
    
    /**
     * Pre-signed URL for photo access (expires in 1 hour).
     * In current implementation, returns direct storage path.
     */
    private String url;
    
    private LocalDateTime uploadedAt;
    private ExifValidationStatus exifValidationStatus;
    private String exifValidationMessage;
    
    // Photo metadata
    private Integer width;
    private Integer height;
    private String mimeType;
    
    // EXIF data (if available)
    private LocalDateTime exifTimestamp;
    private Double exifLatitude;
    private Double exifLongitude;
    private String deviceModel;
}

package org.example.rentoza.booking.checkin.photo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * Message payload for async photo validation.
 * 
 * <p>Sent to RabbitMQ queue for background EXIF validation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhotoValidationMessage implements Serializable {
    
    private static final long serialVersionUID = 1L;

    /**
     * ID of the photo to validate.
     */
    private Long photoId;

    /**
     * Booking ID for context and notifications.
     */
    private Long bookingId;

    /**
     * Storage key/path to the photo file.
     */
    private String storageKey;

    /**
     * Expected photo type for validation context.
     */
    private String photoType;

    /**
     * When the validation request was created.
     */
    private Instant requestedAt;

    /**
     * Retry count for tracking.
     */
    private int retryCount;

    /**
     * Car latitude for GPS validation.
     */
    private Double carLatitude;

    /**
     * Car longitude for GPS validation.
     */
    private Double carLongitude;

    /**
     * Client-reported upload start time for EXIF comparison.
     */
    private Instant clientUploadStartedAt;
}

package org.example.rentoza.booking.checkin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response envelope for photo upload operations.
 * 
 * <p>Provides consistent API response structure for both accepted and rejected photos:
 * <ul>
 *   <li><b>HTTP 201 Created:</b> Photo accepted and stored</li>
 *   <li><b>HTTP 400 Bad Request:</b> Photo rejected (zero-storage policy applied)</li>
 * </ul>
 * 
 * <h2>Usage Examples</h2>
 * <pre>
 * // Accepted photo
 * {
 *   "accepted": true,
 *   "photo": { "photoId": 123, "url": "...", "exifValidationStatus": "VALID" },
 *   "httpStatus": 201,
 *   "userMessage": "Fotografija je uspešno sačuvana."
 * }
 * 
 * // Rejected photo
 * {
 *   "accepted": false,
 *   "photo": { "photoType": "HOST_EXTERIOR_FRONT", "exifValidationStatus": "REJECTED_TOO_OLD", 
 *              "rejectionReason": "...", "remediationHint": "..." },
 *   "httpStatus": 400,
 *   "userMessage": "Fotografija nije prihvaćena.",
 *   "errorCodes": ["PHOTO_TOO_OLD"]
 * }
 * </pre>
 * 
 * @see CheckInPhotoDTO
 * @see org.example.rentoza.booking.checkin.PhotoRejectionService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PhotoUploadResponse {
    
    /**
     * Whether the photo was accepted (true) or rejected (false).
     * Determines HTTP status code: 201 for accepted, 400 for rejected.
     */
    private boolean accepted;
    
    /**
     * The photo DTO with details.
     * For accepted photos: contains photoId, url, and full metadata.
     * For rejected photos: contains photoType, exifValidationStatus, rejectionReason, remediationHint.
     */
    private CheckInPhotoDTO photo;
    
    /**
     * HTTP status code for the response.
     * 201 = Created (accepted), 400 = Bad Request (rejected).
     */
    private int httpStatus;
    
    /**
     * User-friendly message in Serbian.
     * Success: "Fotografija je uspešno sačuvana."
     * Rejection: "Fotografija nije prihvaćena."
     */
    private String userMessage;
    
    /**
     * Machine-readable error codes for frontend mapping.
     * Only populated for rejected photos.
     * Example: ["PHOTO_TOO_OLD"], ["NO_EXIF_DATA"]
     */
    private List<String> errorCodes;
    
    // ========== Factory Methods ==========
    
    /**
     * Create a success response for an accepted photo.
     * 
     * @param photo the stored photo DTO
     * @return PhotoUploadResponse with HTTP 201
     */
    public static PhotoUploadResponse accepted(CheckInPhotoDTO photo) {
        return PhotoUploadResponse.builder()
            .accepted(true)
            .photo(photo)
            .httpStatus(201)
            .userMessage("Fotografija je uspešno sačuvana.")
            .build();
    }
    
    /**
     * Create a rejection response for a photo that failed validation.
     * 
     * @param photo the photo DTO with rejection details
     * @param errorCode the machine-readable error code
     * @return PhotoUploadResponse with HTTP 400
     */
    public static PhotoUploadResponse rejected(CheckInPhotoDTO photo, String errorCode) {
        return PhotoUploadResponse.builder()
            .accepted(false)
            .photo(photo)
            .httpStatus(400)
            .userMessage("Fotografija nije prihvaćena.")
            .errorCodes(errorCode != null ? List.of(errorCode) : null)
            .build();
    }
    
    /**
     * Create a rejection response with multiple error codes.
     * 
     * @param photo the photo DTO with rejection details
     * @param errorCodes list of machine-readable error codes
     * @return PhotoUploadResponse with HTTP 400
     */
    public static PhotoUploadResponse rejected(CheckInPhotoDTO photo, List<String> errorCodes) {
        return PhotoUploadResponse.builder()
            .accepted(false)
            .photo(photo)
            .httpStatus(400)
            .userMessage("Fotografija nije prihvaćena.")
            .errorCodes(errorCodes)
            .build();
    }
}

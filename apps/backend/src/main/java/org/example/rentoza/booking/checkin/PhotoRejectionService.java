package org.example.rentoza.booking.checkin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.checkin.dto.PhotoRejectionInfo;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;

/**
 * Centralized service for photo rejection logic.
 * 
 * <p>Maps EXIF validation statuses to user-friendly messages and remediation hints.
 * This service is designed to be reusable for both check-in and checkout photo validation.
 * 
 * <h2>Design Principles</h2>
 * <ul>
 *   <li><b>Zero-Storage Policy:</b> Rejected photos should NOT be persisted to the database</li>
 *   <li><b>User-Facing Feedback:</b> All messages are in Serbian with clear remediation steps</li>
 *   <li><b>Audit Trail:</b> Rejection events are logged to check_in_events table</li>
 *   <li><b>Reusability:</b> Generic enough to support checkout photos in the future</li>
 * </ul>
 * 
 * <h2>Rejection Reasons</h2>
 * <pre>
 * REJECTED_TOO_OLD        → Photo timestamp exceeds 30-minute threshold
 * REJECTED_NO_EXIF        → Screenshot or heavily edited image
 * REJECTED_LOCATION_MISMATCH → Photo taken at different location than car
 * REJECTED_NO_GPS         → GPS required but not present
 * REJECTED_FUTURE_TIMESTAMP → Device clock manipulation detected
 * </pre>
 * 
 * @see ExifValidationStatus
 * @see PhotoRejectionInfo
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PhotoRejectionService {

    /**
     * Static mapping of rejection statuses to user-friendly info (Serbian localization).
     * Initialized once at service startup for performance.
     */
    private static final Map<ExifValidationStatus, PhotoRejectionInfo> REJECTION_MAP;
    
    static {
        REJECTION_MAP = new EnumMap<>(ExifValidationStatus.class);
        
        REJECTION_MAP.put(ExifValidationStatus.REJECTED_TOO_OLD, PhotoRejectionInfo.builder()
            .status(ExifValidationStatus.REJECTED_TOO_OLD)
            .rejectionReason("Fotografija je prestara. Mora biti snimljena u poslednjih 30 minuta.")
            .remediationHint("Otvorite kameru na telefonu i napravite novu fotografiju. Ne koristite galeriju.")
            .errorCode("PHOTO_TOO_OLD")
            .build());
        
        REJECTION_MAP.put(ExifValidationStatus.REJECTED_NO_EXIF, PhotoRejectionInfo.builder()
            .status(ExifValidationStatus.REJECTED_NO_EXIF)
            .rejectionReason("Fotografija nema metapodatke (EXIF). Screenshot ili editovana slika nije dozvoljena.")
            .remediationHint("Koristite kameru telefona za snimanje, ne screenshot ili obrađenu fotografiju.")
            .errorCode("NO_EXIF_DATA")
            .build());
        
        REJECTION_MAP.put(ExifValidationStatus.REJECTED_LOCATION_MISMATCH, PhotoRejectionInfo.builder()
            .status(ExifValidationStatus.REJECTED_LOCATION_MISMATCH)
            .rejectionReason("Lokacija fotografije ne odgovara lokaciji vozila.")
            .remediationHint("Uverite se da se nalazite kod vozila i da je GPS uključen na telefonu.")
            .errorCode("LOCATION_MISMATCH")
            .build());
        
        REJECTION_MAP.put(ExifValidationStatus.REJECTED_NO_GPS, PhotoRejectionInfo.builder()
            .status(ExifValidationStatus.REJECTED_NO_GPS)
            .rejectionReason("Fotografija nema GPS koordinate koje su obavezne.")
            .remediationHint("Omogućite pristup lokaciji za kameru u podešavanjima telefona.")
            .errorCode("NO_GPS_DATA")
            .build());
        
        REJECTION_MAP.put(ExifValidationStatus.REJECTED_FUTURE_TIMESTAMP, PhotoRejectionInfo.builder()
            .status(ExifValidationStatus.REJECTED_FUTURE_TIMESTAMP)
            .rejectionReason("Vreme na fotografiji je u budućnosti. Sat na uređaju nije tačan.")
            .remediationHint("Proverite da li je vreme na telefonu tačno podešeno (automatsko vreme).")
            .errorCode("FUTURE_TIMESTAMP")
            .build());
    }

    /**
     * Check if a photo should be rejected based on EXIF validation status.
     * 
     * @param status the EXIF validation status
     * @return true if the photo should be rejected (not stored)
     */
    public boolean shouldReject(ExifValidationStatus status) {
        if (status == null) {
            return false;
        }
        return status.isRejected();
    }

    /**
     * Get rejection information for a given EXIF validation status.
     * 
     * @param status the EXIF validation status
     * @return PhotoRejectionInfo with user-friendly message and hint, or null if not a rejection
     */
    public PhotoRejectionInfo getRejectionInfo(ExifValidationStatus status) {
        if (status == null || !status.isRejected()) {
            return null;
        }
        
        PhotoRejectionInfo info = REJECTION_MAP.get(status);
        if (info == null) {
            // Fallback for any new rejection status not yet mapped
            log.warn("[PhotoRejection] Unknown rejection status encountered: {}", status);
            return PhotoRejectionInfo.builder()
                .status(status)
                .rejectionReason("Fotografija nije prihvaćena zbog validacije.")
                .remediationHint("Pokušajte ponovo sa novom fotografijom iz kamere.")
                .errorCode("UNKNOWN_REJECTION")
                .build();
        }
        
        return info;
    }

    /**
     * Get a user-friendly rejection message for the given status.
     * 
     * @param status the EXIF validation status
     * @return Serbian rejection message, or null if not a rejection
     */
    public String getRejectionReason(ExifValidationStatus status) {
        PhotoRejectionInfo info = getRejectionInfo(status);
        return info != null ? info.getRejectionReason() : null;
    }

    /**
     * Get a remediation hint for the given rejection status.
     * 
     * @param status the EXIF validation status
     * @return Serbian remediation hint, or null if not a rejection
     */
    public String getRemediationHint(ExifValidationStatus status) {
        PhotoRejectionInfo info = getRejectionInfo(status);
        return info != null ? info.getRemediationHint() : null;
    }

    /**
     * Get the error code for the given rejection status.
     * 
     * @param status the EXIF validation status
     * @return Error code string for frontend mapping, or null if not a rejection
     */
    public String getErrorCode(ExifValidationStatus status) {
        PhotoRejectionInfo info = getRejectionInfo(status);
        return info != null ? info.getErrorCode() : null;
    }

    /**
     * Create metadata map for rejection event logging.
     * 
     * @param status the EXIF validation status
     * @param photoType the type of photo being uploaded
     * @param fileSize the size of the rejected file in bytes
     * @return Map of metadata for event recording
     */
    public Map<String, Object> createRejectionEventMetadata(
            ExifValidationStatus status,
            CheckInPhotoType photoType,
            long fileSize) {
        
        PhotoRejectionInfo info = getRejectionInfo(status);
        
        return Map.of(
            "photoType", photoType.name(),
            "exifStatus", status.name(),
            "errorCode", info != null ? info.getErrorCode() : "UNKNOWN",
            "rejectionReason", info != null ? info.getRejectionReason() : "Unknown rejection",
            "fileSize", fileSize,
            "rejected", true
        );
    }
}

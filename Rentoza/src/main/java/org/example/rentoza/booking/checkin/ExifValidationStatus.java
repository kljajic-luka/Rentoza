package org.example.rentoza.booking.checkin;

/**
 * EXIF validation status for check-in photos.
 * 
 * <p>Photos are validated server-side to prevent fraud (e.g., uploading old photos
 * from camera roll instead of fresh captures). Validation checks:
 * 
 * <ul>
 *   <li><b>Timestamp:</b> EXIF DateTimeOriginal must be within 30 minutes of upload</li>
 *   <li><b>Location:</b> GPS coordinates must be within 1km of car/pickup location</li>
 *   <li><b>Presence:</b> EXIF data must exist (pure screenshots rejected)</li>
 *   <li><b>Future check:</b> Timestamp cannot be in the future (device clock manipulation)</li>
 * </ul>
 *
 * @see CheckInPhoto
 */
public enum ExifValidationStatus {
    
    /**
     * Validation pending (photo just uploaded, not yet processed).
     * Async validation job will update status.
     */
    PENDING,
    
    /**
     * Photo passed all EXIF validation checks.
     * - Timestamp within allowed window
     * - GPS within allowed radius
     * - EXIF data present and consistent
     */
    VALID,
    
    /**
     * Photo passed EXIF validation but has no GPS coordinates.
     * Accepted when GPS is not required (default configuration).
     */
    VALID_NO_GPS,
    
    /**
     * Photo passed validation with minor warnings.
     * Examples: GPS slightly outside expected area, timestamp near limit.
     * Accepted but flagged for potential review.
     */
    VALID_WITH_WARNINGS,
    
    /**
     * Photo rejected: EXIF timestamp too old.
     * User attempted to upload a photo from camera roll.
     * Threshold: configurable, default 30 minutes.
     */
    REJECTED_TOO_OLD,
    
    /**
     * Photo rejected: No EXIF data found.
     * Likely a screenshot, downloaded image, or heavily edited photo.
     */
    REJECTED_NO_EXIF,
    
    /**
     * Photo rejected: GPS location doesn't match car/pickup location.
     * User may have taken photo at a different location (fraud indicator).
     */
    REJECTED_LOCATION_MISMATCH,
    
    /**
     * Photo rejected: GPS is required but not present in EXIF.
     * Used when app.checkin.exif.require-gps=true.
     */
    REJECTED_NO_GPS,
    
    /**
     * Photo rejected: EXIF timestamp is in the future.
     * Indicates device clock manipulation (fraud attempt).
     */
    REJECTED_FUTURE_TIMESTAMP,
    
    /**
     * Photo manually approved by admin despite failed validation.
     * Used for edge cases (e.g., network delay caused timestamp mismatch).
     * Requires audit trail of who approved and why.
     */
    OVERRIDE_APPROVED;
    
    /**
     * Check if this status allows the photo to be used in check-in.
     * @return true if photo is usable (VALID, VALID_NO_GPS, VALID_WITH_WARNINGS, or OVERRIDE_APPROVED)
     */
    public boolean isAccepted() {
        return this == VALID || this == VALID_NO_GPS || this == VALID_WITH_WARNINGS || this == OVERRIDE_APPROVED;
    }
    
    /**
     * Check if this status is a rejection.
     * @return true if photo was rejected for any reason
     */
    public boolean isRejected() {
        return name().startsWith("REJECTED_");
    }
    
    /**
     * Check if validation is still in progress.
     * @return true if status is PENDING
     */
    public boolean isPending() {
        return this == PENDING;
    }
}

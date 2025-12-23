package org.example.rentoza.booking.checkin;

/**
 * Event types for the check-in workflow audit trail.
 * 
 * <p>These events are stored in the {@code check_in_events} table as an immutable
 * audit log. They are critical for:
 * <ul>
 *   <li>Insurance claims (proving who did what, when)</li>
 *   <li>Dispute resolution (host vs guest conflicts)</li>
 *   <li>Fraud detection (pattern analysis)</li>
 *   <li>Compliance auditing (GDPR data access logs)</li>
 * </ul>
 * 
 * <h2>Event Categories</h2>
 * <pre>
 * LIFECYCLE:   CHECK_IN_OPENED, CHECK_IN_REMINDER_SENT
 * HOST:        HOST_PHOTO_UPLOADED, HOST_ODOMETER_SUBMITTED, HOST_FUEL_SUBMITTED, etc.
 * GUEST:       GUEST_ID_VERIFIED, GUEST_CONDITION_ACKNOWLEDGED, etc.
 * HANDSHAKE:   HANDSHAKE_HOST_CONFIRMED, HANDSHAKE_GUEST_CONFIRMED, TRIP_STARTED
 * GEOFENCE:    GEOFENCE_CHECK_PASSED, GEOFENCE_CHECK_FAILED
 * NO_SHOW:     NO_SHOW_HOST_TRIGGERED, NO_SHOW_GUEST_TRIGGERED
 * REMOTE:      LOCKBOX_CODE_REVEALED
 * CHECKOUT:    CHECKOUT_INITIATED, CHECKOUT_COMPLETE (Phase 2)
 * </pre>
 *
 * @see CheckInEvent
 */
public enum CheckInEventType {
    
    // ========== WINDOW LIFECYCLE ==========
    
    /**
     * Check-in window opened by scheduler at T-24h.
     * Metadata: {@code {"triggeredBy": "SCHEDULER", "bookingStartDate": "..."}}
     */
    CHECK_IN_OPENED,
    
    /**
     * Reminder notification sent (T-12h or configurable).
     * Metadata: {@code {"channel": "PUSH|EMAIL", "recipient": "HOST|GUEST"}}
     */
    CHECK_IN_REMINDER_SENT,
    
    // ========== HOST ACTIONS ==========
    
    /**
     * Host uploaded a check-in photo.
     * Metadata: {@code {"photoId": 123, "photoType": "HOST_EXTERIOR_FRONT", "exifValid": true}}
     */
    HOST_PHOTO_UPLOADED,
    
    /**
     * Host photo was rejected due to EXIF validation failure.
     * Photo is NOT stored (zero-storage policy). Only event logged for audit.
     * Metadata: {@code {"photoType": "HOST_EXTERIOR_FRONT", "exifStatus": "REJECTED_TOO_OLD", 
     *                   "errorCode": "PHOTO_TOO_OLD", "rejectionReason": "...", "fileSize": 2048000}}
     * 
     * @since Phase 1: Rejected Photo Infrastructure
     */
    HOST_PHOTO_REJECTED,
    
    /**
     * Host submitted odometer reading.
     * Metadata: {@code {"reading": 45678, "extractedFromPhoto": true}}
     */
    HOST_ODOMETER_SUBMITTED,
    
    /**
     * Host submitted fuel level.
     * Metadata: {@code {"levelPercent": 75, "extractedFromPhoto": false}}
     */
    HOST_FUEL_SUBMITTED,
    
    /**
     * Host submitted lockbox code for remote handoff.
     * Metadata: {@code {"codeLength": 4}} (actual code is encrypted, not in metadata)
     */
    HOST_LOCKBOX_SUBMITTED,
    
    /**
     * Host completed all required check-in steps.
     * Triggers transition to CHECK_IN_HOST_COMPLETE status.
     * Metadata: {@code {"photoCount": 8, "odometerSubmitted": true, "fuelSubmitted": true}}
     */
    HOST_SECTION_COMPLETE,
    
    // ========== GUEST ACTIONS ==========
    
    /**
     * Guest identity verification passed.
     * Metadata: {@code {"livenessScore": 0.95, "nameMatchScore": 0.88, "documentType": "DRIVERS_LICENSE"}}
     */
    GUEST_ID_VERIFIED,
    
    /**
     * Guest identity verification failed.
     * Metadata: {@code {"failureReason": "FAILED_LIVENESS", "attempts": 2}}
     */
    GUEST_ID_FAILED,
    
    /**
     * Guest acknowledged vehicle condition based on host photos.
     * Metadata: {@code {"preExistingDamageNoted": true, "damagePhotoIds": [45, 46]}}
     */
    GUEST_CONDITION_ACKNOWLEDGED,
    
    /**
     * Guest marked a damage hotspot on vehicle diagram.
     * Metadata: {@code {"hotspotId": 1, "location": "FRONT_LEFT_FENDER", "description": "..."}}
     */
    GUEST_HOTSPOT_MARKED,
    
    /**
     * Guest completed all required check-in steps.
     * Triggers transition to CHECK_IN_COMPLETE status.
     * Metadata: {@code {"idVerified": true, "conditionAcknowledged": true, "hotspotsMarked": 1}}
     */
    GUEST_SECTION_COMPLETE,
    
    // ========== HANDSHAKE ==========
    
    /**
     * Host confirmed they are ready to hand over the vehicle.
     * Metadata: {@code {"latitude": 44.8176, "longitude": 20.4633, "confirmedAt": "..."}}
     */
    HANDSHAKE_HOST_CONFIRMED,
    
    /**
     * Guest confirmed they received the vehicle.
     * Metadata: {@code {"latitude": 44.8178, "longitude": 20.4635, "distanceFromCar": 15}}
     */
    HANDSHAKE_GUEST_CONFIRMED,
    
    /**
     * Trip officially started (both handshakes complete).
     * Triggers transition to IN_TRIP status. Billing clock starts.
     * Metadata: {@code {"handshakeMethod": "IN_PERSON|REMOTE", "geofenceStatus": "PASSED"}}
     */
    TRIP_STARTED,
    
    // ========== GEOFENCE ==========
    
    /**
     * Guest passed geofence proximity check (within 100m of car).
     * Metadata: {@code {"distanceMeters": 45, "thresholdMeters": 100}}
     */
    GEOFENCE_CHECK_PASSED,
    
    /**
     * Guest failed geofence check (too far from car).
     * May still allow check-in with warning if geofence.strict=false.
     * Metadata: {@code {"distanceMeters": 350, "thresholdMeters": 100, "action": "WARN|BLOCK"}}
     */
    GEOFENCE_CHECK_FAILED,
    
    // ========== LOCATION VARIANCE (Phase 2.4 - Geospatial Migration) ==========
    
    /**
     * Car location variance exceeds WARNING threshold (500m).
     * Host moved the car more than 500m from agreed pickup location.
     * Check-in continues but event logged for audit.
     * Metadata: {@code {"varianceMeters": 750, "threshold": 500, "action": "WARNING"}}
     */
    LOCATION_VARIANCE_WARNING,
    
    /**
     * Car location variance exceeds BLOCKING threshold (2km).
     * Host moved the car more than 2km from agreed pickup location.
     * Check-in is blocked until car is moved closer.
     * Metadata: {@code {"varianceMeters": 2500, "threshold": 2000, "action": "BLOCKED"}}
     */
    LOCATION_VARIANCE_BLOCKING,
    
    // ========== NO-SHOW FLOW ==========
    
    /**
     * Host triggered no-show (failed to complete check-in by T+30m).
     * Triggers transition to NO_SHOW_HOST status.
     * Metadata: {@code {"deadlineAt": "...", "missedBy": "45 minutes"}}
     */
    NO_SHOW_HOST_TRIGGERED,
    
    /**
     * Guest triggered no-show (failed to complete check-in by T+30m after host).
     * Triggers transition to NO_SHOW_GUEST status.
     * Metadata: {@code {"deadlineAt": "...", "hostCompletedAt": "..."}}
     */
    NO_SHOW_GUEST_TRIGGERED,
    
    // ========== REMOTE HANDOFF ==========
    
    /**
     * Lockbox code revealed to guest.
     * Sensitive audit event for remote key handoff.
     * Metadata: {@code {"revealedAt": "...", "guestLatitude": 44.8, "guestLongitude": 20.4}}
     */
    LOCKBOX_CODE_REVEALED,
    
    // ========== PHASE 2: CAR LOCATION DERIVATION ==========
    
    /**
     * Car location derived from first valid photo EXIF GPS.
     * 
     * <p>Phase 2 implementation: Car location is no longer submitted by host.
     * Instead, we derive it from the first uploaded photo with valid EXIF GPS data.
     * This simplifies the check-in flow (Turo-style) and fixes the orphaned
     * carLatitude/carLongitude problem.
     * 
     * <p>Metadata: {@code {"photoId": 123, "photoType": "HOST_EXTERIOR_FRONT", 
     *                       "latitude": 44.8176, "longitude": 20.4624, "photoTimestamp": "...", 
     *                       "derivationMethod": "FIRST_VALID_EXIF"}}
     * 
     * @since Phase 2 - Turo-Style Simplification
     */
    CAR_LOCATION_DERIVED,
    
    /**
     * Car location could not be derived (no photo with EXIF GPS found).
     * 
     * <p>Phase 2 warning event: Logged when host completes check-in but none of the
     * uploaded photos contain GPS coordinates. Check-in proceeds anyway (trust model),
     * but this flags potential issues for admin review.
     * 
     * <p>Causes:
     * <ul>
     *   <li>Device has GPS disabled in camera settings</li>
     *   <li>Photos taken indoors with no GPS signal</li>
     *   <li>Photos edited/compressed after capture (EXIF stripped)</li>
     *   <li>Host used screenshots instead of camera</li>
     * </ul>
     * 
     * <p>Metadata: {@code {"reason": "NO_GPS_IN_PHOTOS", "photoCount": 8, 
     *                       "photosChecked": 8, "photosWithoutGps": 8}}
     * 
     * @since Phase 2 - Turo-Style Simplification
     */
    CAR_LOCATION_MISSING,
    
    // ========== CHECKOUT ==========
    
    /**
     * Checkout process initiated.
     * Metadata: {@code {"initiatedBy": "HOST|GUEST|SCHEDULER", "reason": "TRIP_END|EARLY_RETURN"}}
     */
    CHECKOUT_INITIATED,
    
    /**
     * Guest uploaded a checkout photo.
     * Metadata: {@code {"photoId": 456, "photoType": "CHECKOUT_EXTERIOR_FRONT", "exifValid": true}}
     */
    CHECKOUT_GUEST_PHOTO_UPLOADED,
    
    /**
     * Guest submitted end odometer reading at checkout.
     * Metadata: {@code {"reading": 45890, "totalMileage": 212}}
     */
    CHECKOUT_GUEST_ODOMETER_SUBMITTED,
    
    /**
     * Guest submitted end fuel level at checkout.
     * Metadata: {@code {"levelPercent": 60, "startLevel": 75, "difference": -15}}
     */
    CHECKOUT_GUEST_FUEL_SUBMITTED,
    
    /**
     * Guest completed all required checkout steps.
     * Triggers transition to CHECKOUT_GUEST_COMPLETE status.
     * Metadata: {@code {"photoCount": 6, "odometerSubmitted": true, "fuelSubmitted": true}}
     */
    CHECKOUT_GUEST_SECTION_COMPLETE,
    
    /**
     * Host confirmed vehicle return and condition.
     * Metadata: {@code {"conditionAccepted": true, "newDamageReported": false}}
     */
    CHECKOUT_HOST_CONFIRMED,
    
    /**
     * Host reported new damage found at checkout.
     * Metadata: {@code {"damageDescription": "...", "estimatedCostRsd": 15000, "photoIds": [789, 790]}}
     */
    CHECKOUT_HOST_DAMAGE_REPORTED,
    
    /**
     * Dispute opened during checkout (damage disagreement).
     * Metadata: {@code {"openedBy": "HOST|GUEST", "reason": "DAMAGE_DISPUTE|MILEAGE_DISPUTE"}}
     */
    CHECKOUT_DISPUTE_OPENED,
    
    /**
     * Checkout completed, trip ended.
     * Triggers transition to COMPLETED status.
     * Metadata: {@code {"endOdometer": 45890, "endFuelLevel": 60, "newDamageReported": false, "totalMileage": 212}}
     */
    CHECKOUT_COMPLETE,
    
    /**
     * Late return detected (guest returned after scheduled end time).
     * Metadata: {@code {"scheduledEndTime": "...", "actualReturnTime": "...", "lateMinutes": 45, "lateFeeRsd": 500}}
     */
    LATE_RETURN_DETECTED,
    
    /**
     * Guest initiated early return (before scheduled end date).
     * Metadata: {@code {"scheduledEndDate": "...", "requestedReturnDate": "...", "daysEarly": 2}}
     */
    EARLY_RETURN_INITIATED,
    
    // ========== DUAL-PARTY PHOTO CAPTURE (Phase 1 - Enterprise Upgrade) ==========
    
    /**
     * Guest uploaded a check-in photo (dual-party verification).
     * When guest arrives, they capture the same angles as host to establish
     * bilateral evidence for dispute resolution.
     * 
     * <p>Metadata: {@code {"photoId": 123, "photoType": "GUEST_EXTERIOR_FRONT", 
     *                       "exifValid": true, "sessionId": "uuid-..."}}
     * 
     * @since Enterprise Upgrade Phase 1
     */
    GUEST_CHECK_IN_PHOTO_UPLOADED,
    
    /**
     * Guest check-in photo was rejected due to EXIF validation failure.
     * 
     * <p>Metadata: {@code {"photoType": "GUEST_EXTERIOR_FRONT", "exifStatus": "REJECTED_TOO_OLD",
     *                       "errorCode": "PHOTO_TOO_OLD", "rejectionReason": "..."}}
     * 
     * @since Enterprise Upgrade Phase 1
     */
    GUEST_CHECK_IN_PHOTO_REJECTED,
    
    /**
     * Guest photo validation failed at handshake - not all required photos uploaded.
     * Blocks handshake until guest completes required photo uploads.
     * 
     * <p>Metadata: {@code {"uploadedCount": 5, "requiredCount": 8, "reason": "INCOMPLETE_PHOTOS"}}
     * 
     * @since Enterprise Upgrade Phase 1.5
     */
    GUEST_PHOTO_VALIDATION_FAILED,
    
    /**
     * Guest completed all required dual-party check-in photos.
     * 
     * <p>Metadata: {@code {"photoCount": 8, "validPhotoCount": 8, "sessionId": "uuid-..."}}
     * 
     * @since Enterprise Upgrade Phase 1
     */
    GUEST_CHECK_IN_PHOTOS_COMPLETE,
    
    /**
     * Host uploaded a checkout photo (dual-party verification).
     * When vehicle is returned, host captures the same angles as guest
     * to verify return condition.
     * 
     * <p>Metadata: {@code {"photoId": 456, "photoType": "HOST_CHECKOUT_EXTERIOR_FRONT",
     *                       "exifValid": true, "sessionId": "uuid-..."}}
     * 
     * @since Enterprise Upgrade Phase 1
     */
    HOST_CHECKOUT_PHOTO_UPLOADED,
    
    /**
     * Host checkout photo was rejected due to EXIF validation failure.
     * 
     * <p>Metadata: {@code {"photoType": "HOST_CHECKOUT_EXTERIOR_FRONT", "exifStatus": "REJECTED_NO_EXIF",
     *                       "errorCode": "NO_EXIF_DATA", "rejectionReason": "..."}}
     * 
     * @since Enterprise Upgrade Phase 1
     */
    HOST_CHECKOUT_PHOTO_REJECTED,
    
    /**
     * Host completed all required dual-party checkout photos.
     * 
     * <p>Metadata: {@code {"photoCount": 8, "validPhotoCount": 8, "sessionId": "uuid-..."}}
     * 
     * @since Enterprise Upgrade Phase 1
     */
    HOST_CHECKOUT_PHOTOS_COMPLETE,
    
    // ========== PHOTO DISCREPANCY DETECTION ==========
    
    /**
     * Photo discrepancy detected between host and guest photos.
     * System flagged a difference when comparing same angle photos.
     * 
     * <p>Metadata: {@code {"discrepancyId": 789, "photoType": "EXTERIOR_FRONT",
     *                       "severity": "MEDIUM", "description": "...",
     *                       "hostPhotoId": 123, "guestPhotoId": 456}}
     * 
     * @since Enterprise Upgrade Phase 1
     */
    PHOTO_DISCREPANCY_DETECTED,
    
    /**
     * Photo discrepancy was resolved by admin.
     * 
     * <p>Metadata: {@code {"discrepancyId": 789, "resolution": "DISMISSED",
     *                       "resolvedBy": "admin@example.com", "notes": "..."}}
     * 
     * @since Enterprise Upgrade Phase 1
     */
    PHOTO_DISCREPANCY_RESOLVED,
    
    /**
     * Critical photo discrepancy blocking handover.
     * Requires resolution before check-in can complete.
     * 
     * <p>Metadata: {@code {"discrepancyId": 789, "photoType": "EXTERIOR_LEFT",
     *                       "blocksHandover": true, "severity": "CRITICAL"}}
     * 
     * @since Enterprise Upgrade Phase 1
     */
    PHOTO_DISCREPANCY_BLOCKING,
    
    // ========== OCR VERIFICATION ==========
    
    /**
     * Odometer OCR extraction completed.
     * 
     * <p>Metadata: {@code {"ocrValue": 45678, "userValue": 45680, "confidence": 0.95,
     *                       "discrepancy": 2, "flagged": false, "photoId": 123}}
     * 
     * @since Enterprise Upgrade Phase 1
     */
    ODOMETER_OCR_EXTRACTED,
    
    /**
     * Odometer OCR discrepancy flagged.
     * OCR reading differs significantly from user-submitted value.
     * 
     * <p>Metadata: {@code {"ocrValue": 45000, "userValue": 46000, "difference": 1000,
     *                       "threshold": 10, "flagged": true}}
     * 
     * @since Enterprise Upgrade Phase 1
     */
    ODOMETER_OCR_DISCREPANCY
}

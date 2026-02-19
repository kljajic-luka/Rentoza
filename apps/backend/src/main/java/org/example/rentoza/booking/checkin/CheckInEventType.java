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
     * Guest passed geofence proximity check (within 50m of car).
     * Metadata: {@code {"distanceMeters": 45, "thresholdMeters": 50}}
     */
    GEOFENCE_CHECK_PASSED,
    
    /**
     * Guest failed geofence check (too far from car).
     * May still allow check-in with warning if geofence.strict=false.
     * Metadata: {@code {"distanceMeters": 350, "thresholdMeters": 100, "action": "WARN|BLOCK"}}
     */
    GEOFENCE_CHECK_FAILED,

    // ========== P0: ANTI-SPOOFING EVENTS ==========

    /**
     * GPS spoofing/mock location detected during handshake.
     * FRAUD ALERT: This blocks the handshake and should trigger admin review.
     * Metadata: {@code {"fraudType": "MOCK_LOCATION", "platform": "ANDROID", "deviceFingerprint": "..."}}
     */
    GPS_SPOOFING_DETECTED,

    /**
     * Low GPS accuracy warning (>100m horizontal accuracy).
     * May indicate VPN, proxy, or indoor use. Flagged for review but not blocked.
     * Metadata: {@code {"horizontalAccuracy": 250.0, "platform": "ANDROID"}}
     */
    GPS_LOW_ACCURACY_WARNING,
    
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
     * Host triggered no-show (failed to complete check-in by T+2h).
     * Triggers transition to NO_SHOW_HOST status.
     * Metadata: {@code {"deadlineAt": "...", "missedBy": "2 hours 15 minutes"}}
     */
    NO_SHOW_HOST_TRIGGERED,
    
    /**
     * Guest triggered no-show (failed to complete check-in by T+2h after host).
     * Triggers transition to NO_SHOW_GUEST status.
     * Metadata: {@code {"deadlineAt": "...", "hostCompletedAt": "..."}}
     */
    NO_SHOW_GUEST_TRIGGERED,

    /**
     * No-show refund was processed successfully.
     * Metadata: {@code {"party": "HOST", "refundMode": "PAYMENT_PROVIDER|MOCK"}}
     */
    NO_SHOW_REFUND_PROCESSED,

    /**
     * No-show refund failed and requires intervention.
     * Metadata: {@code {"party": "HOST", "refundMode": "PAYMENT_PROVIDER"}}
     */
    NO_SHOW_REFUND_FAILED,

    /**
     * Admin alert sent for no-show processing outcome.
     * Metadata: {@code {"party": "HOST|GUEST"}}
     */
    NO_SHOW_ADMIN_ALERT_SENT,

    /**
     * Booking auto-cancelled due to stale handshake timeout.
     * Metadata: {@code {"timeoutMinutes": 45, "reason": "HANDSHAKE_NOT_CONFIRMED"}}
     */
    HANDSHAKE_TIMEOUT_AUTO_CANCELLED,
    
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
     * Guest accepted the damage claim at checkout (VAL-010).
     * Deposit will be captured for damage charges.
     * Metadata: {@code {"claimId": 123, "claimAmountRsd": 15000}}
     * 
     * @since VAL-010 - Damage Claim Blocks Deposit Release
     */
    CHECKOUT_DAMAGE_ACCEPTED,
    
    /**
     * Guest disputed the damage claim at checkout (VAL-010).
     * Escalates to admin for resolution.
     * Metadata: {@code {"claimId": 123, "disputeReason": "..."}}
     * 
     * @since VAL-010 - Damage Claim Blocks Deposit Release
     */
    CHECKOUT_DAMAGE_DISPUTED,
    
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
    ODOMETER_OCR_DISCREPANCY,
    
    // ========== PHASE 4: SAFETY IMPROVEMENTS ==========
    
    // ========== 4A: CHECK-IN TIMING & INSURANCE ALIGNMENT ==========
    
    /**
     * Check-in process has begun (first interaction/photo upload).
     * Sent as notification to the other party for awareness.
     * 
     * <p>Metadata: {@code {"party": "HOST|GUEST", "begunAt": "...", "tripStartTime": "..."}}
     * 
     * @since Phase 4 - Safety Improvements
     */
    CHECK_IN_BEGUN,
    
    /**
     * Check-in timing validated successfully.
     * User attempted check-in within the allowed window (max 1 hour before trip start).
     * 
     * <p>Metadata: {@code {"tripStartTime": "...", "attemptTime": "...", 
     *                       "minutesUntilTrip": 45, "maxEarlyMinutes": 60}}
     * 
     * @since Phase 4A - Insurance Alignment
     */
    CHECK_IN_TIMING_VALIDATED,
    
    /**
     * Check-in blocked due to early timing (outside insurance coverage window).
     * User tried to complete check-in more than 1 hour before trip start.
     * 
     * <p>Metadata: {@code {"tripStartTime": "...", "attemptTime": "...", 
     *                       "minutesUntilTrip": 120, "maxEarlyMinutes": 60,
     *                       "earliestAllowedTime": "..."}}
     * 
     * @since Phase 4A - Insurance Alignment
     */
    EARLY_CHECK_IN_BLOCKED,
    
    // ========== 4B: IDENTITY & LICENSE VERIFICATION ==========
    
    /**
     * Host confirmed they verified guest's driver's license in-person.
     * Required step before check-in completion when license verification is mandatory.
     * 
     * <p>Metadata: {@code {"guestUserId": 123, "guestName": "...", 
     *                       "verifiedAt": "...", "hostUserId": 456}}
     * 
     * @since Phase 4B - License Verification
     */
    LICENSE_VERIFIED_IN_PERSON,
    
    /**
     * License verification was skipped (admin override or feature disabled).
     * Logged for audit trail when verification requirement is bypassed.
     * 
     * <p>Metadata: {@code {"reason": "FEATURE_DISABLED|ADMIN_OVERRIDE", 
     *                       "skippedBy": "SYSTEM|admin@example.com"}}
     * 
     * @since Phase 4B - License Verification
     */
    LICENSE_VERIFICATION_SKIPPED,
    
    // ========== 4C: HARDENED NO-SHOW LOGIC ==========
    
    /**
     * No-show processing blocked due to missing messaging attempt.
     * System requires at least one message attempt before triggering no-show.
     * 
     * <p>Metadata: {@code {"reason": "NO_MESSAGE_ATTEMPT", "party": "HOST|GUEST",
     *                       "gracePeriodMinutes": 30, "tripStartTime": "..."}}
     * 
     * @since Phase 4C - No-Show Hardening
     */
    NO_SHOW_BLOCKED_NO_MESSAGE,
    
    /**
     * Messaging attempt logged before no-show detection.
     * Records that user attempted to contact the other party.
     * 
     * <p>Metadata: {@code {"messageId": "...", "sentAt": "...", 
     *                       "sender": "HOST|GUEST", "channel": "IN_APP|SMS"}}
     * 
     * @since Phase 4C - No-Show Hardening
     */
    MESSAGE_ATTEMPT_LOGGED,
    
    // ========== 4D: LATE RETURNS & TIERED PENALTIES ==========
    
    /**
     * Late fee tier 1 applied (0-2 hours late).
     * 
     * <p>Metadata: {@code {"tier": 1, "lateMinutes": 45, "feeRsd": 375, 
     *                       "ratePerHour": 500}}
     * 
     * @since Phase 4D - Tiered Penalties
     */
    LATE_FEE_TIER_1_APPLIED,
    
    /**
     * Late fee tier 2 applied (2-6 hours late).
     * 
     * <p>Metadata: {@code {"tier": 2, "lateMinutes": 240, "feeRsd": 2500, 
     *                       "ratePerHour": 750}}
     * 
     * @since Phase 4D - Tiered Penalties
     */
    LATE_FEE_TIER_2_APPLIED,
    
    /**
     * Late fee tier 3 applied (6-24 hours late).
     * 
     * <p>Metadata: {@code {"tier": 3, "lateMinutes": 600, "feeRsd": 8000, 
     *                       "ratePerHour": 1000}}
     * 
     * @since Phase 4D - Tiered Penalties
     */
    LATE_FEE_TIER_3_APPLIED,
    
    /**
     * Vehicle not returned flag set (24+ hours overdue).
     * Automatic escalation to admin and potential legal action.
     * 
     * <p>Metadata: {@code {"hoursOverdue": 24, "scheduledReturnTime": "...",
     *                       "escalatedToAdmin": true, "adminNotifiedAt": "..."}}
     * 
     * @since Phase 4D - Vehicle Not Returned
     */
    VEHICLE_NOT_RETURNED_FLAG,
    
    // ========== 4E: PHOTO TIMING WINDOWS ==========
    
    /**
     * Photo uploaded after the deadline (reduced evidentiary weight).
     * Photos uploaded more than 24 hours after window opening have secondary weight.
     * 
     * <p>Metadata: {@code {"photoId": 123, "photoType": "HOST_EXTERIOR_FRONT",
     *                       "deadline": "...", "uploadedAt": "...", 
     *                       "lateByMinutes": 180, "evidenceWeight": "SECONDARY"}}
     * 
     * @since Phase 4E - Photo Timing
     */
    PHOTO_UPLOAD_LATE,
    
    /**
     * Photo evidence weight reduced due to late upload.
     * Logged when a late photo's weight is downgraded from PRIMARY to SECONDARY.
     * 
     * <p>Metadata: {@code {"photoId": 123, "previousWeight": "PRIMARY", 
     *                       "newWeight": "SECONDARY", "reason": "LATE_UPLOAD"}}
     * 
     * @since Phase 4E - Photo Timing
     */
    PHOTO_EVIDENCE_WEIGHT_REDUCED,
    
    // ========== 4F: IMPROPER RETURN STATE ==========
    
    /**
     * Improper return flagged by host.
     * Vehicle returned with issues (keys not returned, wrong location, etc.).
     * 
     * <p>Metadata: {@code {"reason": "KEYS_NOT_RETURNED|WRONG_LOCATION|OTHER",
     *                       "keysReturned": false, "correctLocation": false,
     *                       "damageNotes": "...", "photoIds": [789, 790]}}
     * 
     * @since Phase 4F - Improper Return
     */
    IMPROPER_RETURN_FLAGGED,
    
    // ========== 4I: BEGUN NOTIFICATIONS ==========
    
    /**
     * Host has begun their check-in process (first photo or interaction).
     * Notification sent to guest for awareness.
     * 
     * <p>Metadata: {@code {"begunAt": "...", "hostUserId": 456}}
     * 
     * @since Phase 4I - Begun Notifications
     */
    CHECK_IN_HOST_BEGUN,
    
    /**
     * Guest has begun their check-in process.
     * Notification sent to host for awareness.
     * 
     * <p>Metadata: {@code {"begunAt": "...", "guestUserId": 123}}
     * 
     * @since Phase 4I - Begun Notifications
     */
    CHECK_IN_GUEST_BEGUN,
    
    /**
     * Guest has begun their checkout process.
     * Notification sent to host for awareness.
     * 
     * <p>Metadata: {@code {"begunAt": "...", "guestUserId": 123}}
     * 
     * @since Phase 4I - Begun Notifications
     */
    CHECKOUT_GUEST_BEGUN,
    
    // ========== VAL-004 PHASE 6: CHECK-IN DISPUTE TIMEOUT ==========
    
    /**
     * Check-in dispute escalated to senior admin due to 24h timeout.
     * Trip is still viable (>24h until start), so only escalation needed.
     * 
     * <p>Metadata: {@code {"disputeId": 123, "hoursUntilTrip": 36,
     *                       "reason": "No admin response within 24h"}}
     * 
     * @since VAL-004 Phase 6 - Timeout Handling
     */
    DISPUTE_ESCALATED,
    
    /**
     * Check-in dispute auto-cancelled due to timeout with imminent trip start.
     * Trip start time passed or is within 24h, booking cancelled with full refund.
     * 
     * <p>Metadata: {@code {"disputeId": 123, "reason": "Admin did not respond within 24h, trip start imminent",
     *                       "refundProcessed": true}}
     * 
     * @since VAL-004 Phase 6 - Timeout Handling
     */
    DISPUTE_TIMEOUT_AUTO_CANCEL
}

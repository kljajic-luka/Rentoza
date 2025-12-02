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
    EARLY_RETURN_INITIATED
}

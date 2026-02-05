package org.example.rentoza.booking.checkin;

/**
 * Actor roles for check-in events.
 * 
 * <p>Every check-in event is attributed to one of these roles for audit purposes.
 */
public enum CheckInActorRole {
    
    /** Event triggered by the car owner/host */
    HOST,
    
    /** Event triggered by the renter/guest */
    GUEST,
    
    /** Event triggered by automated system (scheduler, background job) */
    SYSTEM
}

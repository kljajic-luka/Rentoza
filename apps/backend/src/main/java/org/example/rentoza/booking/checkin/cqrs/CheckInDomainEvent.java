package org.example.rentoza.booking.checkin.cqrs;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain events for Check-In CQRS pattern.
 * 
 * <p>These events are published by the Command Service when state changes occur,
 * and consumed by the Query Service to update the denormalized read model.
 * 
 * <h2>Event Sourcing Support</h2>
 * <p>Each event contains:
 * <ul>
 *   <li>Unique event ID for deduplication</li>
 *   <li>Booking and session IDs for correlation</li>
 *   <li>Timestamp for ordering</li>
 *   <li>Domain-specific payload</li>
 * </ul>
 * 
 * @see CheckInCommandService for event producers
 * @see CheckInStatusViewSyncListener for event consumers
 */
public sealed interface CheckInDomainEvent {

    /**
     * Get the booking ID associated with this event.
     */
    Long bookingId();

    /**
     * Get the check-in session ID for correlation.
     */
    UUID sessionId();

    /**
     * Get the timestamp when this event occurred.
     */
    Instant occurredAt();

    // ========== HOST WORKFLOW EVENTS ==========

    /**
     * Event: Host has completed their check-in workflow.
     * 
     * <p>Triggered when host submits odometer, fuel level, and all required photos.
     * Guest can now proceed with condition acknowledgment.
     */
    record HostCheckInCompleted(
            Long bookingId,
            UUID sessionId,
            Long hostUserId,
            Instant occurredAt,
            Integer odometerReading,
            Integer fuelLevelPercent
    ) implements CheckInDomainEvent {}

    // ========== GUEST WORKFLOW EVENTS ==========

    /**
     * Event: Guest has acknowledged vehicle condition.
     * 
     * <p>Triggered when guest reviews photos and confirms vehicle condition.
     * Both parties can now proceed with handshake confirmation.
     */
    record GuestConditionAcknowledged(
            Long bookingId,
            UUID sessionId,
            Long guestUserId,
            Instant occurredAt,
            int hotspotCount
    ) implements CheckInDomainEvent {}

    // ========== HANDSHAKE EVENTS ==========

    /**
     * Event: Trip has started after successful handshake.
     * 
     * <p>Triggered when both host and guest confirm the vehicle handoff.
     * The booking transitions to IN_TRIP status.
     */
    record TripStarted(
            Long bookingId,
            UUID sessionId,
            Instant occurredAt,
            String handshakeMethod  // "REMOTE" or "IN_PERSON"
    ) implements CheckInDomainEvent {}

    // ========== NO-SHOW EVENTS ==========

    /**
     * Event: No-show has been processed for a party.
     * 
     * <p>Triggered when grace period expires without workflow completion.
     */
    record NoShowProcessed(
            Long bookingId,
            UUID sessionId,
            String noShowParty,  // "HOST" or "GUEST"
            Instant occurredAt
    ) implements CheckInDomainEvent {}

    // ========== CHECK-IN WINDOW EVENTS ==========

    /**
     * Event: Check-in window has been opened for a booking.
     * 
     * <p>Triggered 24 hours before trip start time.
     * Host can now begin uploading photos.
     */
    record CheckInWindowOpened(
            Long bookingId,
            UUID sessionId,
            Instant occurredAt,
            Instant scheduledStartTime
    ) implements CheckInDomainEvent {}

    // ========== PHOTO EVENTS ==========

    /**
     * Event: A check-in photo has been uploaded.
     * 
     * <p>Triggered when host uploads a new vehicle photo.
     */
    record PhotoUploaded(
            Long bookingId,
            UUID sessionId,
            Long photoId,
            String photoType,
            Instant occurredAt
    ) implements CheckInDomainEvent {}

    /**
     * Event: EXIF validation completed for a photo.
     * 
     * <p>Triggered when async EXIF validation finishes.
     */
    record PhotoValidationCompleted(
            Long bookingId,
            UUID sessionId,
            Long photoId,
            String validationStatus,  // PASSED, FAILED, PENDING
            String validationMessage,
            Instant occurredAt
    ) implements CheckInDomainEvent {}
}

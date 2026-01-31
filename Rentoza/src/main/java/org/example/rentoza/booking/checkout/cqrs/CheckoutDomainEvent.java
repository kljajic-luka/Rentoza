package org.example.rentoza.booking.checkout.cqrs;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain events for Checkout CQRS pattern.
 * 
 * <p>These events are published by the Checkout services when state changes occur,
 * and consumed by view sync listeners to update denormalized read models.
 * 
 * <h2>Event Sourcing Support</h2>
 * <p>Each event contains:
 * <ul>
 *   <li>Unique event ID for deduplication</li>
 *   <li>Booking ID for correlation</li>
 *   <li>Timestamp for ordering</li>
 *   <li>Domain-specific payload</li>
 * </ul>
 * 
 * @see CheckoutSagaOrchestrator for event producers
 * @see CheckoutStatusViewSyncListener for event consumers
 */
public sealed interface CheckoutDomainEvent {

    /**
     * Get the booking ID associated with this event.
     */
    Long bookingId();

    /**
     * Get the timestamp when this event occurred.
     */
    Instant occurredAt();

    // ========== CHECKOUT WINDOW EVENTS ==========

    /**
     * Event: Checkout window has been opened for a booking.
     * 
     * <p>Triggered when trip end time is reached and checkout can begin.
     */
    record CheckoutWindowOpened(
            Long bookingId,
            Long carId,
            Long guestUserId,
            Long hostUserId,
            boolean isEarlyReturn,
            Instant occurredAt
    ) implements CheckoutDomainEvent {
        @Override
        public Instant occurredAt() { return occurredAt; }
    }

    // ========== GUEST WORKFLOW EVENTS ==========

    /**
     * Event: Guest has completed their checkout workflow.
     * 
     * <p>Triggered when guest submits odometer, fuel level, and all required photos.
     */
    record GuestCheckoutCompleted(
            Long bookingId,
            Long guestUserId,
            Integer odometerReading,
            Integer fuelLevelPercent,
            Integer lateMinutes,
            Instant occurredAt
    ) implements CheckoutDomainEvent {
        @Override
        public Instant occurredAt() { return occurredAt; }
    }

    // ========== HOST WORKFLOW EVENTS ==========

    /**
     * Event: Host has completed their checkout workflow.
     * 
     * <p>Triggered when host verifies return condition and submits photos.
     */
    record HostCheckoutCompleted(
            Long bookingId,
            Long hostUserId,
            boolean hasDamageReported,
            String damageDescription,
            Integer estimatedDamageCostRsd,
            Instant occurredAt
    ) implements CheckoutDomainEvent {
        @Override
        public Instant occurredAt() { return occurredAt; }
    }

    // ========== SAGA EVENTS ==========

    /**
     * Event: Checkout saga has started.
     */
    record SagaStarted(
            Long bookingId,
            UUID sagaId,
            Instant occurredAt
    ) implements CheckoutDomainEvent {}

    /**
     * Event: Checkout saga step completed.
     */
    record SagaStepCompleted(
            Long bookingId,
            UUID sagaId,
            String stepName,
            Instant occurredAt
    ) implements CheckoutDomainEvent {}

    /**
     * Event: Extra charges calculated during checkout.
     */
    record ExtraChargesCalculated(
            Long bookingId,
            UUID sagaId,
            BigDecimal mileageCharge,
            BigDecimal fuelCharge,
            BigDecimal lateFee,
            BigDecimal totalExtraCharges,
            Instant occurredAt
    ) implements CheckoutDomainEvent {}

    /**
     * Event: Deposit has been captured.
     */
    record DepositCaptured(
            Long bookingId,
            UUID sagaId,
            BigDecimal amount,
            String transactionId,
            Instant occurredAt
    ) implements CheckoutDomainEvent {}

    /**
     * Event: Deposit has been released.
     */
    record DepositReleased(
            Long bookingId,
            UUID sagaId,
            BigDecimal amount,
            String transactionId,
            Instant occurredAt
    ) implements CheckoutDomainEvent {}

    // ========== COMPLETION EVENTS ==========

    /**
     * Event: Booking has been completed.
     * 
     * <p>Triggered when checkout saga completes successfully.
     */
    record BookingCompleted(
            Long bookingId,
            UUID sagaId,
            BigDecimal finalAmount,
            Integer totalDays,
            Instant occurredAt
    ) implements CheckoutDomainEvent {}

    /**
     * Event: Saga compensation triggered due to failure.
     */
    record SagaCompensating(
            Long bookingId,
            UUID sagaId,
            String failedStep,
            String reason,
            Instant occurredAt
    ) implements CheckoutDomainEvent {}

    /**
     * Event: Saga compensation completed.
     */
    record SagaCompensated(
            Long bookingId,
            UUID sagaId,
            Instant occurredAt
    ) implements CheckoutDomainEvent {}

    // ========== PHOTO EVENTS ==========

    /**
     * Event: A checkout photo has been uploaded.
     */
    record PhotoUploaded(
            Long bookingId,
            Long photoId,
            String photoType,
            String party,  // "host" or "guest"
            Instant occurredAt
    ) implements CheckoutDomainEvent {}

    // ========== LATE RETURN EVENTS ==========

    /**
     * Event: Late return fee applied.
     */
    record LateReturnFeeApplied(
            Long bookingId,
            int hoursLate,
            String feeTier,  // "TIER_1", "TIER_2", "TIER_3"
            BigDecimal feeAmount,
            Instant occurredAt
    ) implements CheckoutDomainEvent {}

    /**
     * Event: Vehicle flagged as not returned.
     */
    record VehicleNotReturned(
            Long bookingId,
            int hoursOverdue,
            Instant occurredAt
    ) implements CheckoutDomainEvent {}
}

package org.example.rentoza.booking.cqrs;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Sealed interface representing domain events for booking workflow.
 * 
 * <h2>Event Categories</h2>
 * <ul>
 *   <li>Booking creation and approval flow</li>
 *   <li>Booking status transitions</li>
 *   <li>Payment-related events</li>
 * </ul>
 * 
 * <h2>CQRS Pattern</h2>
 * <p>Events are published by command services and consumed by view sync listeners
 * to update denormalized read models asynchronously.</p>
 * 
 * @see BookingApprovalService for event producers
 */
public sealed interface BookingDomainEvent {

    // ========== BOOKING CREATION ==========
    
    /**
     * Published when a new booking request is created.
     */
    record BookingCreated(
            Long bookingId,
            Long carId,
            Long guestId,
            Long hostId,
            LocalDateTime startTime,
            LocalDateTime endTime,
            BigDecimal totalPrice,
            Instant occurredAt
    ) implements BookingDomainEvent {}

    // ========== APPROVAL WORKFLOW ==========
    
    /**
     * Published when host approves a booking request.
     */
    record BookingApproved(
            Long bookingId,
            Long hostId,
            String paymentVerificationRef,
            Instant occurredAt
    ) implements BookingDomainEvent {}

    /**
     * Published when host declines a booking request.
     */
    record BookingDeclined(
            Long bookingId,
            Long hostId,
            String reason,
            Instant occurredAt
    ) implements BookingDomainEvent {}

    /**
     * Published when a booking request expires due to host inactivity.
     */
    record BookingExpired(
            Long bookingId,
            Long guestId,
            Long hostId,
            Instant occurredAt
    ) implements BookingDomainEvent {}

    // ========== STATUS TRANSITIONS ==========
    
    /**
     * Published when booking status changes.
     */
    record BookingStatusChanged(
            Long bookingId,
            String previousStatus,
            String newStatus,
            Long triggeredByUserId,
            Instant occurredAt
    ) implements BookingDomainEvent {}

    /**
     * Published when a booking is cancelled.
     */
    record BookingCancelled(
            Long bookingId,
            Long cancelledByUserId,
            String reason,
            String cancellationPolicy,
            BigDecimal refundAmount,
            Instant occurredAt
    ) implements BookingDomainEvent {}

    // ========== PAYMENT EVENTS ==========
    
    /**
     * Published when payment is authorized for a booking.
     */
    record PaymentAuthorized(
            Long bookingId,
            BigDecimal amount,
            String paymentReference,
            Instant occurredAt
    ) implements BookingDomainEvent {}

    /**
     * Published when payment is released (refund on decline/expiry).
     */
    record PaymentReleased(
            Long bookingId,
            BigDecimal amount,
            String reason,
            Instant occurredAt
    ) implements BookingDomainEvent {}
}

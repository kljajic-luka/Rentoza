package org.example.rentoza.booking.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Conversation-safe booking summary DTO for chat enrichment.
 * 
 * <h2>Exact Timestamp Architecture</h2>
 * Returns precise start/end timestamps for trip status calculation.
 * Times are in Europe/Belgrade timezone.
 * 
 * <h2>Purpose</h2>
 * - Provide minimal booking context for chat UI (car details, times, trip status)
 * - Enable chat microservice to enrich conversations without exposing PII
 * - Support both renter and owner perspectives
 * 
 * <h2>Security</h2>
 * - NO PII: No renter/owner names, emails, phone numbers
 * - NO business-sensitive data: No pricing, insurance details
 * - RLS-compliant: Only renter, car owner, or INTERNAL_SERVICE (with user assertion) can access
 * 
 * <h2>Use Cases</h2>
 * - Chat UI: Display "Future trip with 2020 BMW X5" context
 * - Trip status badge: Show FUTURE/CURRENT/PAST/UNAVAILABLE
 * - Messaging control: Enable/disable messaging based on bookingStatus
 * 
 * <h2>Access Pattern</h2>
 * - Direct (JWT): Renter or owner calls GET /api/bookings/{id}/conversation-view
 * - Service-to-Service: Chat service calls with X-Internal-Service-Token + X-Act-As-User-Id
 * 
 * <h2>Related Endpoints</h2>
 * - GET /api/bookings/{id}/conversation-view → Returns BookingConversationDTO
 * - GET /api/bookings/{id} → Returns BookingResponseDTO (OWNER/ADMIN only, full details)
 * - GET /api/bookings/car/{id}/public → Returns BookingSlotDTO[] (public, minimal times)
 */
public record BookingConversationDTO(
    /**
     * Booking identifier
     */
    Long bookingId,
    
    /**
     * Car identifier (for navigation/linking)
     */
    Long carId,
    
    /**
     * Car brand (e.g., "BMW", "Audi")
     */
    String carBrand,
    
    /**
     * Car model (e.g., "X5", "A4")
     */
    String carModel,
    
    /**
     * Car year (e.g., 2020)
     */
    Integer carYear,
    
    /**
     * Car image URL (nullable)
     */
    String carImageUrl,
    
    /**
     * Exact trip start timestamp (ISO-8601 format: "2025-10-10T10:00:00")
     */
    LocalDateTime startTime,
    
    /**
     * Exact trip end timestamp (ISO-8601 format: "2025-10-12T10:00:00")
     */
    LocalDateTime endTime,
    
    /**
     * Booking status: PENDING_APPROVAL, ACTIVE, DECLINED, EXPIRED, CANCELLED, COMPLETED (canonical enum values)
     */
    String bookingStatus,
    
    /**
     * Computed trip status for UI display (date-first computation):
     * - FUTURE: now < startTime
     * - CURRENT: startTime <= now <= endTime
     * - PAST: now > endTime
     * - UNAVAILABLE: times missing or invalid
     */
    String tripStatus,
    
    /**
     * Whether messaging is allowed for this booking.
     * Derived from bookingStatus: true only if ACTIVE or in check-in/trip phase
     */
    boolean messagingAllowed
) {
    /**
     * Constructor from Booking entity (server-side mapping).
     * Computes tripStatus and messagingAllowed based on times and status.
     * 
     * Image priority: imageUrls[0] > imageUrl (fallback to legacy field)
     */
    public BookingConversationDTO(org.example.rentoza.booking.Booking booking) {
        this(
            booking.getId(),
            booking.getCar().getId(),
            booking.getCar().getBrand(),
            booking.getCar().getModel(),
            booking.getCar().getYear(),
            extractCarImageUrl(booking.getCar()),
            booking.getStartTime(),
            booking.getEndTime(),
            booking.getStatus().name(),
            computeTripStatus(booking.getStartTime(), booking.getEndTime()),
            computeMessagingAllowed(booking.getStatus())
        );
    }
    
    /**
     * Extract first available car image URL.
     * Priority: imageUrls[0] > imageUrl (legacy) > null
     */
    private static String extractCarImageUrl(org.example.rentoza.car.Car car) {
        List<String> images = car.getImageUrls();

        if (images != null) {
            try {
                if (!images.isEmpty()) {
                    String first = images.get(0);
                    if (first != null && !first.isBlank()) return first;
                }
            } catch (org.hibernate.LazyInitializationException e) {
                // session was closed, ignore and fallback
            }
        }

        if (car.getImageUrl() != null && !car.getImageUrl().isBlank()) {
            return car.getImageUrl();
        }
        return null;
    }
    
    /**
     * Compute trip status based on current time and booking timestamps.
     * Public static method for use in service logging.
     */
    public static String computeTripStatusFromTimes(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime == null || endTime == null) {
            return "UNAVAILABLE";
        }
        
        LocalDateTime now = LocalDateTime.now();
        
        if (now.isBefore(startTime)) {
            return "FUTURE";
        } else if (now.isAfter(endTime)) {
            return "PAST";
        } else {
            return "CURRENT";
        }
    }

    /**
     * Compute trip status based on dates (for backward compatibility).
     * Uses the date portion of timestamps for comparison.
     */
    public static String computeTripStatusFromDates(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            return "UNAVAILABLE";
        }
        
        LocalDate today = LocalDate.now();
        
        if (today.isBefore(startDate)) {
            return "FUTURE";
        } else if (today.isAfter(endDate)) {
            return "PAST";
        } else {
            return "CURRENT";
        }
    }
    
    /**
     * Compute trip status based on current time and booking timestamps.
     * Private helper for record constructor.
     */
    private static String computeTripStatus(LocalDateTime startTime, LocalDateTime endTime) {
        return computeTripStatusFromTimes(startTime, endTime);
    }
    
    /**
     * Compute whether messaging is allowed based on booking status.
     * 
     * Messaging enabled for:
     * - ACTIVE: Booking approved, waiting for check-in window
     * - CHECK_IN_OPEN: Check-in window is open
     * - CHECK_IN_HOST_COMPLETE: Host completed, guest pending
     * - CHECK_IN_COMPLETE: Both parties completed, awaiting handshake
     * - IN_TRIP: Trip is in progress
     * 
     * Messaging disabled for:
     * - PENDING_APPROVAL: Awaiting host approval (no chat until approved)
     * - DECLINED: Host declined request (no need for chat)
     * - EXPIRED: Request timed out (no need for chat) - legacy status
     * - EXPIRED_SYSTEM: System auto-expired due to host inactivity (no need for chat)
     * - CANCELLED: User/host cancelled (chat closed)
     * - COMPLETED: Trip finished (chat closed)
     * - NO_SHOW_HOST/NO_SHOW_GUEST: No-show scenarios (dispute channel, not chat)
     * 
     * Rationale:
     * - PENDING_APPROVAL: No chat conversation exists yet (created on approval)
     * - DECLINED/EXPIRED/EXPIRED_SYSTEM: Request rejected, no trip happening
     * - CANCELLED/COMPLETED: Trip lifecycle ended
     * - NO_SHOW: Escalated to dispute resolution, not normal chat
     */
    private static boolean computeMessagingAllowed(org.example.rentoza.booking.BookingStatus status) {
        if (status == null) {
            return false;
        }
        return switch (status) {
            case ACTIVE, CHECK_IN_OPEN, CHECK_IN_HOST_COMPLETE, CHECK_IN_COMPLETE, IN_TRIP -> true;
            case PENDING_APPROVAL, DECLINED, EXPIRED, EXPIRED_SYSTEM, CANCELLED, COMPLETED,
                 NO_SHOW_HOST, NO_SHOW_GUEST -> false;
            default -> false;
        };
    }

    /**
     * Get start date for display purposes.
     */
    public LocalDate getStartDate() {
        return startTime != null ? startTime.toLocalDate() : null;
    }

    /**
     * Get end date for display purposes.
     */
    public LocalDate getEndDate() {
        return endTime != null ? endTime.toLocalDate() : null;
    }
}

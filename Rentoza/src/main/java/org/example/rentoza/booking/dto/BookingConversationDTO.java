package org.example.rentoza.booking.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Conversation-safe booking summary DTO for chat enrichment.
 * 
 * Purpose:
 * - Provide minimal booking context for chat UI (car details, dates, trip status)
 * - Enable chat microservice to enrich conversations without exposing PII
 * - Support both renter and owner perspectives
 * 
 * Security:
 * - NO PII: No renter/owner names, emails, phone numbers
 * - NO business-sensitive data: No pricing, insurance details, pickup times
 * - RLS-compliant: Only renter, car owner, or INTERNAL_SERVICE (with user assertion) can access
 * 
 * Use Cases:
 * - Chat UI: Display "Future trip with 2020 BMW X5" context
 * - Trip status badge: Show FUTURE/CURRENT/PAST/UNAVAILABLE
 * - Messaging control: Enable/disable messaging based on bookingStatus
 * 
 * Access Pattern:
 * - Direct (JWT): Renter or owner calls GET /api/bookings/{id}/conversation-view
 * - Service-to-Service: Chat service calls with X-Internal-Service-Token + X-Act-As-User-Id
 * 
 * Related Endpoints:
 * - GET /api/bookings/{id}/conversation-view → Returns BookingConversationDTO
 * - GET /api/bookings/{id} → Returns BookingResponseDTO (OWNER/ADMIN only, full details)
 * - GET /api/bookings/car/{id}/public → Returns BookingSlotDTO[] (public, minimal dates)
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
     * Booking start date (ISO-8601 format)
     */
    LocalDate startDate,
    
    /**
     * Booking end date (ISO-8601 format)
     */
    LocalDate endDate,
    
    /**
     * Booking status: PENDING_APPROVAL, ACTIVE, DECLINED, EXPIRED, CANCELLED, COMPLETED (canonical enum values)
     */
    String bookingStatus,
    
    /**
     * Computed trip status for UI display (date-first computation):
     * - FUTURE: today < startDate
     * - CURRENT: startDate <= today <= endDate
     * - PAST: today > endDate
     * - UNAVAILABLE: dates missing or invalid
     */
    String tripStatus,
    
    /**
     * Whether messaging is allowed for this booking.
     * Derived from bookingStatus: true only if ACTIVE
     */
    boolean messagingAllowed
) {
    /**
     * Constructor from Booking entity (server-side mapping).
     * Computes tripStatus and messagingAllowed based on dates and status.
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
            booking.getStartDate(),
            booking.getEndDate(),
            booking.getStatus().name(),
            computeTripStatus(booking.getStartDate(), booking.getEndDate()),
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
                if (!images.isEmpty()) {  // this line triggers LazyInitialization
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
     * Compute trip status based on current date and booking dates.
     * Public static method for use in service logging.
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
     * Compute trip status based on current date and booking dates.
     * Private helper for record constructor.
     */
    private static String computeTripStatus(LocalDate startDate, LocalDate endDate) {
        return computeTripStatusFromDates(startDate, endDate);
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
        return switch (status) {
            case ACTIVE, CHECK_IN_OPEN, CHECK_IN_HOST_COMPLETE, CHECK_IN_COMPLETE, IN_TRIP -> true;
            case PENDING_APPROVAL, DECLINED, EXPIRED, EXPIRED_SYSTEM, CANCELLED, COMPLETED,
                 NO_SHOW_HOST, NO_SHOW_GUEST -> false;
        };
    }
}

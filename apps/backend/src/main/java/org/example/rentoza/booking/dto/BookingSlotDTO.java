package org.example.rentoza.booking.dto;

import java.time.LocalDateTime;

/**
 * Public-safe DTO for calendar availability display.
 * 
 * <h2>Exact Timestamp Architecture</h2>
 * Returns precise start/end timestamps for calendar blocking.
 * Times are in Europe/Belgrade timezone.
 * 
 * <h2>Purpose</h2>
 * - Expose only time ranges for bookings without revealing sensitive information
 * - Used by public/renter-facing calendar views to show unavailable times
 * - Does NOT include: renter details, owner details, pricing, booking status
 * 
 * <h2>Security</h2>
 * - This DTO is safe for public/authenticated user consumption
 * - No PII (Personally Identifiable Information) exposure
 * - No business-sensitive data (pricing, renter identity)
 * 
 * <h2>Use Case</h2>
 * - Angular calendar component needs to know which times are blocked
 * - Regular renters (ROLE_USER) need to see unavailable periods
 * - Guests (unauthenticated) may need to see unavailable periods
 * 
 * <h2>Calendar Display</h2>
 * Since we use full-day blocking for calendar display, the frontend should
 * gray out entire days if any hours within that day are booked.
 * 
 * <h2>Related Endpoints</h2>
 * - GET /api/bookings/car/{carId}/public → Returns List&lt;BookingSlotDTO&gt;
 * - GET /api/bookings/car/{carId} → Returns List&lt;BookingResponseDTO&gt; (OWNER/ADMIN only)
 */
public record BookingSlotDTO(
    Long carId,
    LocalDateTime startTime,
    LocalDateTime endTime
) {
    /**
     * Constructor from Booking entity (server-side mapping)
     */
    public BookingSlotDTO(org.example.rentoza.booking.Booking booking) {
        this(
            booking.getCar().getId(),
            booking.getStartTime(),
            booking.getEndTime()
        );
    }

    /**
     * Get start date for calendar display (extracts date from timestamp).
     * Used for full-day blocking on calendar UI.
     */
    public java.time.LocalDate getStartDate() {
        return startTime != null ? startTime.toLocalDate() : null;
    }

    /**
     * Get end date for calendar display (extracts date from timestamp).
     * Used for full-day blocking on calendar UI.
     */
    public java.time.LocalDate getEndDate() {
        return endTime != null ? endTime.toLocalDate() : null;
    }
}

package org.example.rentoza.booking.dto;

import java.time.LocalDate;

/**
 * Public-safe DTO for calendar availability display.
 * 
 * Purpose:
 * - Expose only date ranges for bookings without revealing sensitive information
 * - Used by public/renter-facing calendar views to show unavailable dates
 * - Does NOT include: renter details, owner details, pricing, booking status
 * 
 * Security:
 * - This DTO is safe for public/authenticated user consumption
 * - No PII (Personally Identifiable Information) exposure
 * - No business-sensitive data (pricing, renter identity)
 * 
 * Use Case:
 * - Angular calendar component needs to know which dates are blocked
 * - Regular renters (ROLE_USER) need to see unavailable dates
 * - Guests (unauthenticated) may need to see unavailable dates
 * 
 * Related Endpoints:
 * - GET /api/bookings/car/{carId}/public → Returns List<BookingSlotDTO>
 * - GET /api/bookings/car/{carId} → Returns List<BookingResponseDTO> (OWNER/ADMIN only)
 */
public record BookingSlotDTO(
    Long carId,
    LocalDate startDate,
    LocalDate endDate
) {
    /**
     * Constructor from Booking entity (server-side mapping)
     */
    public BookingSlotDTO(org.example.rentoza.booking.Booking booking) {
        this(
            booking.getCar().getId(),
            booking.getStartDate(),
            booking.getEndDate()
        );
    }
}

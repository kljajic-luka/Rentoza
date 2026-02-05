package org.example.chatservice.dto.client;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Conversation-safe booking summary DTO for chat enrichment (client-side copy).
 * 
 * Purpose:
 * - Receive minimal booking context from Main API for chat UI enrichment
 * - Display "Future trip with 2020 BMW X5" in conversation headers
 * - NO PII: No renter/owner names, emails, phone numbers, pricing
 * 
 * Source:
 * - Main API: GET /api/bookings/{id}/conversation-view
 * - Mapped from: org.example.rentoza.booking.dto.BookingConversationDTO
 * 
 * Security:
 * - RLS-compliant: Only renter, car owner, or INTERNAL_SERVICE (with user assertion) can access
 * - NO business-sensitive data: No pricing, insurance details, pickup times
 * 
 * Use Cases:
 * - Chat UI: Display trip context in conversation header
 * - Trip status badge: Show FUTURE/CURRENT/PAST/UNAVAILABLE
 * - Messaging control: Enable/disable messaging based on bookingStatus
 * 
 * Fallback Strategy:
 * - If enrichment fails (404, 403, timeout), show "Unknown car" with disabled messaging
 * - Graceful degradation: Chat functionality remains operational without enrichment
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BookingConversationDTO {
    
    /**
     * Booking identifier
     */
    private Long bookingId;
    
    /**
     * Car identifier (for navigation/linking)
     */
    private Long carId;
    
    /**
     * Car brand (e.g., "BMW", "Audi")
     */
    private String carBrand;
    
    /**
     * Car model (e.g., "X5", "A4")
     */
    private String carModel;
    
    /**
     * Car year (e.g., 2020)
     */
    private Integer carYear;
    
    /**
     * Car image URL (nullable)
     */
    private String carImageUrl;
    
    /**
     * Booking start date (ISO-8601 format)
     */
    private LocalDate startDate;
    
    /**
     * Booking end date (ISO-8601 format)
     */
    private LocalDate endDate;
    
    /**
     * Booking status: ACTIVE, CANCELLED, COMPLETED (canonical enum values)
     */
    private String bookingStatus;
    
    /**
     * Computed trip status for UI display (date-first computation):
     * - FUTURE: today < startDate
     * - CURRENT: startDate <= today <= endDate
     * - PAST: today > endDate
     * - UNAVAILABLE: dates missing, invalid, or enrichment failed
     */
    private String tripStatus;
    
    /**
     * Whether messaging is allowed for this booking.
     * Derived from bookingStatus: true only if ACTIVE
     */
    private boolean messagingAllowed;
    
    /**
     * Renter's display name (first name + last initial)
     */
    private String renterName;
    
    /**
     * Owner's display name (first name + last initial)
     */
    private String ownerName;
    
    /**
     * Renter's profile picture URL (nullable)
     */
    private String renterProfilePicUrl;
    
    /**
     * Owner's profile picture URL (nullable)
     */
    private String ownerProfilePicUrl;
    
    /**
     * Create a fallback DTO for failed enrichment attempts.
     * Shows "Unknown car" with disabled messaging.
     * 
     * @param bookingId Booking ID from conversation
     * @return Fallback BookingConversationDTO with minimal data
     */
    public static BookingConversationDTO createFallback(String bookingId) {
        BookingConversationDTO fallback = new BookingConversationDTO();
        fallback.setBookingId(bookingId != null ? Long.parseLong(bookingId) : null);
        fallback.setCarId(null);
        fallback.setCarBrand("Unknown");
        fallback.setCarModel("Unknown");
        fallback.setCarYear(null);
        fallback.setCarImageUrl(null);
        fallback.setStartDate(null);
        fallback.setEndDate(null);
        fallback.setBookingStatus("UNAVAILABLE");
        fallback.setTripStatus("UNAVAILABLE");
        fallback.setMessagingAllowed(false);
        fallback.setRenterName("User");
        fallback.setOwnerName("Owner");
        fallback.setRenterProfilePicUrl(null);
        fallback.setOwnerProfilePicUrl(null);
        return fallback;
    }
    
    /**
     * Get human-readable trip description for UI display.
     * Examples:
     * - "Future trip with 2020 BMW X5"
     * - "Current trip with 2021 Audi A4"
     * - "Past trip with 2019 Mercedes C-Class"
     * - "Unknown car" (fallback)
     * 
     * @return Formatted trip description
     */
    public String getFormattedTripDescription() {
        if ("Unknown".equals(carBrand) || "Unknown".equals(carModel)) {
            return "Unknown car";
        }
        
        String tripType = switch (tripStatus) {
            case "FUTURE" -> "Future trip";
            case "CURRENT" -> "Current trip";
            case "PAST" -> "Past trip";
            default -> "Trip";
        };
        
        String carDescription = carYear != null 
                ? String.format("%d %s %s", carYear, carBrand, carModel)
                : String.format("%s %s", carBrand, carModel);
        
        return String.format("%s with %s", tripType, carDescription);
    }
}

package org.example.rentoza.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Guest preview DTO for host approval workflow.
 * Contains enterprise-grade information for hosts to make informed booking decisions.
 * 
 * Privacy: No PII exposed (no full name, email, phone number, address).
 * Only verification STATUS flags are shared, not actual data.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestBookingPreviewDTO {
    // Basic Profile
    private String profilePhotoUrl;
    private String firstName;
    private String lastInitial; // e.g., "S."
    private String joinDate; // e.g., "Oct 2021"
    
    // Trust Signals (provenance-backed renter verification only)
    private List<GuestTrustSignalDTO> trustSignals;
    private String drivingEligibilityStatus; // "APPROVED", "PENDING_REVIEW", "REJECTED", "NOT_STARTED"
    
    // Guest Demographics
    private Integer age;                        // Calculated from DOB
    private boolean ageVerified;                // From OCR (trusted) vs self-reported
    
    // Driving Experience
    private String licenseCountry;              // e.g., "SRB", "HRV", "DEU"
    private String licenseCategories;           // e.g., "B", "B,C", "B+E"
    private Integer licenseTenureMonths;        // How long they've held license
    private LocalDate licenseExpiryDate;        // When license expires
    
    // Reliability Stats
    private Double starRating;
    private int tripCount;                      // Completed trips
    private int cancelledTripsCount;            // Guest-initiated cancellations
    private Double cancellationRate;            // cancelledTripsCount / totalBookings * 100
    
    // Badges (enterprise achievements, NOT verification badges)
    private List<String> badges;                // e.g., "Experienced Guest", "Top Rated"
    
    // Host Reviews
    private List<ReviewPreviewDTO> hostReviews;
    
    // Trip Details
    private LocalDateTime requestedStartDateTime;
    private LocalDateTime requestedEndDateTime;
    private String message; // Optional message from guest
    private String protectionPlan;
}


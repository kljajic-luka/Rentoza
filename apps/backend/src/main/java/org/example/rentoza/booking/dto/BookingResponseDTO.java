package org.example.rentoza.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.common.GeoPoint;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for booking response.
 * 
 * <h2>Exact Timestamp Architecture</h2>
 * Returns precise start/end timestamps for frontend display.
 * Times are in Europe/Belgrade timezone.
 * 
 * <h2>Geospatial Location (Phase 2.4)</h2>
 * Includes pickup location snapshot and delivery fee details.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BookingResponseDTO {

    private Long id;
    
    /**
     * Exact trip start timestamp.
     * Format: ISO-8601 LocalDateTime (e.g., "2025-10-10T10:00:00")
     */
    private LocalDateTime startTime;
    
    /**
     * Exact trip end timestamp.
     * Format: ISO-8601 LocalDateTime (e.g., "2025-10-12T10:00:00")
     */
    private LocalDateTime endTime;
    
    private BigDecimal totalPrice;
    private BookingStatus status;
    private String createdAt;
    private LocalDateTime decisionDeadlineAt;
    private LocalDateTime approvedAt;
    private LocalDateTime declinedAt;
    private String declineReason;
    private AgreementSummaryDTO agreementSummary;

    // ========== PRICE BREAKDOWN (Turo Standard) ==========
    
    /**
     * Base rental price (days × daily rate) before any fees.
     */
    private BigDecimal basePrice;
    
    /**
     * Service/platform fee (percentage of base price).
     */
    private BigDecimal serviceFee;
    
    /**
     * Insurance cost (additional cost based on selected tier).
     */
    private BigDecimal insuranceCost;
    
    /**
     * Insurance type selected (BASIC, STANDARD, PREMIUM).
     */
    private String insuranceType;
    
    /**
     * Security deposit amount (held, not charged; released after trip).
     */
    private BigDecimal securityDeposit;
    
    /**
     * Prepaid refuel cost (if selected).
     */
    private BigDecimal refuelCost;
    
    /**
     * Payment status (PENDING, AUTHORIZED, PAID, RELEASED, etc.)
     */
    private String paymentStatus;

    // Review flags
    private Boolean hasOwnerReview;

    // Car details
    private CarDetailsDTO car;

    // Renter details
    private RenterDetailsDTO renter;
    
    // ========== GEOSPATIAL PICKUP LOCATION (Phase 2.4) ==========
    
    /**
     * Agreed pickup location snapshot (immutable after booking creation).
     */
    private PickupLocationDTO pickupLocation;
    
    /**
     * Delivery distance in kilometers (null if self-pickup).
     */
    private BigDecimal deliveryDistanceKm;
    
    /**
     * Calculated delivery fee (0 if self-pickup or free delivery).
     */
    private BigDecimal deliveryFee;
    
    /**
     * Nested DTO for pickup location details.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PickupLocationDTO {
        private BigDecimal latitude;
        private BigDecimal longitude;
        private String address;
        private String city;
        private String zipCode;
        
        public PickupLocationDTO(GeoPoint geoPoint) {
            if (geoPoint != null) {
                this.latitude = geoPoint.getLatitude();
                this.longitude = geoPoint.getLongitude();
                this.address = geoPoint.getAddress();
                this.city = geoPoint.getCity();
                this.zipCode = geoPoint.getZipCode();
            }
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CarDetailsDTO {
        private Long id;
        private String brand;
        private String model;
        private Integer year;
        private String imageUrl;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RenterDetailsDTO {
        private Long id;
        private String firstName;
        private String lastName;
        private String email;
        private String phone;
        private String avatarUrl;
    }

    public BookingResponseDTO(Booking booking) {
        this.id = booking.getId();
        this.startTime = booking.getStartTime();
        this.endTime = booking.getEndTime();
        this.totalPrice = booking.getTotalPrice();
        this.status = booking.getStatus();
        this.createdAt = booking.getCreatedAt() != null ? booking.getCreatedAt().toString() : null;
        this.decisionDeadlineAt = booking.getDecisionDeadlineAt();
        this.approvedAt = booking.getApprovedAt();
        this.declinedAt = booking.getDeclinedAt();
        this.declineReason = booking.getDeclineReason();
        
        // Payment & deposit info
        this.paymentStatus = booking.getPaymentStatus();
        this.securityDeposit = booking.getSecurityDeposit();
        this.insuranceType = booking.getInsuranceType();

        // Reconstruct price breakdown from booking data
        // P2 FIX: Use persisted snapshots where available to prevent price drift.
        // Falls back to recalculation only for legacy bookings created before V58.
        if (booking.getCar() != null && booking.getStartTime() != null && booking.getEndTime() != null) {
            long hours = java.time.temporal.ChronoUnit.HOURS.between(booking.getStartTime(), booking.getEndTime());
            int periods = Math.max(1, (int) Math.ceil(hours / 24.0));
            java.math.BigDecimal dailyRate = booking.getSnapshotDailyRate() != null 
                    ? booking.getSnapshotDailyRate() 
                    : booking.getCar().getPricePerDay();
            this.basePrice = dailyRate.multiply(java.math.BigDecimal.valueOf(periods));
            
            // Insurance cost — use persisted snapshot if available
            if (booking.getInsuranceCostSnapshot() != null) {
                this.insuranceCost = booking.getInsuranceCostSnapshot();
            } else {
                // Legacy fallback: recalculate from current rates
                java.math.BigDecimal insuranceMultiplier = switch (booking.getInsuranceType() != null ? booking.getInsuranceType().toUpperCase() : "BASIC") {
                    case "STANDARD" -> new java.math.BigDecimal("1.10");
                    case "PREMIUM" -> new java.math.BigDecimal("1.20");
                    default -> java.math.BigDecimal.ONE;
                };
                this.insuranceCost = this.basePrice.multiply(insuranceMultiplier)
                        .subtract(this.basePrice)
                        .setScale(2, java.math.RoundingMode.HALF_UP);
            }
            
            // Service fee — use persisted snapshot if available
            if (booking.getServiceFeeSnapshot() != null) {
                this.serviceFee = booking.getServiceFeeSnapshot();
            } else {
                // Legacy fallback: recalculate with 15% default
                this.serviceFee = this.basePrice
                        .multiply(new java.math.BigDecimal("0.15"))
                        .setScale(2, java.math.RoundingMode.HALF_UP);
            }
            
            // Refuel cost (reconstruct from car data)
            if (booking.isPrepaidRefuel() && booking.getCar().getFuelConsumption() != null) {
                this.refuelCost = new java.math.BigDecimal(booking.getCar().getFuelConsumption().toString())
                        .multiply(new java.math.BigDecimal("6.5"))
                        .multiply(java.math.BigDecimal.TEN)
                        .setScale(2, java.math.RoundingMode.HALF_UP);
            } else {
                this.refuelCost = java.math.BigDecimal.ZERO;
            }
        }

        // Map car details
        if (booking.getCar() != null) {
            this.car = new CarDetailsDTO(
                    booking.getCar().getId(),
                    booking.getCar().getBrand(),
                    booking.getCar().getModel(),
                    booking.getCar().getYear(),
                    booking.getCar().getImageUrl()
            );
        }

        // Map renter details (including contact info and avatar for host coordination)
        if (booking.getRenter() != null) {
            this.renter = new RenterDetailsDTO(
                    booking.getRenter().getId(),
                    booking.getRenter().getFirstName(),
                    booking.getRenter().getLastName(),
                    booking.getRenter().getEmail(),
                    booking.getRenter().getPhone(),
                    booking.getRenter().getAvatarUrl()
            );
        }
        
        // Map geospatial pickup location (Phase 2.4)
        if (booking.getPickupLocation() != null) {
            this.pickupLocation = new PickupLocationDTO(booking.getPickupLocation());
        }
        this.deliveryDistanceKm = booking.getDeliveryDistanceKm();
        this.deliveryFee = booking.getDeliveryFeeCalculated();
    }
}

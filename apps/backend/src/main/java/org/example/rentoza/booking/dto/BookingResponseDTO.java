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

package org.example.rentoza.booking.checkout.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.dto.CheckInPhotoDTO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for checkout status response.
 * 
 * <p>Contains complete checkout state including:
 * <ul>
 *   <li>Current status and phase completion flags</li>
 *   <li>Check-in photos (for comparison)</li>
 *   <li>Checkout photos</li>
 *   <li>Odometer/fuel readings (start vs end)</li>
 *   <li>Damage assessment status</li>
 *   <li>Late return information</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckOutStatusDTO {
    
    private Long bookingId;
    private String checkoutSessionId;
    private BookingStatus status;
    
    // ========== PHASE COMPLETION ==========
    
    private boolean checkoutWindowOpen;
    private boolean guestCheckOutComplete;
    private boolean hostCheckOutComplete;
    private boolean checkoutComplete;
    
    // ========== TIMESTAMPS ==========
    
    private LocalDateTime checkoutOpenedAt;
    private LocalDateTime guestCompletedAt;
    private LocalDateTime hostCompletedAt;
    private LocalDateTime checkoutCompletedAt;
    
    // ========== TRIP TIMING ==========
    
    private LocalDateTime tripStartedAt;
    private LocalDateTime scheduledReturnTime;
    private LocalDateTime actualReturnTime;
    private Integer lateReturnMinutes;
    private BigDecimal lateFeeAmount;
    
    // ========== ODOMETER & FUEL ==========
    
    private Integer startOdometer;
    private Integer endOdometer;
    private Integer totalMileage;
    
    private Integer startFuelLevel;
    private Integer endFuelLevel;
    private Integer fuelDifference;
    
    // ========== PHOTOS ==========
    
    /**
     * Check-in photos (for comparison).
     */
    private List<CheckInPhotoDTO> checkInPhotos;
    
    /**
     * Checkout photos submitted by guest.
     */
    private List<CheckInPhotoDTO> checkoutPhotos;
    
    /**
     * Host checkout confirmation/damage photos.
     */
    private List<CheckInPhotoDTO> hostCheckoutPhotos;
    
    // ========== DAMAGE ASSESSMENT ==========
    
    private boolean newDamageReported;
    private String damageDescription;
    private BigDecimal damageClaimAmount;
    private String damageClaimStatus;
    
    // ========== ROLE FLAGS ==========
    
    private boolean isHost;
    private boolean isGuest;
    
    // ========== VEHICLE INFO ==========
    
    private CarSummaryDTO car;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CarSummaryDTO {
        private Long id;
        private String brand;
        private String model;
        private Integer year;
        private String imageUrl;
    }
}


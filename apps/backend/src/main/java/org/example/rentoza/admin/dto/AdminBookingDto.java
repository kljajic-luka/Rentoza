package org.example.rentoza.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Admin view of a booking with all fields needed for management.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminBookingDto {
    private Long id;
    private BookingStatus status;
    private String paymentStatus;
    
    // Car info
    private Long carId;
    private String carTitle;
    
    // Renter info
    private Long renterId;
    private String renterName;
    private String renterEmail;
    
    // Owner info
    private Long ownerId;
    private String ownerName;
    private String ownerEmail;
    
    // Trip details
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private BigDecimal totalPrice;
    private String insuranceType;
    
    // Timestamps
    private LocalDateTime createdAt;
    
    public static AdminBookingDto fromEntity(Booking b) {
        return AdminBookingDto.builder()
            .id(b.getId())
            .status(b.getStatus())
            .paymentStatus(b.getPaymentStatus())
            .carId(b.getCar() != null ? b.getCar().getId() : null)
            .carTitle(b.getCar() != null
                ? b.getCar().getBrand() + " " + b.getCar().getModel() + " (" + b.getCar().getYear() + ")"
                : "Unknown")
            .renterId(b.getRenter() != null ? b.getRenter().getId() : null)
            .renterName(b.getRenter() != null
                ? b.getRenter().getFirstName() + " " + b.getRenter().getLastName()
                : "Unknown")
            .renterEmail(b.getRenter() != null ? b.getRenter().getEmail() : null)
            .ownerId(b.getCar() != null && b.getCar().getOwner() != null ? b.getCar().getOwner().getId() : null)
            .ownerName(b.getCar() != null && b.getCar().getOwner() != null
                ? b.getCar().getOwner().getFirstName() + " " + b.getCar().getOwner().getLastName()
                : "Unknown")
            .ownerEmail(b.getCar() != null && b.getCar().getOwner() != null ? b.getCar().getOwner().getEmail() : null)
            .startTime(b.getStartTime())
            .endTime(b.getEndTime())
            .totalPrice(b.getTotalPrice())
            .insuranceType(b.getInsuranceType())
            .createdAt(b.getCreatedAt())
            .build();
    }
}

package org.example.rentoza.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.booking.Booking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * DTO for recent bookings displayed on admin dashboard.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecentBookingDto {
    private Long id;
    private String carTitle;
    private String renterName;
    private String ownerName;
    private String status;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal totalPrice;
    private LocalDateTime createdAt;

    /**
     * Convert Booking entity to RecentBookingDto.
     */
    public static RecentBookingDto fromEntity(Booking booking) {
        String carTitle = booking.getCar() != null 
            ? booking.getCar().getBrand() + " " + booking.getCar().getModel()
            : "Unknown Car";
            
        String renterName = booking.getRenter() != null
            ? booking.getRenter().getFirstName() + " " + booking.getRenter().getLastName()
            : "Unknown Renter";
            
        String ownerName = booking.getCar() != null && booking.getCar().getOwner() != null
            ? booking.getCar().getOwner().getFirstName() + " " + booking.getCar().getOwner().getLastName()
            : "Unknown Owner";

        return RecentBookingDto.builder()
            .id(booking.getId())
            .carTitle(carTitle)
            .renterName(renterName)
            .ownerName(ownerName)
            .status(booking.getStatus() != null ? booking.getStatus().name() : "UNKNOWN")
            .startDate(booking.getStartDate())
            .endDate(booking.getEndDate())
            .totalPrice(booking.getTotalPrice())
            .createdAt(booking.getCreatedAt())
            .build();
    }
}

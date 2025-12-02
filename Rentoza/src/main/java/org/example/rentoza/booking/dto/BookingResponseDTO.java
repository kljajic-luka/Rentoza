package org.example.rentoza.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for booking response.
 * 
 * <h2>Exact Timestamp Architecture</h2>
 * Returns precise start/end timestamps for frontend display.
 * Times are in Europe/Belgrade timezone.
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

    // Review flags
    private Boolean hasOwnerReview;

    // Car details
    private CarDetailsDTO car;

    // Renter details
    private RenterDetailsDTO renter;

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
    }

    public BookingResponseDTO(Booking booking) {
        this.id = booking.getId();
        this.startTime = booking.getStartTime();
        this.endTime = booking.getEndTime();
        this.totalPrice = booking.getTotalPrice();
        this.status = booking.getStatus();
        this.createdAt = booking.getCreatedAt() != null ? booking.getCreatedAt().toString() : null;

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

        // Map renter details
        if (booking.getRenter() != null) {
            this.renter = new RenterDetailsDTO(
                    booking.getRenter().getId(),
                    booking.getRenter().getFirstName(),
                    booking.getRenter().getLastName()
            );
        }
    }
}

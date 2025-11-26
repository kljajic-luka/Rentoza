package org.example.rentoza.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BookingResponseDTO {

    private Long id;
    private LocalDate startDate;
    private LocalDate endDate;
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
        private String brand;  // Changed from 'make' to 'brand' for consistency
        private String model;
        private Integer year;  // Added year field
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
        this.startDate = booking.getStartDate();
        this.endDate = booking.getEndDate();
        this.totalPrice = booking.getTotalPrice();
        this.status = booking.getStatus();
        this.createdAt = booking.getStartDate() != null ? booking.getStartDate().toString() : null;

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

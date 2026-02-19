package org.example.chatservice.dto.client;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BookingDetailsDTO {
    private Long id;
    private CarDetailsDTO car;
    private RenterDetailsDTO renter;
    private OwnerDetailsDTO owner;
    private LocalDate startDate;
    private LocalDate endDate;
    private boolean isFallback; // Marker to indicate this is a fallback DTO

    /**
     * Create a fallback DTO for when booking is not found
     */
    public static BookingDetailsDTO createFallback(String bookingId) {
        BookingDetailsDTO fallback = new BookingDetailsDTO();
        try {
            fallback.setId(Long.parseLong(bookingId));
        } catch (NumberFormatException e) {
            fallback.setId(0L);
        }
        fallback.setCar(new CarDetailsDTO(0L, "Unknown", "Unknown", 0));
        fallback.setRenter(new RenterDetailsDTO(0L, "Unknown", ""));
        fallback.setOwner(new OwnerDetailsDTO(0L, "Unknown", ""));
        fallback.setStartDate(LocalDate.now());
        fallback.setEndDate(LocalDate.now());
        fallback.setFallback(true);
        return fallback;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CarDetailsDTO {
        private Long id;
        private String brand;
        private String model;
        private Integer year;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RenterDetailsDTO {
        private Long id;
        private String firstName;
        private String lastName;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OwnerDetailsDTO {
        private Long id;
        private String firstName;
        private String lastName;
    }
}
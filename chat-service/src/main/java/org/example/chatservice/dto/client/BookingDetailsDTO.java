package org.example.chatservice.dto.client;

import lombok.Data;

import java.time.LocalDate;

@Data
public class BookingDetailsDTO {
    private Long id;
    private CarDetailsDTO car;
    private RenterDetailsDTO renter;
    private LocalDate startDate;
    private LocalDate endDate;

    @Data
    public static class CarDetailsDTO {
        private Long id;
        private String brand;
        private String model;
        private Integer year;
    }

    @Data
    public static class RenterDetailsDTO {
        private Long id;
        private String firstName;
        private String lastName;
    }
}

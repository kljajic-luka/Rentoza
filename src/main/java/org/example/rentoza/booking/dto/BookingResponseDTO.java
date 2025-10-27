package org.example.rentoza.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;

@Getter
@Setter
@AllArgsConstructor
public class BookingResponseDTO {
    private Long id;
    private Long carId;
    private String renterEmail;
    private LocalDate startDate;
    private LocalDate endDate;
    private Double totalPrice;
    private String status;
}
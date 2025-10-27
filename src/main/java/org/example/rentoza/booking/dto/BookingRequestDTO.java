package org.example.rentoza.booking.dto;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;

@Getter
@Setter
public class BookingRequestDTO {
    private Long carId;
    private LocalDate startDate;
    private LocalDate endDate;
}
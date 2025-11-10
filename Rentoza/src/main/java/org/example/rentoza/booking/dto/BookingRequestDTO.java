package org.example.rentoza.booking.dto;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;
import java.time.LocalTime;

@Getter
@Setter
public class BookingRequestDTO {
    private Long carId;
    private LocalDate startDate;
    private LocalDate endDate;
    private String insuranceType = "BASIC"; // BASIC, STANDARD, PREMIUM
    private boolean prepaidRefuel = false;
    
    // Phase 2.2: Pickup time support
    private String pickupTimeWindow = "MORNING"; // MORNING, AFTERNOON, EVENING, EXACT
    private LocalTime pickupTime; // Optional: only required when pickupTimeWindow is EXACT
}
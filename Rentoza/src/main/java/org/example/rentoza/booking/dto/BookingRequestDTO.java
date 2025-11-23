package org.example.rentoza.booking.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;
import java.time.LocalTime;

@Getter
@Setter
public class BookingRequestDTO {
    @NotNull(message = "Car ID is required")
    private Long carId;

    @NotNull(message = "Start date is required")
    @FutureOrPresent(message = "Start date must be today or in the future")
    private LocalDate startDate;

    @NotNull(message = "End date is required")
    @Future(message = "End date must be in the future")
    private LocalDate endDate;

    private String insuranceType = "BASIC"; // BASIC, STANDARD, PREMIUM
    private boolean prepaidRefuel = false;
    
    // Phase 2.2: Pickup time support
    private String pickupTimeWindow = "MORNING"; // MORNING, AFTERNOON, EVENING, EXACT
    private LocalTime pickupTime; // Optional: only required when pickupTimeWindow is EXACT

    @AssertTrue(message = "End date must be after start date")
    private boolean isEndDateAfterStartDate() {
        if (startDate == null || endDate == null) {
            return true; // Let @NotNull handle nulls
        }
        return endDate.isAfter(startDate);
    }
}
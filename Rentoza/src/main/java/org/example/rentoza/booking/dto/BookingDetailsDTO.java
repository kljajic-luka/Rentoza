package org.example.rentoza.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.booking.BookingStatus;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingDetailsDTO {
    // Trip
    private Long id;
    private BookingStatus status;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalTime pickupTime;
    private String pickupTimeWindow;
    private Double totalPrice;
    private String insuranceType;
    private boolean prepaidRefuel;
    private String cancellationPolicy;

    // Car
    private Long carId;
    private String brand;
    private String model;
    private Integer year;
    private String licensePlate; // Only returned to approved renters
    private String location;
    private String primaryImageUrl;
    
    // Car Details (Extra fields requested in prompt)
    private Integer seats;
    private String fuelType;
    private Double fuelConsumption;
    private String transmissionType;
    private Integer minRentalDays;
    private Integer maxRentalDays;

    // Host
    private Long hostId;
    private String hostName;
    private Double hostRating;
    private Integer hostTotalTrips;
    private String hostJoinedDate;
    private String hostAvatarUrl;
}

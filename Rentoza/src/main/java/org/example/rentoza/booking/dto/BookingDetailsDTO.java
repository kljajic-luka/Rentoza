package org.example.rentoza.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.booking.BookingStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for detailed booking information.
 * 
 * <h2>Exact Timestamp Architecture</h2>
 * Returns precise start/end timestamps for booking display.
 * Times are in Europe/Belgrade timezone.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingDetailsDTO {
    // Trip
    private Long id;
    private BookingStatus status;
    
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
    
    // Car Details
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

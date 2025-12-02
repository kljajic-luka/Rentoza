package org.example.rentoza.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for user's booking history display.
 * 
 * <h2>Exact Timestamp Architecture</h2>
 * Returns precise start/end timestamps for booking display.
 * Times are in Europe/Belgrade timezone.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserBookingResponseDTO {
    private Long id;
    private Long carId;
    private String carBrand;
    private String carModel;
    private Integer carYear;
    private String carImageUrl;
    private String carLocation;
    private BigDecimal carPricePerDay;
    
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
    private String status;

    // Review information
    private Boolean hasReview;
    private Integer reviewRating;
    private String reviewComment;
}

package org.example.rentoza.booking.extension.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.booking.extension.TripExtensionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * DTO for trip extension response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TripExtensionDTO {
    
    private Long id;
    private Long bookingId;
    
    // ========== DATES ==========
    
    private LocalDate originalEndDate;
    private LocalDate requestedEndDate;
    private Integer additionalDays;
    private String reason;
    
    // ========== PRICING ==========
    
    private BigDecimal dailyRate;
    private BigDecimal additionalCost;
    
    // ========== STATUS ==========
    
    private TripExtensionStatus status;
    private String statusDisplay;
    private LocalDateTime responseDeadline;
    private String hostResponse;
    private LocalDateTime respondedAt;
    
    // ========== TIMESTAMPS ==========
    
    private LocalDateTime createdAt;
    
    // ========== VEHICLE INFO ==========
    
    private String vehicleName;
    private String vehicleImageUrl;
    
    // ========== GUEST INFO (for host view) ==========
    
    private Long guestId;
    private String guestName;
}


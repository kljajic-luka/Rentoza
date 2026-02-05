package org.example.rentoza.booking.checkout.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for guest checkout submission.
 * 
 * <p>Submitted by guest when returning the vehicle with:
 * <ul>
 *   <li>End odometer reading</li>
 *   <li>End fuel level</li>
 *   <li>Optional comment about vehicle condition</li>
 *   <li>Guest's GPS location at checkout</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestCheckOutSubmissionDTO {
    
    @NotNull(message = "ID rezervacije je obavezan")
    private Long bookingId;
    
    @NotNull(message = "Završna kilometraža je obavezna")
    @Min(value = 0, message = "Kilometraža mora biti pozitivna")
    private Integer endOdometerReading;
    
    @NotNull(message = "Nivo goriva je obavezan")
    @Min(value = 0, message = "Nivo goriva mora biti 0-100%")
    @Max(value = 100, message = "Nivo goriva mora biti 0-100%")
    private Integer endFuelLevelPercent;
    
    /**
     * Optional comment about vehicle condition at return.
     */
    private String conditionComment;
    
    /**
     * Guest's GPS latitude at checkout.
     */
    private Double guestLatitude;
    
    /**
     * Guest's GPS longitude at checkout.
     */
    private Double guestLongitude;
}


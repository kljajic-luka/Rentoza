package org.example.rentoza.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.FuelType;
import org.example.rentoza.car.TransmissionType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * DTO for admin car list view.
 * Contains summary information for car management table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminCarDto {
    
    private Long id;
    private String brand;
    private String model;
    private Integer year;
    private BigDecimal pricePerDay;
    private String location;
    private String imageUrl;
    
    /** Owner info */
    private Long ownerId;
    private String ownerName;
    private String ownerEmail;
    
    /** Car specifications */
    private Integer seats;
    private FuelType fuelType;
    private TransmissionType transmissionType;
    
    /** Status */
    private boolean available;
    private String approvalStatus;
    private String rejectionReason;
    private Instant approvedAt;
    private Long approvedById;
    private String approvedByName;
    
    /** Timestamps */
    private Instant createdAt;
    private Instant updatedAt;
    
    /** Statistics */
    private Integer totalBookings;
    private Double averageRating;
    
    /**
     * Convert Car entity to summary DTO.
     */
    public static AdminCarDto fromEntity(Car car) {
        AdminCarDtoBuilder builder = AdminCarDto.builder()
            .id(car.getId())
            .brand(car.getBrand())
            .model(car.getModel())
            .year(car.getYear())
            .pricePerDay(car.getPricePerDay())
            .location(car.getEffectiveCity())
            .imageUrl(car.getImageUrl())
            .seats(car.getSeats())
            .fuelType(car.getFuelType())
            .transmissionType(car.getTransmissionType())
            .available(car.isAvailable())
            .createdAt(car.getCreatedAt())
            .updatedAt(car.getUpdatedAt())
            // Approval status fields
            .approvalStatus(car.getApprovalStatus() != null ? car.getApprovalStatus().name() : null)
            .rejectionReason(car.getRejectionReason())
            .approvedAt(car.getApprovedAt())
            .approvedById(car.getApprovedBy() != null ? car.getApprovedBy().getId() : null)
            .approvedByName(car.getApprovedBy() != null 
                ? car.getApprovedBy().getFirstName() + " " + car.getApprovedBy().getLastName() 
                : null);
        
        if (car.getOwner() != null) {
            builder.ownerId(car.getOwner().getId());
            builder.ownerName(car.getOwner().getFirstName() + " " + car.getOwner().getLastName());
            builder.ownerEmail(car.getOwner().getEmail());
        }
        
        return builder.build();
    }
    
    /**
     * Get display title (Brand Model Year).
     */
    public String getTitle() {
        return brand + " " + model + " (" + year + ")";
    }
}

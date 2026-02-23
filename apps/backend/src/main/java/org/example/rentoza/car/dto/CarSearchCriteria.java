package org.example.rentoza.car.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.car.Feature;
import org.example.rentoza.car.FuelType;
import org.example.rentoza.car.TransmissionType;

import java.util.List;

/**
 * DTO for car search criteria - all fields are optional
 * Used to build dynamic queries with JPA Specifications
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarSearchCriteria {

    // Price filtering
    private Double minPrice;
    private Double maxPrice;

    // Vehicle type/brand filtering
    private String vehicleType;  // For future use if we add a vehicleType field to Car
    private String make;          // Brand
    private String model;

    // Year filtering
    private Integer minYear;
    private Integer maxYear;

    // Location filtering
    private String location;

    // Seats filtering
    private Integer minSeats;

    // Transmission filtering
    private TransmissionType transmission;

    // Fuel type filtering
    private FuelType fuelType;

    // Features filtering (comma-separated or list)
    private List<Feature> features;

    /**
     * Free-text search query (canonical param: q, legacy alias: search).
     * OR-matched across brand, model, location, and description fields.
     * Accent-insensitive, case-insensitive contains match.
     */
    private String q;

    // Pagination and sorting
    private Integer page;
    private Integer size;
    private String sort;  // e.g., "price,asc" or "year,desc"

    /**
     * Normalize and validate criteria
     * - Clamp negative prices to zero
     * - Set default page/size values
     * - Validate sort field
     */
    public void normalize() {
        if (minPrice != null && minPrice < 0) {
            minPrice = 0.0;
        }
        if (maxPrice != null && maxPrice < 0) {
            maxPrice = 0.0;
        }
        if (minYear != null && minYear < 1900) {
            minYear = 1900;
        }
        if (maxYear != null && maxYear > 2100) {
            maxYear = 2100;
        }
        if (minSeats != null && minSeats < 1) {
            minSeats = 1;
        }
        if (page == null || page < 0) {
            page = 0;
        }
        if (size == null || size < 1) {
            size = 20;
        }
        // Cap size at 50 to prevent abuse
        if (size > 50) {
            size = 50;
        }
    }
}

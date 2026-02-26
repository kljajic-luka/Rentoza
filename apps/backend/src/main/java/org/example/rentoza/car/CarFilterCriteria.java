package org.example.rentoza.car;

import java.util.List;

/**
 * Shared interface for car filter criteria.
 *
 * Implemented by both {@link org.example.rentoza.car.dto.CarSearchCriteria} (DB-level search)
 * and {@link org.example.rentoza.car.dto.AvailabilitySearchRequestDTO} (in-memory availability search)
 * to ensure filter semantics are defined once and applied consistently across both paths.
 *
 * @see CarFilterEngine
 */
public interface CarFilterCriteria {

    Double getMinPrice();

    Double getMaxPrice();

    String getMake();

    String getModel();

    Integer getMinYear();

    Integer getMaxYear();

    Integer getMinSeats();

    TransmissionType getTransmission();

    List<Feature> getFeatures();

    String getVehicleType();

    /**
     * Resolved fuel type as the canonical enum value.
     * Implementations handle conversion from String (availability path)
     * or direct enum access (standard search path).
     *
     * @return FuelType enum, or null if not set / unrecognised
     */
    FuelType getResolvedFuelType();

    String getQ();
}

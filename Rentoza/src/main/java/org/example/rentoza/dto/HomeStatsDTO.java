package org.example.rentoza.dto;

public record HomeStatsDTO(
        double guestSatisfactionRating,
        long verifiedVehiclesCount,
        String supportAvailability
) {}

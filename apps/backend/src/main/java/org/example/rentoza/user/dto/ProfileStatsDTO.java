package org.example.rentoza.user.dto;

public record ProfileStatsDTO(
        long completedTrips,
        long hostedTrips,
        long totalReviews
) {}

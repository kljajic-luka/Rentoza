package org.example.rentoza.booking.checkin.dto;

import lombok.Builder;

import java.time.Instant;

@Builder
public record CheckInAttestationResponseDTO(
        Long bookingId,
        String checkInSessionId,
        String payloadHash,
        String artifactUrl,
        Instant createdAt
) {
}

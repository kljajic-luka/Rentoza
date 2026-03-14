package org.example.rentoza.booking.checkin;

public record CheckInAttestationRequestedEvent(Long bookingId, String checkInSessionId, Long actorId) {}
package org.example.rentoza.booking.dto;

import java.time.LocalDateTime;

public record AgreementSummaryDTO(
        String workflowStatus,
        boolean ownerAccepted,
        boolean renterAccepted,
        boolean currentActorNeedsAcceptance,
        boolean currentActorCanProceedToCheckIn,
        boolean legacyBooking,
        LocalDateTime acceptanceDeadlineAt,
        String urgencyLevel,
        String recommendedPrimaryAction
) {
}
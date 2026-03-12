package org.example.rentoza.booking.dto;

import org.example.rentoza.booking.RentalAgreement;
import org.example.rentoza.booking.RentalAgreementActor;
import org.example.rentoza.booking.RentalAgreementStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Response DTO for rental agreement data.
 */
public record RentalAgreementDTO(
        Long id,
        Long bookingId,
        String agreementVersion,
        String agreementType,
        String contentHash,
        Instant generatedAt,
        RentalAgreementStatus status,
        boolean ownerAccepted,
        Instant ownerAcceptedAt,
        boolean renterAccepted,
        Instant renterAcceptedAt,
        LocalDateTime acceptanceDeadlineAt,
        RentalAgreementActor requiredNextActor,
        RentalAgreementActor expiredDueToActor,
        String expiredReason,
        String settlementPolicyApplied,
        Long settlementRecordId,
        String termsTemplateId,
        String termsTemplateHash,
        boolean enforcementEnabled,
        Long ownerUserId,
        Long renterUserId,
        Map<String, Object> vehicleSnapshot,
        Map<String, Object> termsSnapshot
) {
    public static RentalAgreementDTO from(RentalAgreement agreement, boolean enforcementEnabled) {
        return new RentalAgreementDTO(
                agreement.getId(),
                agreement.getBookingId(),
                agreement.getAgreementVersion(),
                agreement.getAgreementType(),
                agreement.getContentHash(),
                agreement.getGeneratedAt(),
                agreement.getStatus(),
                agreement.getOwnerAcceptedAt() != null,
                agreement.getOwnerAcceptedAt(),
                agreement.getRenterAcceptedAt() != null,
                agreement.getRenterAcceptedAt(),
                agreement.getAcceptanceDeadlineAt(),
                agreement.getRequiredNextActor(),
                agreement.getExpiredDueToActor(),
                agreement.getExpiredReason(),
                agreement.getSettlementPolicyApplied(),
                agreement.getSettlementRecordId(),
                agreement.getTermsTemplateId(),
                agreement.getTermsTemplateHash(),
                enforcementEnabled,
                agreement.getOwnerUserId(),
                agreement.getRenterUserId(),
                agreement.getVehicleSnapshotJson(),
                agreement.getTermsSnapshotJson()
        );
    }
}

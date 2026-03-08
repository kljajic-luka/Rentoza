package org.example.rentoza.booking.dto;

import org.example.rentoza.booking.RentalAgreement;
import org.example.rentoza.booking.RentalAgreementStatus;

import java.time.Instant;
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
        String termsTemplateId,
        String termsTemplateHash,
        Long ownerUserId,
        Long renterUserId,
        Map<String, Object> vehicleSnapshot,
        Map<String, Object> termsSnapshot
) {
    public static RentalAgreementDTO from(RentalAgreement agreement) {
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
                agreement.getTermsTemplateId(),
                agreement.getTermsTemplateHash(),
                agreement.getOwnerUserId(),
                agreement.getRenterUserId(),
                agreement.getVehicleSnapshotJson(),
                agreement.getTermsSnapshotJson()
        );
    }
}

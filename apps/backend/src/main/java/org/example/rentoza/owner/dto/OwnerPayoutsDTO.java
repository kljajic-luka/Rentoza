package org.example.rentoza.owner.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.payment.PayoutLedger;
import org.example.rentoza.payment.PayoutLifecycleStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Response DTO for GET /api/owner/payouts.
 *
 * <p>Returns per-booking payout status information for the host dashboard,
 * including lifecycle state, fee breakdown, ETA, retry tracking, and errors.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OwnerPayoutsDTO {

    private List<BookingPayoutStatusDTO> payouts;

    /**
     * Per-booking payout status for the host dashboard.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BookingPayoutStatusDTO {
        private Long bookingId;
        private String carBrand;
        private String carModel;
        private String guestName;
        private String tripStartTime;
        private String tripEndTime;
        private PayoutLifecycleStatus payoutStatus;
        /** ISO-8601 datetime when payout becomes eligible (after dispute window). */
        private String eligibleAt;
        private BigDecimal tripAmount;
        private BigDecimal platformFee;
        private BigDecimal hostPayoutAmount;
        private int attemptCount;
        private int maxAttempts;
        /** ISO-8601 datetime for next retry attempt (if FAILED). */
        private String nextRetryAt;
        private String lastError;

        /**
         * Build a DTO from a PayoutLedger entity and associated booking/car data.
         */
        public static BookingPayoutStatusDTO fromLedger(
                PayoutLedger ledger,
                String carBrand,
                String carModel,
                String guestName,
                String tripStartTime,
                String tripEndTime
        ) {
            return BookingPayoutStatusDTO.builder()
                    .bookingId(ledger.getBookingId())
                    .carBrand(carBrand)
                    .carModel(carModel)
                    .guestName(guestName)
                    .tripStartTime(tripStartTime)
                    .tripEndTime(tripEndTime)
                    .payoutStatus(ledger.getStatus())
                    .eligibleAt(instantToString(ledger.getEligibleAt()))
                    .tripAmount(ledger.getTripAmount())
                    .platformFee(ledger.getPlatformFee())
                    .hostPayoutAmount(ledger.getHostPayoutAmount())
                    .attemptCount(ledger.getAttemptCount())
                    .maxAttempts(ledger.getMaxAttempts())
                    .nextRetryAt(instantToString(ledger.getNextRetryAt()))
                    .lastError(ledger.getLastError())
                    .build();
        }

        private static String instantToString(Instant instant) {
            return instant != null ? instant.toString() : null;
        }
    }
}

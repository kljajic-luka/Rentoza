package org.example.rentoza.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.user.OwnerType;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/**
 * Tax withholding calculation and monthly aggregation service.
 *
 * <p>Implements Serbian income tax withholding for individual owners:
 * <pre>
 *   grossOwnerIncome     = tripAmount - platformFee
 *   normalizedExpenses   = grossOwnerIncome × 0.20
 *   taxableBase          = grossOwnerIncome - normalizedExpenses
 *   incomeTaxWithheld    = taxableBase × 0.20
 *   netOwnerPayout       = grossOwnerIncome - incomeTaxWithheld
 * </pre>
 *
 * <p>Legal entities are exempt (incomeTaxWithheld = 0, net = gross).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TaxWithholdingService {

    private static final BigDecimal NORMALIZED_EXPENSES_RATE = new BigDecimal("0.2000");
    private static final BigDecimal INCOME_TAX_RATE = new BigDecimal("0.2000");
    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");

    private final PayoutLedgerRepository payoutLedgerRepository;
    private final TaxWithholdingSummaryRepository summaryRepository;
    private final UserRepository userRepository;

    /**
     * Calculate and apply tax withholding to a payout ledger entry.
     *
     * <p>Must be called before the payout transfer amount is finalized.
     * Idempotent: if withholding is already calculated, returns the existing ledger.
     *
     * @param ledger the payout ledger entry to calculate withholding for
     * @param owner  the owner user (needed for ownerType determination)
     * @return the updated ledger with withholding fields populated
     */
    public PayoutLedger calculateWithholding(PayoutLedger ledger, User owner) {
        // Already calculated — idempotent
        if (ledger.getGrossOwnerIncome() != null && ledger.getIncomeTaxWithheld() != null) {
            return ledger;
        }

        BigDecimal grossOwnerIncome = ledger.getTripAmount().subtract(ledger.getPlatformFee());
        ledger.setGrossOwnerIncome(grossOwnerIncome);

        OwnerType ownerType = owner.getOwnerType();
        if (ownerType == null) {
            throw new IllegalStateException(
                    "Owner type must be set before tax withholding can be calculated. Owner user ID: "
                    + owner.getId());
        }
        ledger.setOwnerTaxType(ownerType.name());

        if (ownerType == OwnerType.LEGAL_ENTITY) {
            // Legal entities are exempt from income tax withholding
            ledger.setNormalizedExpensesRate(BigDecimal.ZERO);
            ledger.setTaxableBase(BigDecimal.ZERO);
            ledger.setIncomeTaxRate(BigDecimal.ZERO);
            ledger.setIncomeTaxWithheld(BigDecimal.ZERO);
            ledger.setNetOwnerPayout(grossOwnerIncome);
            ledger.setTaxWithholdingStatus("EXEMPT");
        } else {
            // Individual: apply normalized expenses and income tax
            BigDecimal normalizedExpenses = grossOwnerIncome
                    .multiply(NORMALIZED_EXPENSES_RATE)
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal taxableBase = grossOwnerIncome.subtract(normalizedExpenses)
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal incomeTaxWithheld = taxableBase
                    .multiply(INCOME_TAX_RATE)
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal netOwnerPayout = grossOwnerIncome.subtract(incomeTaxWithheld)
                    .setScale(2, RoundingMode.HALF_UP);

            ledger.setNormalizedExpensesRate(NORMALIZED_EXPENSES_RATE);
            ledger.setTaxableBase(taxableBase);
            ledger.setIncomeTaxRate(INCOME_TAX_RATE);
            ledger.setIncomeTaxWithheld(incomeTaxWithheld);
            ledger.setNetOwnerPayout(netOwnerPayout);
            ledger.setTaxWithholdingStatus("CALCULATED");
        }

        log.debug("[Tax] Withholding calculated for payout {}: gross={}, tax={}, net={}, type={}",
                ledger.getId(), ledger.getGrossOwnerIncome(),
                ledger.getIncomeTaxWithheld(), ledger.getNetOwnerPayout(),
                ledger.getOwnerTaxType());

        return ledger;
    }

    /**
     * Generate or update the monthly summary for a given owner and period.
     *
     * <p>Aggregates all payouts for the owner in the given month.
     *
     * @param ownerUserId the owner user ID
     * @param year tax period year
     * @param month tax period month (1-12)
     * @return the generated/updated summary
     */
    @Transactional
    public TaxWithholdingSummary generateMonthlyStatement(Long ownerUserId, int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        Instant periodStart = ym.atDay(1).atStartOfDay(SERBIA_ZONE).toInstant();
        Instant periodEnd = ym.plusMonths(1).atDay(1).atStartOfDay(SERBIA_ZONE).toInstant();

        List<PayoutLedger> payouts = payoutLedgerRepository
                .findByHostUserIdAndPaidAtBetween(ownerUserId, periodStart, periodEnd);

        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalExpenses = BigDecimal.ZERO;
        BigDecimal totalTaxBase = BigDecimal.ZERO;
        BigDecimal totalTax = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;
        int count = 0;

        for (PayoutLedger p : payouts) {
            if (p.getGrossOwnerIncome() != null) {
                totalGross = totalGross.add(p.getGrossOwnerIncome());
                BigDecimal expenses = p.getGrossOwnerIncome()
                        .multiply(p.getNormalizedExpensesRate() != null ? p.getNormalizedExpensesRate() : BigDecimal.ZERO)
                        .setScale(2, RoundingMode.HALF_UP);
                totalExpenses = totalExpenses.add(expenses);
                totalTaxBase = totalTaxBase.add(
                        p.getTaxableBase() != null ? p.getTaxableBase() : BigDecimal.ZERO);
                totalTax = totalTax.add(
                        p.getIncomeTaxWithheld() != null ? p.getIncomeTaxWithheld() : BigDecimal.ZERO);
                totalNet = totalNet.add(
                        p.getNetOwnerPayout() != null ? p.getNetOwnerPayout() : BigDecimal.ZERO);
                count++;
            }
        }

        TaxWithholdingSummary summary = summaryRepository
                .findByOwnerUserIdAndTaxPeriodYearAndTaxPeriodMonth(ownerUserId, year, month)
                .orElse(TaxWithholdingSummary.builder()
                        .ownerUserId(ownerUserId)
                        .taxPeriodYear(year)
                        .taxPeriodMonth(month)
                        .build());

        summary.setTotalGrossIncome(totalGross);
        summary.setTotalNormalizedExpenses(totalExpenses);
        summary.setTotalTaxableBase(totalTaxBase);
        summary.setTotalTaxWithheld(totalTax);
        summary.setTotalNetPaid(totalNet);
        summary.setPayoutCount(count);

        return summaryRepository.save(summary);
    }

    /**
     * Aggregate all individual owner withholdings for PPPPD filing for a given month.
     * Only includes individual owners (legal entities handle their own taxes).
     *
     * @param year tax period year
     * @param month tax period month (1-12)
     * @return list of summaries for individual owners with payouts in the period
     */
    @Transactional
    public List<TaxWithholdingSummary> aggregateForPPPPD(int year, int month) {
        // Get all unique individual owner IDs with payouts in this period
        YearMonth ym = YearMonth.of(year, month);
        Instant periodStart = ym.atDay(1).atStartOfDay(SERBIA_ZONE).toInstant();
        Instant periodEnd = ym.plusMonths(1).atDay(1).atStartOfDay(SERBIA_ZONE).toInstant();

        List<Long> ownerIds = payoutLedgerRepository
                .findDistinctIndividualHostUserIdsByPaidAtBetween(periodStart, periodEnd);

        log.info("[Tax] Aggregating PPPPD for {}-{}: {} individual owners with payouts", year, month, ownerIds.size());

        return ownerIds.stream()
                .map(ownerId -> generateMonthlyStatement(ownerId, year, month))
                .toList();
    }

    @Transactional
    public TaxWithholdingSummary markFiled(Long summaryId, String reference) {
        TaxWithholdingSummary summary = summaryRepository.findById(summaryId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tax summary not found: " + summaryId));

        if (summary.isPpppdFiled()) {
            return summary; // Idempotent
        }

        summary.setPpppdFiled(true);
        summary.setPpppdFilingDate(LocalDate.now());
        if (reference != null && !reference.isBlank()) {
            summary.setPpppdReference(reference.trim());
        }

        TaxWithholdingSummary saved = summaryRepository.save(summary);
        log.info("[Tax] Monthly summary {} marked as PPPPD-filed (ref: {})", summaryId, reference);
        return saved;
    }
}

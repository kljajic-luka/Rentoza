package org.example.rentoza.payment;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Monthly tax withholding aggregation per owner.
 *
 * <p>Used for PPPPD (Serbian individual income tax form) filing tracking.
 * One row per owner per month captures aggregate withholding data.
 */
@Entity
@Table(
    name = "tax_withholding_summary",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_tax_summary_owner_period",
        columnNames = {"owner_user_id", "tax_period_year", "tax_period_month"}
    ),
    indexes = {
        @Index(name = "idx_tws_owner", columnList = "owner_user_id"),
        @Index(name = "idx_tws_period", columnList = "tax_period_year, tax_period_month")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaxWithholdingSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "tax_period_year", nullable = false)
    private int taxPeriodYear;

    @Column(name = "tax_period_month", nullable = false)
    private int taxPeriodMonth;

    @Column(name = "total_gross_income", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal totalGrossIncome = BigDecimal.ZERO;

    @Column(name = "total_normalized_expenses", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal totalNormalizedExpenses = BigDecimal.ZERO;

    @Column(name = "total_taxable_base", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal totalTaxableBase = BigDecimal.ZERO;

    @Column(name = "total_tax_withheld", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal totalTaxWithheld = BigDecimal.ZERO;

    @Column(name = "total_net_paid", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal totalNetPaid = BigDecimal.ZERO;

    @Column(name = "payout_count", nullable = false)
    @Builder.Default
    private int payoutCount = 0;

    @Column(name = "ppppd_filed", nullable = false)
    @Builder.Default
    private boolean ppppdFiled = false;

    @Column(name = "ppppd_filing_date")
    private LocalDate ppppdFilingDate;

    @Column(name = "ppppd_reference", length = 100)
    private String ppppdReference;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}

package org.example.rentoza.admin.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.payment.TaxWithholdingService;
import org.example.rentoza.payment.TaxWithholdingSummary;
import org.example.rentoza.payment.TaxWithholdingSummaryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Admin Tax Withholding Management Controller.
 *
 * <p>Provides endpoints for:
 * <ul>
 *   <li>Monthly tax withholding summary generation and viewing</li>
 *   <li>Per-owner annual tax statements</li>
 *   <li>PPPPD filing status tracking</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/tax")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminTaxController {

    private final TaxWithholdingService taxWithholdingService;
    private final TaxWithholdingSummaryRepository summaryRepository;

    /**
     * Aggregate (create/update) monthly tax withholding summaries for all owners.
     * This is a mutating operation — use POST.
     */
    @PostMapping("/monthly-summary/aggregate")
    public ResponseEntity<List<TaxWithholdingSummary>> aggregateMonthlySummary(
            @RequestParam @Min(2024) int year,
            @RequestParam @Min(1) @Max(12) int month) {

        log.info("[AdminTax] Aggregating monthly summary for {}-{}", year, month);
        List<TaxWithholdingSummary> summaries = taxWithholdingService.aggregateForPPPPD(year, month);
        return ResponseEntity.ok(summaries);
    }

    /**
     * Read existing monthly tax withholding summaries (read-only).
     */
    @GetMapping("/monthly-summary")
    public ResponseEntity<List<TaxWithholdingSummary>> getMonthlySummary(
            @RequestParam @Min(2024) int year,
            @RequestParam @Min(1) @Max(12) int month) {

        List<TaxWithholdingSummary> summaries = summaryRepository
                .findByTaxPeriodYearAndTaxPeriodMonth(year, month);
        return ResponseEntity.ok(summaries);
    }

    /**
     * Get annual tax statements for a specific owner.
     */
    @GetMapping("/owner/{userId}/statements")
    public ResponseEntity<List<TaxWithholdingSummary>> getOwnerStatements(
            @PathVariable Long userId,
            @RequestParam @Min(2024) int year) {

        List<TaxWithholdingSummary> statements = summaryRepository
                .findByOwnerUserIdAndTaxPeriodYear(userId, year);
        return ResponseEntity.ok(statements);
    }

    /**
     * Mark a monthly summary as PPPPD-filed.
     */
    @PostMapping("/monthly-summary/{id}/mark-filed")
    public ResponseEntity<TaxWithholdingSummary> markFiled(
            @PathVariable Long id,
            @RequestParam(required = false) String reference) {

        TaxWithholdingSummary saved = taxWithholdingService.markFiled(id, reference);
        return ResponseEntity.ok(saved);
    }
}

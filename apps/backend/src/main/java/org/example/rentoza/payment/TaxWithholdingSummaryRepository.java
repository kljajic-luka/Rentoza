package org.example.rentoza.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaxWithholdingSummaryRepository extends JpaRepository<TaxWithholdingSummary, Long> {

    Optional<TaxWithholdingSummary> findByOwnerUserIdAndTaxPeriodYearAndTaxPeriodMonth(
            Long ownerUserId, int year, int month);

    List<TaxWithholdingSummary> findByTaxPeriodYearAndTaxPeriodMonth(int year, int month);

    List<TaxWithholdingSummary> findByOwnerUserIdAndTaxPeriodYear(Long ownerUserId, int year);

    @Query("SELECT t FROM TaxWithholdingSummary t WHERE t.taxPeriodYear = :year AND t.taxPeriodMonth = :month AND t.ppppdFiled = false")
    List<TaxWithholdingSummary> findUnfiledForPeriod(@Param("year") int year, @Param("month") int month);
}

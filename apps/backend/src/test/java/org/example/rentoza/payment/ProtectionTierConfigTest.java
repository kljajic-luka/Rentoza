package org.example.rentoza.payment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ProtectionTierConfigTest {

    // --- fromString ---

    @ParameterizedTest
    @CsvSource({
            "BASIC,   BASIC",
            "basic,   BASIC",
            "STANDARD, STANDARD",
            "standard, STANDARD",
            "Standard, STANDARD",
            "PREMIUM,  PREMIUM",
            "premium,  PREMIUM"
    })
    void fromString_resolvesTier(String input, ProtectionTierConfig expected) {
        assertThat(ProtectionTierConfig.fromString(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"UNKNOWN", "foo", " "})
    void fromString_defaultsToBasic(String input) {
        assertThat(ProtectionTierConfig.fromString(input)).isEqualTo(ProtectionTierConfig.BASIC);
    }

    // --- priceMultiplier ---

    @Test
    void basicPriceMultiplier() {
        assertThat(ProtectionTierConfig.BASIC.getPriceMultiplier())
                .isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void standardPriceMultiplier() {
        assertThat(ProtectionTierConfig.STANDARD.getPriceMultiplier())
                .isEqualByComparingTo(new BigDecimal("1.10"));
    }

    @Test
    void premiumPriceMultiplier() {
        assertThat(ProtectionTierConfig.PREMIUM.getPriceMultiplier())
                .isEqualByComparingTo(new BigDecimal("1.20"));
    }

    // --- calculateEffectiveDeposit ---

    @Test
    void basicDeposit_fullAmount() {
        BigDecimal listingDeposit = new BigDecimal("30000");
        assertThat(ProtectionTierConfig.BASIC.calculateEffectiveDeposit(listingDeposit))
                .isEqualByComparingTo(new BigDecimal("30000.00"));
    }

    @Test
    void standardDeposit_halfAmount() {
        BigDecimal listingDeposit = new BigDecimal("30000");
        assertThat(ProtectionTierConfig.STANDARD.calculateEffectiveDeposit(listingDeposit))
                .isEqualByComparingTo(new BigDecimal("15000.00"));
    }

    @Test
    void premiumDeposit_zeroAmount() {
        BigDecimal listingDeposit = new BigDecimal("30000");
        assertThat(ProtectionTierConfig.PREMIUM.calculateEffectiveDeposit(listingDeposit))
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void calculateEffectiveDeposit_nullInput_returnsZero() {
        assertThat(ProtectionTierConfig.STANDARD.calculateEffectiveDeposit(null))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void calculateEffectiveDeposit_oddAmount_roundsCorrectly() {
        BigDecimal listingDeposit = new BigDecimal("33333");
        BigDecimal result = ProtectionTierConfig.STANDARD.calculateEffectiveDeposit(listingDeposit);
        assertThat(result).isEqualByComparingTo(new BigDecimal("16666.50"));
    }

    // --- maxPlatformCoverage ---

    @Test
    void basicCoverage_zero() {
        assertThat(ProtectionTierConfig.BASIC.getMaxPlatformCoverage())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void standardCoverage() {
        assertThat(ProtectionTierConfig.STANDARD.getMaxPlatformCoverage())
                .isEqualByComparingTo(new BigDecimal("150000"));
    }

    @Test
    void premiumCoverage() {
        assertThat(ProtectionTierConfig.PREMIUM.getMaxPlatformCoverage())
                .isEqualByComparingTo(new BigDecimal("300000"));
    }
}

package org.example.rentoza.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Konfiguracija nivoa zastite (protection tier).
 * Odredjuje odnos depozita i pokrica platforme po tieru.
 *
 * <p>Ovo NIJE polisa osiguranja. Zastita Rentoza je ugovorna
 * garancija platforme u skladu sa Zakonom o obligacionim odnosima.
 */
public enum ProtectionTierConfig {

    BASIC(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO),
    STANDARD(new BigDecimal("1.10"), new BigDecimal("0.50"), new BigDecimal("150000")),
    PREMIUM(new BigDecimal("1.20"), BigDecimal.ZERO, new BigDecimal("300000"));

    private final BigDecimal priceMultiplier;
    private final BigDecimal depositMultiplier;  // Procenat listing depozita
    private final BigDecimal maxPlatformCoverage; // Max koji platforma pokriva iznad depozita

    ProtectionTierConfig(BigDecimal priceMultiplier,
                         BigDecimal depositMultiplier,
                         BigDecimal maxPlatformCoverage) {
        this.priceMultiplier = priceMultiplier;
        this.depositMultiplier = depositMultiplier;
        this.maxPlatformCoverage = maxPlatformCoverage;
    }

    public static ProtectionTierConfig fromString(String tier) {
        if (tier == null) return BASIC;
        return switch (tier.toUpperCase()) {
            case "STANDARD" -> STANDARD;
            case "PREMIUM" -> PREMIUM;
            default -> BASIC;
        };
    }

    public BigDecimal getPriceMultiplier() { return priceMultiplier; }
    public BigDecimal getDepositMultiplier() { return depositMultiplier; }
    public BigDecimal getMaxPlatformCoverage() { return maxPlatformCoverage; }

    /**
     * Racuna efektivni depozit na osnovu listing depozita i tier-a.
     */
    public BigDecimal calculateEffectiveDeposit(BigDecimal listingDeposit) {
        if (listingDeposit == null) return BigDecimal.ZERO;
        return listingDeposit.multiply(depositMultiplier).setScale(2, RoundingMode.HALF_UP);
    }
}

package org.example.rentoza.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for escrow account balance summary.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EscrowBalanceDto {
    
    private BigDecimal totalEscrowBalance;
    private BigDecimal pendingPayouts;
    private BigDecimal availableBalance;
    private BigDecimal frozenFunds; // Disputed amounts
    private Long activeBookingsCount;
    private Long pendingPayoutsCount;
    private String currency;
}

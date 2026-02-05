package org.example.rentoza.admin.dto.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum DisputeSeverity {
    LOW(0, 10000),           // $0-$100
    MEDIUM(10000, 50000),    // $100-$500
    HIGH(50000, 200000),     // $500-$2000
    CRITICAL(200000, Long.MAX_VALUE); // $2000+
    
    private final long minCostCents;
    private final long maxCostCents;
}

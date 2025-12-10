package org.example.rentoza.admin.dto.enums;

public enum DisputeDecision {
    APPROVED,     // Guest wins, host pays (or insurance)
    REJECTED,     // Host wins, guest pays nothing
    PARTIAL,      // Split the cost
    MEDIATED      // Both parties agreed on amount
}

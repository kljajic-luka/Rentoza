package org.example.rentoza.user.gdpr;

import lombok.Data;
import java.time.LocalDate;

/**
 * Response DTO for account deletion request.
 */
@Data
public class AccountDeletionResultDTO {
    private String message;
    private LocalDate deletionDate;
    private int gracePeriodDays;
    private boolean canCancel;
}

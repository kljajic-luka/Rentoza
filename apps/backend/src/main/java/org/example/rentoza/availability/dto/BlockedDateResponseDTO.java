package org.example.rentoza.availability.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Response DTO for blocked date information.
 * Contains minimal data needed for calendar display and management.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlockedDateResponseDTO {

    private Long id;
    private Long carId;
    private LocalDate startDate;
    private LocalDate endDate;
    private Instant createdAt;
}

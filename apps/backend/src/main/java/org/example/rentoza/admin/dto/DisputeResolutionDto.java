package org.example.rentoza.admin.dto;

import lombok.Builder;
import lombok.Data;
import org.example.rentoza.admin.dto.enums.DisputeDecision;
import org.example.rentoza.admin.entity.DisputeResolution;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
public class DisputeResolutionDto {
    private DisputeDecision decision;
    private String decisionNotes;
    private BigDecimal approvedAmount;
    private String rejectionReason;
    private Instant resolvedAt;
    private String adminName;

    public static DisputeResolutionDto fromEntity(DisputeResolution entity) {
        return DisputeResolutionDto.builder()
            .decision(entity.getDecision())
            .decisionNotes(entity.getDecisionNotes())
            .approvedAmount(entity.getApprovedAmount())
            .rejectionReason(entity.getRejectionReason())
            .resolvedAt(entity.getResolvedAt())
            .adminName(entity.getAdmin().getFirstName() + " " + entity.getAdmin().getLastName())
            .build();
    }
}

package org.example.rentoza.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.ResourceType;

import java.time.Instant;

/**
 * Request DTO for searching audit logs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogSearchRequest {
    
    private Long adminId;
    private ResourceType resourceType;
    private Long resourceId;
    private AdminAction action;
    private Instant startDate;
    private Instant endDate;
    private String searchTerm; // Search in notes/before/after
    private Integer page;
    private Integer size;
}

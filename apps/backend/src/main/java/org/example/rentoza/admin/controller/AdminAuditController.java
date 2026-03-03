package org.example.rentoza.admin.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.AdminAuditLogDto;
import org.example.rentoza.admin.dto.AuditLogSearchRequest;
import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.AdminAuditLog;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.admin.repository.AdminAuditLogRepository;
import org.example.rentoza.admin.service.AdminAuditService;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.criteria.Predicate;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Admin Audit Controller for immutable audit trail access.
 * 
 * <p><b>ENDPOINTS:</b>
 * <ul>
 *   <li>GET /api/admin/audit - Search and filter audit logs</li>
 *   <li>GET /api/admin/audit/{id} - Get single audit log entry</li>
 *   <li>GET /api/admin/audit/resource/{type}/{id} - Get logs for specific resource</li>
 *   <li>GET /api/admin/audit/export - Export audit logs as CSV</li>
 * </ul>
 * 
 * <p><b>SECURITY:</b>
 * <ul>
 *   <li>All endpoints require ROLE_ADMIN</li>
 *   <li>Audit logs are immutable (read-only via this controller)</li>
 *   <li>Exports are limited to 10,000 records</li>
 * </ul>
 * 
 * <p><b>PERFORMANCE:</b>
 * <ul>
 *   <li>Pagination required (max 100 per page)</li>
 *   <li>Indexed queries on timestamp, adminId, resourceType, action</li>
 *   <li>Full-text search limited to notes/before/after fields</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminAuditController {
    
    private final AdminAuditLogRepository auditRepo;
    private final CurrentUser currentUser;
    private final AdminAuditService auditService;
    private final UserRepository userRepo;
    

    private static final int MAX_EXPORT_SIZE = 10000;
    
    /**
     * Search audit logs with filters.
     * 
     * <p><b>Query Parameters:</b>
     * <ul>
     *   <li>adminId - Filter by admin user ID</li>
     *   <li>resourceType - Filter by resource type (USER, CAR, BOOKING, DISPUTE)</li>
     *   <li>resourceId - Filter by resource ID</li>
     *   <li>action - Filter by admin action (USER_BANNED, CAR_APPROVED, etc.)</li>
     *   <li>startDate - Filter by timestamp >= startDate (ISO-8601)</li>
     *   <li>endDate - Filter by timestamp <= endDate (ISO-8601)</li>
     *   <li>searchTerm - Full-text search in notes/before/after</li>
     *   <li>page - Page number (0-indexed, default: 0)</li>
     *   <li>size - Page size (default: 20, max: 100)</li>
     * </ul>
     * 
     * @return Paginated audit log entries
     */
    @GetMapping
    public ResponseEntity<Page<AdminAuditLogDto>> searchAuditLogs(
            @RequestParam(required = false) Long adminId,
            @RequestParam(required = false) ResourceType resourceType,
            @RequestParam(required = false) Long resourceId,
            @RequestParam(required = false) AdminAction action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate,
            @RequestParam(required = false) String searchTerm,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        log.debug("Admin {} searching audit logs: resourceType={}, action={}, page={}",
                  currentUser.id(), resourceType, action, page);
        
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        
        // Build dynamic specification
        Specification<AdminAuditLog> spec = buildSpecification(
            adminId, resourceType, resourceId, action, startDate, endDate, searchTerm
        );
        
        Page<AdminAuditLog> auditLogs = auditRepo.findAll(spec, pageable);
        
        Page<AdminAuditLogDto> dtos = auditLogs.map(this::toDto);
        
        log.debug("Found {} audit log entries", auditLogs.getTotalElements());
        
        return ResponseEntity.ok(dtos);
    }
    
    /**
     * Get single audit log entry by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<AdminAuditLogDto> getAuditLogById(@PathVariable @Positive Long id) {
        log.debug("Admin {} requesting audit log {}", currentUser.id(), id);
        
        return auditRepo.findById(id)
            .map(this::toDto)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
    
    /**
     * Get all audit logs for a specific resource.
     * 
     * <p>Example: GET /api/admin/audit/resource/USER/123
     * Returns all admin actions performed on user 123.
     */
    @GetMapping("/resource/{type}/{id}")
    public ResponseEntity<Page<AdminAuditLogDto>> getAuditLogsForResource(
            @PathVariable ResourceType type,
            @PathVariable @Positive Long id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        log.debug("Admin {} requesting audit logs for {} {}", currentUser.id(), type, id);
        
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        
        Page<AdminAuditLog> auditLogs = auditRepo.findByResourceTypeAndResourceId(type, id, pageable);
        
        Page<AdminAuditLogDto> dtos = auditLogs.map(this::toDto);
        
        return ResponseEntity.ok(dtos);
    }
    
    /**
     * Export audit logs as CSV.
     * 
     * <p>Accepts same query parameters as search endpoint.
     * Limited to MAX_EXPORT_SIZE (10,000) records for performance.
     * 
     * @return CSV file download
     */
    @GetMapping("/export")
    public ResponseEntity<String> exportAuditLogs(
            @RequestParam(required = false) Long adminId,
            @RequestParam(required = false) ResourceType resourceType,
            @RequestParam(required = false) Long resourceId,
            @RequestParam(required = false) AdminAction action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate,
            @RequestParam(required = false) String searchTerm) {
        
        log.info("Admin {} exporting audit logs: resourceType={}, action={}", 
                 currentUser.id(), resourceType, action);
        
        Pageable pageable = PageRequest.of(0, MAX_EXPORT_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"));
        
        Specification<AdminAuditLog> spec = buildSpecification(
            adminId, resourceType, resourceId, action, startDate, endDate, searchTerm
        );
        
        Page<AdminAuditLog> auditLogs = auditRepo.findAll(spec, pageable);
        
        String csv = generateCsv(auditLogs.getContent());
        
        // Log the export action to audit trail
        userRepo.findById(currentUser.id()).ifPresent(admin ->
            auditService.logAction(admin, AdminAction.AUDIT_EXPORTED, ResourceType.CONFIG, null, null,
                auditLogs.getTotalElements() + " audit records exported", "Audit log CSV export")
        );
        
        String filename = "audit_logs_" + Instant.now().toEpochMilli() + ".csv";
        
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(csv);
    }
    
    /**
     * Get audit log statistics.
     * 
     * <p>Returns counts grouped by action type, resource type, and admin.
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getAuditStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate) {
        
        log.debug("Admin {} requesting audit stats", currentUser.id());
        
        // Convert Instant to LocalDateTime for entity field compatibility
        LocalDateTime start = startDate != null 
            ? LocalDateTime.ofInstant(startDate, java.time.ZoneId.systemDefault())
            : LocalDateTime.now().minusDays(30);
        LocalDateTime end = endDate != null 
            ? LocalDateTime.ofInstant(endDate, java.time.ZoneId.systemDefault())
            : LocalDateTime.now();
        
        List<AdminAuditLog> logs = auditRepo.findByCreatedAtBetween(start, end);
        
        // Group by action
        var byAction = logs.stream()
            .collect(Collectors.groupingBy(AdminAuditLog::getAction, Collectors.counting()));
        
        // Group by resource type
        var byResourceType = logs.stream()
            .collect(Collectors.groupingBy(AdminAuditLog::getResourceType, Collectors.counting()));
        
        // Group by admin
        var byAdmin = logs.stream()
            .collect(Collectors.groupingBy(
                log -> log.getAdmin().getId(),
                Collectors.counting()
            ));
        
        return ResponseEntity.ok(java.util.Map.of(
            "byAction", byAction,
            "byResourceType", byResourceType,
            "byAdmin", byAdmin,
            "totalActions", logs.size(),
            "periodStart", start,
            "periodEnd", end
        ));
    }
    
    // ==================== HELPER METHODS ====================
    
    private Specification<AdminAuditLog> buildSpecification(
            Long adminId, ResourceType resourceType, Long resourceId,
            AdminAction action, Instant startDate, Instant endDate, String searchTerm) {
        
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            
            if (adminId != null) {
                predicates.add(cb.equal(root.get("admin").get("id"), adminId));
            }
            
            if (resourceType != null) {
                predicates.add(cb.equal(root.get("resourceType"), resourceType));
            }
            
            if (resourceId != null) {
                predicates.add(cb.equal(root.get("resourceId"), resourceId));
            }
            
            if (action != null) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            
            if (startDate != null) {
                LocalDateTime startLocal = LocalDateTime.ofInstant(startDate, java.time.ZoneId.systemDefault());
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startLocal));
            }
            
            if (endDate != null) {
                LocalDateTime endLocal = LocalDateTime.ofInstant(endDate, java.time.ZoneId.systemDefault());
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), endLocal));
            }
            
            if (searchTerm != null && !searchTerm.isBlank()) {
                String pattern = "%" + escapeLikePattern(searchTerm.toLowerCase()) + "%";
                Predicate reasonMatch = cb.like(cb.lower(root.get("reason")), pattern);
                Predicate beforeMatch = cb.like(cb.lower(root.get("beforeState")), pattern);
                Predicate afterMatch = cb.like(cb.lower(root.get("afterState")), pattern);
                predicates.add(cb.or(reasonMatch, beforeMatch, afterMatch));
            }
            
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
    
    private AdminAuditLogDto toDto(AdminAuditLog log) {
        return AdminAuditLogDto.builder()
            .id(log.getId())
            .adminId(log.getAdmin().getId())
            .admin(log.getAdmin().getFirstName() + " " + log.getAdmin().getLastName())
            .action(log.getAction())
            .resourceType(log.getResourceType())
            .resourceId(log.getResourceId())
            .beforeState(log.getBeforeState())
            .afterState(log.getAfterState())
            .reason(log.getReason())
            .createdAt(log.getCreatedAt())
            .ipAddress(log.getIpAddress())
            .userAgent(log.getUserAgent())
            .build();
    }
    
    private String generateCsv(List<AdminAuditLog> logs) {
        StringBuilder csv = new StringBuilder();
        
        // Header
        csv.append("ID,Timestamp,Admin ID,Admin Name,Action,Resource Type,Resource ID,Reason\n");
        
        // Rows
        for (AdminAuditLog log : logs) {
            csv.append(log.getId()).append(",");
            csv.append(log.getCreatedAt()).append(",");
            csv.append(log.getAdmin().getId()).append(",");
            csv.append(escapeCsv(log.getAdmin().getFirstName() + " " + log.getAdmin().getLastName())).append(",");
            csv.append(log.getAction()).append(",");
            csv.append(log.getResourceType()).append(",");
            csv.append(log.getResourceId()).append(",");
            csv.append(escapeCsv(log.getReason())).append("\n");
        }
        
        return csv.toString();
    }
    
    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String escapeLikePattern(String input) {
        return input.replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
    }
}

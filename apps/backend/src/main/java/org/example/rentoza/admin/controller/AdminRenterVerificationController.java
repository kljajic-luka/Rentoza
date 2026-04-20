package org.example.rentoza.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.admin.service.AdminAuditService;
import org.example.rentoza.car.DocumentVerificationStatus;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.storage.SupabaseStorageService;
import org.example.rentoza.user.DriverLicenseStatus;
import org.example.rentoza.user.RenterVerificationService;
import org.example.rentoza.user.RiskLevel;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.example.rentoza.user.document.RenterDocument;
import org.example.rentoza.user.document.RenterDocumentRepository;
import org.example.rentoza.user.document.RenterVerificationAudit;
import org.example.rentoza.user.document.RenterVerificationAuditRepository;
import org.example.rentoza.user.dto.RenterDocumentDTO;
import org.example.rentoza.user.dto.RenterVerificationProfileDTO;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Admin controller for renter verification management.
 * 
 * <p>Endpoints for admins to:
 * <ul>
 *   <li>View pending verification queue</li>
 *   <li>Review documents with OCR/biometric results</li>
 *   <li>Approve or reject verifications</li>
 *   <li>Suspend users for fraud</li>
 *   <li>View verification analytics</li>
 * </ul>
 * 
 * <p>Queue/detail access is limited to ADMIN or IDENTITY_REVIEWER.
 * Explicit document reveal/download grants are audited immutably.
 */
@RestController
@RequestMapping("/api/admin/renter-verifications")
@Tag(name = "Admin - Renter Verification", description = "Admin management of renter license verification")
@PreAuthorize("hasAnyRole('ADMIN', 'IDENTITY_REVIEWER')")
@Validated
@Slf4j
@RequiredArgsConstructor
public class AdminRenterVerificationController {
    
    private final RenterVerificationService verificationService;
    private final RenterDocumentRepository documentRepository;
    private final RenterVerificationAuditRepository auditRepository;
    private final UserRepository userRepository;
    private final SupabaseStorageService storageService;
    private final CurrentUser currentUser;
    private final AdminAuditService auditService;
    
    // ==================== QUEUE MANAGEMENT ====================
    
    @Operation(
        summary = "Get pending verification queue",
        description = "Returns paginated list of users awaiting driver license verification. " +
                      "Queries users by driver_license_status (not documents). " +
                      "Supports filtering by status and risk level, with FIFO ordering by submission time."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Queue retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "Not authenticated"),
        @ApiResponse(responseCode = "403", description = "Not admin")
    })
    @GetMapping("/pending")
    @Transactional(readOnly = true)
    public ResponseEntity<PagedVerificationResponse> getPendingQueue(
            @Parameter(description = "Page number (0-based)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @Parameter(description = "Filter by status (default: PENDING_REVIEW)") 
            @RequestParam(required = false) DriverLicenseStatus status,
            @Parameter(description = "Filter by risk level") 
            @RequestParam(required = false) RiskLevel riskLevel,
            @Parameter(description = "Sort order") 
            @RequestParam(defaultValue = "newest") String sortBy
    ) {
        // Default to PENDING_REVIEW if no status specified
        DriverLicenseStatus filterStatus = (status != null) ? status : DriverLicenseStatus.PENDING_REVIEW;
        
        // Build sort
        Sort sort = switch (sortBy) {
            case "oldest" -> Sort.by("renterVerificationSubmittedAt").ascending();
            case "riskLevel" -> Sort.by("riskLevel").descending().and(Sort.by("renterVerificationSubmittedAt").ascending());
            default -> Sort.by("renterVerificationSubmittedAt").descending(); // newest first
        };
        
        // Query users by driver license status (not documents!)
        org.springframework.data.domain.Page<User> usersPage = userRepository
            .findUsersByDriverLicenseStatusAndRiskLevel(filterStatus, riskLevel, PageRequest.of(page, size, sort));
        
        // Map to DTO with documents
        List<PendingVerificationItem> items = usersPage.getContent().stream()
            .map(user -> {
                // Get user's documents
                List<RenterDocument> docs = documentRepository.findByUserId(user.getId());
                
                return PendingVerificationItem.builder()
                    .userId(user.getId())
                    .userName(user.getFirstName() + " " + user.getLastName())
                    .userEmail(user.getEmail())
                    .submittedAt(user.getRenterVerificationSubmittedAt())
                    .riskLevel(user.getRiskLevel())
                    .documentCount(docs.size())
                    .documents(docs.stream()
                        .map(RenterDocumentDTO::fromEntityForAdmin)
                        .collect(Collectors.toList()))
                    .build();
            })
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(PagedVerificationResponse.builder()
            .content(items)
            .totalElements(usersPage.getTotalElements())
            .totalPages(usersPage.getTotalPages())
            .currentPage(page)
            .pageSize(size)
            .build());
    }
    
    @Operation(summary = "Get queue statistics", 
               description = "Returns count of users pending review and today's activity metrics")
    @GetMapping("/pending/stats")
    @Transactional(readOnly = true)
    public ResponseEntity<QueueStats> getQueueStats() {
        // Count users with PENDING_REVIEW status (not documents!)
        long pendingCount = userRepository.countPendingDriverLicenseVerifications();
        
        // Count by status
        long approvedToday = auditRepository.countByActionInRange(
            RenterVerificationAudit.AuditAction.MANUAL_APPROVED,
            LocalDateTime.now().withHour(0).withMinute(0),
            LocalDateTime.now()
        );
        long rejectedToday = auditRepository.countByActionInRange(
            RenterVerificationAudit.AuditAction.MANUAL_REJECTED,
            LocalDateTime.now().withHour(0).withMinute(0),
            LocalDateTime.now()
        );
        long autoApprovedToday = auditRepository.countByActionInRange(
            RenterVerificationAudit.AuditAction.AUTO_APPROVED,
            LocalDateTime.now().withHour(0).withMinute(0),
            LocalDateTime.now()
        );
        
        return ResponseEntity.ok(QueueStats.builder()
            .pendingCount(pendingCount)
            .approvedToday(approvedToday)
            .rejectedToday(rejectedToday)
            .autoApprovedToday(autoApprovedToday)
            .build());
    }
    
    // ==================== USER DETAIL VIEW ====================
    
    @Operation(
        summary = "Get user verification details",
        description = "Returns complete verification profile for admin review"
    )
    @GetMapping("/users/{userId}")
    @Transactional(readOnly = true)
    public ResponseEntity<RenterVerificationProfileDTO> getUserVerificationDetails(
            @PathVariable Long userId) {
        
        RenterVerificationProfileDTO profile = verificationService.getVerificationProfile(userId);
        
        // Enhance with admin-specific data without exposing raw document URLs by default
        List<RenterDocument> documents = documentRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<RenterDocumentDTO> adminDocs = documents.stream()
            .map(RenterDocumentDTO::fromEntityForAdmin)
            .collect(Collectors.toList());
        profile.setDocuments(adminDocs);
        
        return ResponseEntity.ok(profile);
    }
    
    @Operation(summary = "Get document detail metadata")
    @GetMapping("/documents/{documentId}")
    @Transactional(readOnly = true)
    public ResponseEntity<RenterDocumentDTO> getDocumentDetail(@PathVariable Long documentId) {
        RenterDocument doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new org.example.rentoza.exception.ResourceNotFoundException(
                "Document not found: " + documentId));
        
        return ResponseEntity.ok(RenterDocumentDTO.fromEntityForAdmin(doc));
    }
    
    // ==================== DOCUMENT ACCESS ENDPOINTS ====================
    
    @Operation(
        summary = "Grant explicit document reveal access",
        description = "Returns a short-lived signed URL for on-screen review. Requires an audit reason."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Signed URL generated"),
        @ApiResponse(responseCode = "404", description = "Document not found")
    })
    @PostMapping("/documents/{documentId}/reveal")
    @Transactional
    public ResponseEntity<DocumentAccessResponse> revealDocument(
            @PathVariable Long documentId,
            @RequestBody @Valid DocumentAccessRequest request
    ) {
        RenterDocument doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new org.example.rentoza.exception.ResourceNotFoundException(
                "Document not found: " + documentId));

        User actor = requireReviewer();
        logDocumentAccess(actor, doc, request, "REVEAL", AdminAction.DOCUMENT_VIEWED);

        String signedUrl = storageService.getRenterDocumentSignedUrl(doc.getDocumentUrl(), 300);
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(300);

        return ResponseEntity.ok(DocumentAccessResponse.builder()
            .url(signedUrl)
            .expiresAt(expiresAt)
            .documentId(documentId)
            .accessMode("REVEAL")
            .build());
    }
    
    @Operation(
        summary = "Grant explicit document download access",
        description = "Returns a short-lived signed URL for document download. Requires an audit reason."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Document content"),
        @ApiResponse(responseCode = "404", description = "Document not found"),
        @ApiResponse(responseCode = "500", description = "Error reading document")
    })
    @PostMapping("/documents/{documentId}/download")
    @Transactional
    public ResponseEntity<DocumentAccessResponse> downloadDocument(
            @PathVariable Long documentId,
            @RequestBody @Valid DocumentAccessRequest request
    ) {
        RenterDocument doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new org.example.rentoza.exception.ResourceNotFoundException(
                "Document not found: " + documentId));

        User actor = requireReviewer();
        logDocumentAccess(actor, doc, request, "DOWNLOAD", AdminAction.DOCUMENT_VIEWED);

        String signedUrl = storageService.getRenterDocumentSignedUrl(doc.getDocumentUrl(), 120);
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(120);

        return ResponseEntity.ok(DocumentAccessResponse.builder()
            .url(signedUrl)
            .expiresAt(expiresAt)
            .documentId(documentId)
            .accessMode("DOWNLOAD")
            .filename(resolveFilename(doc))
            .build());
    }
    
    @Operation(
        summary = "Check if renter document file exists in storage",
        description = "Diagnostic endpoint to verify a document file is present in the renter-documents bucket. " +
                      "Useful for debugging 404 download errors without requiring a full download."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Existence check result"),
        @ApiResponse(responseCode = "404", description = "Document record not found")
    })
    @GetMapping("/documents/{documentId}/exists")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> checkDocumentExists(@PathVariable Long documentId) {
        RenterDocument doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new org.example.rentoza.exception.ResourceNotFoundException(
                "Document not found: " + documentId));

        boolean existsInStorage = storageService.objectExistsInRenterDocuments(doc.getDocumentUrl());
        log.debug("[AdminRenter] Existence check: docId={}, path={}, existsInStorage={}",
            documentId, doc.getDocumentUrl(), existsInStorage);

        return ResponseEntity.ok(Map.of(
            "documentId", documentId,
            "storagePath", doc.getDocumentUrl(),
            "bucket", SupabaseStorageService.BUCKET_RENTER_DOCUMENTS,
            "existsInStorage", existsInStorage
        ));
    }
    
    // ==================== VERIFICATION ACTIONS ====================
    
    @Operation(
        summary = "Approve user verification",
        description = "Marks user as verified, allowing them to book cars"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Approved successfully"),
        @ApiResponse(responseCode = "400", description = "User not pending review"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    @PostMapping("/users/{userId}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'IDENTITY_REVIEWER')")
    public ResponseEntity<ApprovalResponse> approveVerification(
            @PathVariable Long userId,
            @RequestBody(required = false) @Valid ApprovalRequest request
    ) {
        Long adminId = currentUser.id();
        String notes = request != null ? request.getNotes() : null;
        
        log.info("Admin approving: userId={}, adminId={}", userId, adminId);
        
        verificationService.approveVerification(userId, adminId, notes);
        
        return ResponseEntity.ok(ApprovalResponse.builder()
            .userId(userId)
            .newStatus(DriverLicenseStatus.APPROVED)
            .message("Verification approved successfully")
            .build());
    }
    
    @Operation(
        summary = "Reject user verification",
        description = "Rejects verification, user must re-submit documents"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Rejected successfully"),
        @ApiResponse(responseCode = "400", description = "User not pending review or missing reason"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    @PostMapping("/users/{userId}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'IDENTITY_REVIEWER')")
    public ResponseEntity<ApprovalResponse> rejectVerification(
            @PathVariable Long userId,
            @RequestBody @Valid RejectionRequest request
    ) {
        Long adminId = currentUser.id();
        
        log.info("Admin rejecting: userId={}, adminId={}, reason={}", 
            userId, adminId, request.getReason());
        
        verificationService.rejectVerification(userId, adminId, request.getReason());
        
        return ResponseEntity.ok(ApprovalResponse.builder()
            .userId(userId)
            .newStatus(DriverLicenseStatus.REJECTED)
            .message("Verification rejected: " + request.getReason())
            .build());
    }
    
    @Operation(
        summary = "Suspend user verification",
        description = "Suspends user for fraud/abuse - requires investigation"
    )
    @PostMapping("/users/{userId}/suspend")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApprovalResponse> suspendVerification(
            @PathVariable Long userId,
            @RequestBody @Valid SuspensionRequest request
    ) {
        Long adminId = currentUser.id();
        
        log.warn("Admin suspending: userId={}, adminId={}, reason={}", 
            userId, adminId, request.getReason());
        
        verificationService.suspendVerification(userId, adminId, request.getReason());
        
        return ResponseEntity.ok(ApprovalResponse.builder()
            .userId(userId)
            .newStatus(DriverLicenseStatus.SUSPENDED)
            .message("User suspended: " + request.getReason())
            .build());
    }
    
    // ==================== AUDIT HISTORY ====================

    @Operation(summary = "Get verification audit history for user")
    @GetMapping("/users/{userId}/audits")
    @Transactional(readOnly = true)
    public ResponseEntity<List<AuditHistoryItem>> getUserAuditHistory(@PathVariable Long userId) {
        List<RenterVerificationAudit> audits = auditRepository.findByUserIdOrderByCreatedAtDesc(userId);
        
        List<AuditHistoryItem> items = audits.stream()
            .map(a -> AuditHistoryItem.builder()
                .id(a.getId())
                .action(a.getAction())
                .previousStatus(a.getPreviousStatus())
                .newStatus(a.getNewStatus())
                .reason(a.getReason())
                .actorName(a.getActor() != null 
                    ? a.getActor().getFirstName() + " " + a.getActor().getLastName() 
                    : "System")
                .timestamp(a.getCreatedAt())
                .build())
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(items);
    }
    
    // ==================== DIAGNOSTIC/RECOVERY ENDPOINTS ====================
    
    @Operation(
        summary = "Retry document processing",
        description = "Retry OCR/biometric processing for a stuck document. Use when document shows processingStatus=PENDING/FAILED."
    )
    @PostMapping("/documents/{documentId}/retry-processing")
    @PreAuthorize("hasAnyRole('ADMIN', 'IDENTITY_REVIEWER')")
    public ResponseEntity<Map<String, Object>> retryDocumentProcessing(@PathVariable Long documentId) {
        Long adminId = currentUser.id();
        log.info("Admin retrying processing: docId={}, adminId={}", documentId, adminId);
        
        RenterDocument doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new org.example.rentoza.exception.ResourceNotFoundException(
                "Document not found: " + documentId));
        
        // Reset to PENDING to allow reprocessing
        RenterDocument.ProcessingStatus oldStatus = doc.getProcessingStatus();
        doc.setProcessingStatus(RenterDocument.ProcessingStatus.PENDING);
        doc.setProcessingError(null);
        documentRepository.save(doc);
        
        // Trigger processing
        try {
            verificationService.processDocument(documentId);
            
            RenterDocument updated = documentRepository.findById(documentId).orElse(doc);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "documentId", documentId,
                "previousStatus", oldStatus.name(),
                "newStatus", updated.getProcessingStatus().name(),
                "message", "Processing completed"
            ));
        } catch (Exception e) {
            log.error("Retry processing failed: docId={}", documentId, e);
            return ResponseEntity.ok(Map.of(
                "success", false,
                "documentId", documentId,
                "error", e.getMessage(),
                "message", "Processing failed - document marked for manual review"
            ));
        }
    }
    
    @Operation(
        summary = "Mark document as ready for manual review",
        description = "Force a stuck document (PENDING/FAILED processing) into COMPLETED status so admin can review manually."
    )
    @PostMapping("/documents/{documentId}/force-ready")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> forceDocumentReady(@PathVariable Long documentId) {
        Long adminId = currentUser.id();
        
        RenterDocument doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new org.example.rentoza.exception.ResourceNotFoundException(
                "Document not found: " + documentId));
        
        RenterDocument.ProcessingStatus oldStatus = doc.getProcessingStatus();
        doc.setProcessingStatus(RenterDocument.ProcessingStatus.COMPLETED);
        doc.setProcessingError("Manually marked ready by admin");
        documentRepository.save(doc);
        
        log.info("Admin force-ready: docId={}, adminId={}, oldStatus={}", 
            documentId, adminId, oldStatus);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "documentId", documentId,
            "previousStatus", oldStatus.name(),
            "newStatus", "COMPLETED",
            "message", "Document marked ready for manual review"
        ));
    }
    
    // ==================== HELPER METHODS ====================
    
    private User requireReviewer() {
        Long actorId = currentUser.id();
        User actor = userRepository.findById(actorId)
            .orElseThrow(() -> new IllegalStateException("Reviewer not found"));

        if (actor.getRole() != org.example.rentoza.user.Role.ADMIN
                && actor.getRole() != org.example.rentoza.user.Role.IDENTITY_REVIEWER) {
            throw new org.springframework.security.access.AccessDeniedException("Document access requires reviewer privileges");
        }
        return actor;
    }

    private void logDocumentAccess(
            User actor,
            RenterDocument doc,
            DocumentAccessRequest request,
            String accessMode,
            AdminAction action
    ) {
        Map<String, Object> accessEvent = Map.of(
            "documentId", doc.getId(),
            "userId", doc.getUser().getId(),
            "documentType", doc.getType().name(),
            "mode", accessMode,
            "reason", request.getReason(),
            "caseReference", request.getCaseReference() != null ? request.getCaseReference() : "",
            "storagePath", doc.getDocumentUrl() != null ? doc.getDocumentUrl() : ""
        );

        auditService.logAction(
            actor,
            action,
            ResourceType.DOCUMENT,
            doc.getId(),
            auditService.toJson(RenterDocumentDTO.fromEntityForAdmin(doc)),
            auditService.toJson(accessEvent),
            "%s access: %s".formatted(accessMode.toLowerCase(Locale.ROOT), request.getReason())
        );
    }

    private String resolveFilename(RenterDocument doc) {
        if (doc.getOriginalFilename() != null && !doc.getOriginalFilename().isBlank()) {
            return doc.getOriginalFilename();
        }
        String extension = ".bin";
        if (doc.getMimeType() != null && doc.getMimeType().contains("/")) {
            extension = "." + doc.getMimeType().substring(doc.getMimeType().indexOf('/') + 1);
        }
        return "document-" + doc.getId() + extension;
    }
    
    // ==================== REQUEST/RESPONSE DTOs ====================
    
    /**
     * Paginated response for verification queue.
     * Matches frontend PagedRenterVerifications interface.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PagedVerificationResponse {
        private List<PendingVerificationItem> content;
        private long totalElements;
        private int totalPages;
        private int currentPage;
        private int pageSize;
    }
    
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PendingVerificationItem {
        private Long userId;
        private String userName;
        private String userEmail;
        private LocalDateTime submittedAt;
        private org.example.rentoza.user.RiskLevel riskLevel;
        private int documentCount;
        private List<RenterDocumentDTO> documents;
    }
    
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class QueueStats {
        private long pendingCount;
        private long approvedToday;
        private long rejectedToday;
        private long autoApprovedToday;
    }
    
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ApprovalRequest {
        private String notes;
    }
    
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class RejectionRequest {
        @NotBlank(message = "Rejection reason is required")
        private String reason;
    }
    
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SuspensionRequest {
        @NotBlank(message = "Suspension reason is required")
        private String reason;
    }
    
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ApprovalResponse {
        private Long userId;
        private DriverLicenseStatus newStatus;
        private String message;
    }
    
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AuditHistoryItem {
        private Long id;
        private RenterVerificationAudit.AuditAction action;
        private DriverLicenseStatus previousStatus;
        private DriverLicenseStatus newStatus;
        private String reason;
        private String actorName;
        private LocalDateTime timestamp;
    }
    
    /**
     * Response for signed URL request.
     * Frontend uses this to display documents in a viewer.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DocumentAccessResponse {
        /** URL to access the document (pre-signed storage URL) */
        private String url;
        /** When the URL expires (for security) */
        private LocalDateTime expiresAt;
        /** Document ID for reference */
        private Long documentId;
        /** Access mode: REVEAL or DOWNLOAD */
        private String accessMode;
        /** Original filename for download flows */
        private String filename;
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DocumentAccessRequest {
        @NotBlank(message = "Access reason is required")
        @Size(min = 8, max = 500, message = "Access reason must be between 8 and 500 characters")
        private String reason;

        @Size(max = 120, message = "Case reference must be 120 characters or less")
        private String caseReference;
    }
}

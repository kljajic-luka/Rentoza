package org.example.rentoza.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.car.DocumentVerificationStatus;
import org.example.rentoza.car.storage.DocumentStorageStrategy;
import org.example.rentoza.security.CurrentUser;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
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
 * <p>All endpoints require ADMIN role.
 */
@RestController
@RequestMapping("/api/admin/renter-verifications")
@Tag(name = "Admin - Renter Verification", description = "Admin management of renter license verification")
@PreAuthorize("hasRole('ADMIN')")
@Slf4j
@RequiredArgsConstructor
public class AdminRenterVerificationController {
    
    private final RenterVerificationService verificationService;
    private final RenterDocumentRepository documentRepository;
    private final RenterVerificationAuditRepository auditRepository;
    private final UserRepository userRepository;
    private final DocumentStorageStrategy storageStrategy;
    private final CurrentUser currentUser;
    
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
            @Parameter(description = "Page number (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
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
                        .map(d -> RenterDocumentDTO.fromEntityForAdmin(d, getSignedUrl(d)))
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
        
        // Enhance with admin-specific data (download URLs)
        List<RenterDocument> documents = documentRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<RenterDocumentDTO> adminDocs = documents.stream()
            .map(d -> RenterDocumentDTO.fromEntityForAdmin(d, getSignedUrl(d)))
            .collect(Collectors.toList());
        profile.setDocuments(adminDocs);
        
        return ResponseEntity.ok(profile);
    }
    
    @Operation(summary = "Get document detail with download URL")
    @GetMapping("/documents/{documentId}")
    @Transactional(readOnly = true)
    public ResponseEntity<RenterDocumentDTO> getDocumentDetail(@PathVariable Long documentId) {
        RenterDocument doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new org.example.rentoza.exception.ResourceNotFoundException(
                "Document not found: " + documentId));
        
        return ResponseEntity.ok(RenterDocumentDTO.fromEntityForAdmin(doc, getSignedUrl(doc)));
    }
    
    // ==================== DOCUMENT ACCESS ENDPOINTS ====================
    
    @Operation(
        summary = "Get signed URL for document viewing",
        description = "Returns a URL to access the renter's verification document. " +
                      "For local storage, returns a download endpoint URL. " +
                      "For S3 (future), returns a pre-signed URL with expiry."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Signed URL generated"),
        @ApiResponse(responseCode = "404", description = "Document not found")
    })
    @PostMapping("/documents/{documentId}/signed-url")
    @Transactional(readOnly = true)
    public ResponseEntity<SignedUrlResponse> getDocumentSignedUrl(@PathVariable Long documentId) {
        RenterDocument doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new org.example.rentoza.exception.ResourceNotFoundException(
                "Document not found: " + documentId));
        
        log.debug("Generating signed URL for documentId={}, user={}", documentId, doc.getUser().getId());
        
        // For local storage: Return the download endpoint URL
        // For S3 (future): This would call storageStrategy.getSignedUrl() with expiry
        String downloadUrl = "/api/admin/renter-verifications/documents/" + documentId + "/download";
        
        // Calculate expiry (15 minutes from now for security)
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(15);
        
        return ResponseEntity.ok(SignedUrlResponse.builder()
            .url(downloadUrl)
            .expiresAt(expiresAt)
            .documentId(documentId)
            .build());
    }
    
    @Operation(
        summary = "Download renter verification document",
        description = "Stream document content for admin preview. " +
                      "Returns proper MIME type and Content-Disposition headers."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Document content"),
        @ApiResponse(responseCode = "404", description = "Document not found"),
        @ApiResponse(responseCode = "500", description = "Error reading document")
    })
    @GetMapping("/documents/{documentId}/download")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> downloadDocument(@PathVariable Long documentId) {
        RenterDocument doc = documentRepository.findById(documentId)
            .orElseThrow(() -> new org.example.rentoza.exception.ResourceNotFoundException(
                "Document not found: " + documentId));
        
        log.info("Admin downloading renter document: docId={}, userId={}", documentId, doc.getUser().getId());
        
        try {
            // Get file content from storage
            byte[] content = storageStrategy.getFile(doc.getDocumentUrl());
            
            // Determine MIME type
            org.springframework.http.MediaType mediaType = org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
            if (doc.getMimeType() != null && !doc.getMimeType().isBlank()) {
                try {
                    mediaType = org.springframework.http.MediaType.parseMediaType(doc.getMimeType());
                } catch (Exception ignored) {
                    // Keep octet-stream fallback
                }
            }
            
            // Build filename
            String filename = doc.getOriginalFilename() != null && !doc.getOriginalFilename().isBlank()
                ? doc.getOriginalFilename()
                : "document-" + documentId + ".jpg";
            
            org.springframework.http.ContentDisposition contentDisposition = 
                org.springframework.http.ContentDisposition.inline()
                    .filename(filename, java.nio.charset.StandardCharsets.UTF_8)
                    .build();
            
            return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(content);
                
        } catch (java.io.IOException e) {
            log.error("Failed to read document content: docId={}, path={}", documentId, doc.getDocumentUrl(), e);
            throw new RuntimeException("Failed to read document: " + e.getMessage());
        }
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
    
    private String getSignedUrl(RenterDocument doc) {
        // In production, this would generate a signed S3 URL with expiry
        // For local storage, just return the path
        try {
            return storageStrategy.getPublicUrl(doc.getDocumentUrl());
        } catch (Exception e) {
            log.warn("Could not get signed URL for docId={}", doc.getId());
            return null;
        }
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
    public static class SignedUrlResponse {
        /** URL to access the document (download endpoint or pre-signed S3 URL) */
        private String url;
        /** When the URL expires (for security) */
        private LocalDateTime expiresAt;
        /** Document ID for reference */
        private Long documentId;
    }
}

package org.example.rentoza.admin.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.rentoza.admin.dto.DocumentReviewDto;
import org.example.rentoza.admin.dto.DocumentVerificationRequestDto;
import org.example.rentoza.admin.service.AdminAuditService;
import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.car.CarDocument;
import org.example.rentoza.car.CarDocumentService;
import org.example.rentoza.car.dto.CarDocumentDto;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Admin endpoints for document verification (Serbian compliance).
 * 
 * <p>RBAC: Requires ADMIN role.
 */
@RestController
@RequestMapping("/api/admin/documents")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminDocumentController {
    
    private static final Logger log = LoggerFactory.getLogger(AdminDocumentController.class);
    
    private final CarDocumentService documentService;
    private final AdminAuditService auditService;
    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    
    /**
     * Get all pending documents awaiting verification.
     */
    @GetMapping("/pending")
    public ResponseEntity<List<CarDocumentDto>> getPendingDocuments() {
        List<CarDocument> pendingDocs = documentService.getPendingDocuments();
        List<CarDocumentDto> dtos = pendingDocs.stream()
            .map(CarDocumentDto::from)
            .toList();
        
        log.info("Fetched {} pending documents for admin review", dtos.size());
        return ResponseEntity.ok(dtos);
    }
    
    /**
     * Verify or reject a document.
     * 
     * @param documentId Document ID
     * @param request Verification request (approved=true/false, rejectionReason if false)
     * @return Updated document DTO
     */
    @PostMapping("/{documentId}/verify")
    public ResponseEntity<DocumentReviewDto> verifyDocument(
            @PathVariable Long documentId,
            @Valid @RequestBody DocumentVerificationRequestDto request) {
        
        Long adminId = currentUser.id();
        User admin = userRepository.findById(adminId)
            .orElseThrow(() -> new IllegalStateException("Admin not found"));
        
        if (Boolean.TRUE.equals(request.getApproved())) {
            // Mark as VERIFIED
            documentService.verifyDocument(documentId, admin);
            
            // Audit log
            auditService.logAction(
                admin,
                AdminAction.DOCUMENT_VERIFIED,
                ResourceType.DOCUMENT,
                documentId,
                null,
                null,
                "Document verified by admin"
            );
            
            log.info("Document {} verified by admin {}", documentId, adminId);
        } else {
            // Validate rejection reason
            String reason = request.getRejectionReason();
            if (reason == null || reason.length() < 20) {
                throw new IllegalArgumentException("Rejection reason must be at least 20 characters");
            }
            
            // Mark as REJECTED
            documentService.rejectDocument(documentId, reason, admin);
            
            // Audit log
            auditService.logAction(
                admin,
                AdminAction.DOCUMENT_REJECTED,
                ResourceType.DOCUMENT,
                documentId,
                null,
                null,
                reason
            );
            
            log.warn("Document {} rejected by admin {}: {}", documentId, adminId, reason);
        }

        // Return DTO built within a transactional boundary (prevents LazyInitializationException)
        return ResponseEntity.ok(documentService.getDocumentReview(documentId));
    }

    /**
     * Stream a document to the browser for admin preview.
     *
     * <p>RBAC: ADMIN only (controller-level PreAuthorize).
     * <p>Security: Never exposes filesystem paths; LocalDocumentStorageStrategy enforces root confinement.
     */
    @GetMapping("/{documentId}/download")
    public ResponseEntity<byte[]> downloadDocument(@PathVariable Long documentId) {
        Long adminId = currentUser.id();
        User admin = userRepository.findById(adminId)
            .orElseThrow(() -> new IllegalStateException("Admin not found"));

        CarDocument document = documentService.getDocumentById(documentId);
        byte[] bytes = documentService.getDocumentContent(document);

        // Optional but recommended: audit admin viewing
        auditService.logAction(
            admin,
            AdminAction.DOCUMENT_VIEWED,
            ResourceType.DOCUMENT,
            documentId,
            null,
            null,
            "Document viewed by admin"
        );

        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        try {
            if (document.getMimeType() != null && !document.getMimeType().isBlank()) {
                mediaType = MediaType.parseMediaType(document.getMimeType());
            }
        } catch (Exception ignored) {
            // Keep octet-stream fallback
        }

        String filename = (document.getOriginalFilename() != null && !document.getOriginalFilename().isBlank())
            ? document.getOriginalFilename()
            : ("document-" + documentId);

        ContentDisposition contentDisposition = ContentDisposition.inline()
            .filename(filename, StandardCharsets.UTF_8)
            .build();

        return ResponseEntity.ok()
            .contentType(mediaType)
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(bytes);
    }
}

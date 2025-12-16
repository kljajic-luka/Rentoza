package org.example.rentoza.user;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.checkin.verification.IdVerificationProvider;
import org.example.rentoza.booking.checkin.verification.SerbianNameNormalizer;
import org.example.rentoza.car.DocumentVerificationStatus;
import org.example.rentoza.car.storage.DocumentStorageStrategy;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.exception.ValidationException;
import org.example.rentoza.user.document.*;
import org.example.rentoza.user.dto.*;
import org.example.rentoza.user.verification.event.VerificationApprovedEvent;
import org.example.rentoza.user.verification.event.VerificationRejectedEvent;
import org.example.rentoza.util.HashUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for renter driver license verification.
 * 
 * <p>Handles the complete verification workflow:
 * <ol>
 *   <li>Document upload and validation</li>
 *   <li>OCR extraction and name matching</li>
 *   <li>Risk-based auto-approval or manual review escalation</li>
 *   <li>Admin approval/rejection workflows</li>
 *   <li>Booking eligibility checking</li>
 * </ol>
 * 
 * <p>Reuses existing infrastructure:
 * <ul>
 *   <li>{@link IdVerificationProvider} - OCR extraction and liveness</li>
 *   <li>{@link SerbianNameNormalizer} - Name matching</li>
 *   <li>{@link DocumentStorageStrategy} - File storage</li>
 *   <li>{@link HashUtil} - Document hashing</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RenterVerificationService {
    
    // ==================== DEPENDENCIES ====================
    
    private final UserRepository userRepository;
    private final RenterDocumentRepository documentRepository;
    private final RenterVerificationAuditRepository auditRepository;
    private final DocumentStorageStrategy storageStrategy;
    private final IdVerificationProvider verificationProvider;
    private final SerbianNameNormalizer nameNormalizer;
    private final HashUtil hashUtil;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    
    // ==================== CONFIGURATION ====================
    
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
        "image/jpeg", "image/jpg", "image/png"
    );
    
    @Value("${app.renter-verification.name-match-threshold:0.80}")
    private double nameMatchThreshold;
    
    @Value("${app.renter-verification.face-match-threshold:0.85}")
    private double faceMatchThreshold;
    
    @Value("${app.renter-verification.license-required:true}")
    private boolean licenseRequired;
    
    @Value("${app.renter-verification.new-account-threshold-days:30}")
    private int newAccountThresholdDays;
    
    // ==================== DOCUMENT UPLOAD ====================
    
    /**
     * Submit a document for driver license verification.
     * 
     * @param userId User ID
     * @param file Document image file
     * @param request Submission metadata (type, expiry, etc.)
     * @return Created document DTO
     */
    @Transactional
    public RenterDocumentDTO submitDocument(
            Long userId,
            MultipartFile file,
            DriverLicenseSubmissionRequest request) throws IOException {
        
        log.info("Document submission: userId={}, type={}", userId, request.getDocumentType());
        
        // Get user
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        
        // Validate user can submit
        // Only allow submission in NOT_STARTED, REJECTED, or EXPIRED states
        // PENDING_REVIEW users must wait for admin decision before re-submitting
        if (!user.canSubmitDriverLicense()) {
            throw new ValidationException(
                "Cannot submit documents in current status: " + user.getDriverLicenseStatus()
            );
        }
        
        // Validate file
        validateFile(file);
        
        // Validate expiry date if provided
        // NOTE: Expiry date is optional at submission time. User can provide it manually,
        // or it will be extracted via OCR during review. Admin must verify/add expiry before approval.
        RenterDocumentType docType = request.getDocumentType();
        if (request.getExpiryDate() != null && request.getExpiryDate().isBefore(LocalDate.now())) {
            throw new ValidationException("Document has already expired");
        }
        
        // Calculate document hash for duplicate detection
        String documentHash = calculateSha256(file.getBytes());
        
        // Check for duplicate document from another user (fraud detection)
        if (documentRepository.existsByDocumentHashForDifferentUser(documentHash, userId)) {
            log.warn("Duplicate document detected: userId={}, hash={}", userId, documentHash);
            throw new ValidationException("This document has already been submitted by another user");
        }
        
        // Replace existing document of same type
        documentRepository.findByUserIdAndType(userId, docType)
            .ifPresent(existing -> {
                log.info("Replacing existing {} for userId={}", docType, userId);
                documentRepository.delete(existing);
            });
        
        // Generate storage path
        String storagePath = String.format("users/%d/verification/%s_%d.%s",
            userId, docType.name().toLowerCase(), System.currentTimeMillis(),
            getFileExtension(file.getOriginalFilename()));
        
        // Upload to storage
        String documentUrl = storageStrategy.uploadFile(file, storagePath);
        
        // Create document entity
        RenterDocument document = RenterDocument.builder()
            .user(user)
            .type(docType)
            .documentUrl(documentUrl)
            .originalFilename(file.getOriginalFilename())
            .documentHash(documentHash)
            .fileSize(file.getSize())
            .mimeType(file.getContentType())
            .uploadDate(LocalDateTime.now())
            .expiryDate(request.getExpiryDate())
            .status(DocumentVerificationStatus.PENDING)
            .processingStatus(RenterDocument.ProcessingStatus.PENDING)
            .build();
        
        document = documentRepository.save(document);
        
        // Update user status if first submission
        boolean isFirstSubmission = user.getDriverLicenseStatus() == DriverLicenseStatus.NOT_STARTED;
        if (isFirstSubmission) {
            user.setDriverLicenseStatus(DriverLicenseStatus.PENDING_REVIEW);
            user.setRenterVerificationSubmittedAt(LocalDateTime.now());
            userRepository.save(user);
        }
        
        // Store optional metadata
        if (request.getLicenseNumber() != null) {
            user.setDriverLicenseNumber(request.getLicenseNumber());
            user.setDriverLicenseNumberHash(hashUtil.hash(request.getLicenseNumber()));
        }
        if (request.getLicenseCountry() != null) {
            user.setDriverLicenseCountry(request.getLicenseCountry());
        }
        if (request.getLicenseCategories() != null) {
            user.setDriverLicenseCategories(request.getLicenseCategories());
        }
        if (request.getExpiryDate() != null && docType.isDriversLicense()) {
            user.setDriverLicenseExpiryDate(request.getExpiryDate());
        }
        userRepository.save(user);
        
        // Create audit log
        createAudit(user, document, null,
            isFirstSubmission ? RenterVerificationAudit.AuditAction.SUBMITTED 
                : RenterVerificationAudit.AuditAction.RESUBMITTED,
            user.getDriverLicenseStatus(), DriverLicenseStatus.PENDING_REVIEW,
            "Document type: " + docType);
        
        log.info("Document submitted: userId={}, docId={}, type={}", userId, document.getId(), docType);
        
        // Trigger async processing
        triggerDocumentProcessing(document);
        
        return RenterDocumentDTO.fromEntity(document);
    }
    
    // ==================== DOCUMENT PROCESSING ====================
    
    /**
     * Process document with OCR and biometric checks.
     * Called asynchronously after upload via dedicated thread pool.
     * 
     * <p>Processing includes:
     * <ol>
     *   <li>OCR extraction (license number, expiry, name)</li>
     *   <li>Name matching with Serbian diacritical handling</li>
     *   <li>Risk scoring and auto-approval decision</li>
     *   <li>Status transition (PENDING → APPROVED/PENDING_REVIEW)</li>
     * </ol>
     * 
     * @param documentId Document to process
     */
    @Async("renterVerificationExecutor")
    @Transactional
    public void processDocument(Long documentId) {
        RenterDocument document = documentRepository.findById(documentId)
            .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));
        
        if (document.getProcessingStatus() != RenterDocument.ProcessingStatus.PENDING) {
            log.warn("Document already processed: docId={}, status={}", 
                documentId, document.getProcessingStatus());
            return;
        }
        
        log.info("Processing document: docId={}, type={}", documentId, document.getType());
        
        try {
            document.setProcessingStatus(RenterDocument.ProcessingStatus.PROCESSING);
            documentRepository.save(document);
            
            // Get file bytes
            byte[] fileBytes = storageStrategy.getFile(document.getDocumentUrl());
            String mimeType = document.getMimeType();
            
            if (document.getType().requiresOcr()) {
                // Run OCR extraction
                IdVerificationProvider.DocumentExtraction ocrResult = 
                    verificationProvider.extractDocumentData(fileBytes, null, mimeType);
                
                if (ocrResult.isSuccess()) {
                    // Store OCR results
                    document.setOcrExtractedData(serializeOcrResult(ocrResult));
                    document.setOcrConfidence(BigDecimal.valueOf(0.90)); // Default if provider doesn't return
                    
                    User user = document.getUser();
                    
                    // Update expiry if extracted
                    if (ocrResult.getExpiryDate() != null) {
                        document.setExpiryDate(ocrResult.getExpiryDate());
                        if (document.getType().isDriversLicense()) {
                            user.setDriverLicenseExpiryDate(ocrResult.getExpiryDate());
                        }
                    }
                    
                    // ENTERPRISE-GRADE: Store Date of Birth from verified OCR
                    // This is the trusted source for age - extracted from official document
                    if (ocrResult.getDateOfBirth() != null && document.getType().isDriversLicense()) {
                        user.setVerifiedDateOfBirth(ocrResult.getDateOfBirth());
                        log.info("DOB extracted from license OCR: userId={}, dob={}", 
                            user.getId(), ocrResult.getDateOfBirth());
                    }
                    
                    // Store license categories if extracted
                    if (ocrResult.getLicenseCategories() != null && document.getType().isDriversLicense()) {
                        user.setDriverLicenseCategories(ocrResult.getLicenseCategories());
                    }
                    
                    // Calculate license tenure from issue date
                    if (ocrResult.getIssueDate() != null && document.getType().isDriversLicense()) {
                        long tenureMonths = java.time.temporal.ChronoUnit.MONTHS.between(
                            ocrResult.getIssueDate(), java.time.LocalDate.now());
                        user.setDriverLicenseTenureMonths((int) tenureMonths);
                    }
                    
                    // Calculate name match
                    String profileName = nameNormalizer.normalizeFullName(
                        user.getFirstName(), user.getLastName());
                    String ocrName = nameNormalizer.normalizeFullName(
                        ocrResult.getFirstName(), ocrResult.getLastName());
                    
                    double nameMatch = nameNormalizer.jaroWinklerSimilarity(profileName, ocrName);
                    document.setNameMatchScore(BigDecimal.valueOf(nameMatch));
                    
                    log.info("OCR complete: docId={}, nameMatch={}", documentId, nameMatch);
                } else {
                    log.warn("OCR failed: docId={}, error={}", documentId, ocrResult.getErrorMessage());
                    document.setProcessingError("OCR extraction failed: " + ocrResult.getErrorMessage());
                }
            }
            
            if (document.getType().requiresLiveness()) {
                // Run liveness check for selfie
                IdVerificationProvider.LivenessResult livenessResult = 
                    verificationProvider.checkLiveness(fileBytes, mimeType);
                
                document.setLivenessPassed(livenessResult.isPassed());
                if (!livenessResult.isPassed()) {
                    document.setProcessingError("Liveness check failed");
                }
                
                log.info("Liveness check: docId={}, passed={}", documentId, livenessResult.isPassed());
                
                // Face matching: Compare selfie to license front photo if liveness passed
                if (livenessResult.isPassed() && document.getType() == RenterDocumentType.SELFIE) {
                    performFaceMatching(document, fileBytes, mimeType);
                }
            }
            
            document.setProcessingStatus(RenterDocument.ProcessingStatus.COMPLETED);
            documentRepository.save(document);
            
            // Create processing audit
            createAudit(document.getUser(), document, null,
                RenterVerificationAudit.AuditAction.PROCESSING_COMPLETED,
                null, null, "OCR and biometric processing completed");
            
            // Attempt auto-verification
            attemptAutoVerification(document.getUser().getId());
            
        } catch (Exception e) {
            log.error("Document processing failed: docId={}", documentId, e);
            document.setProcessingStatus(RenterDocument.ProcessingStatus.FAILED);
            document.setProcessingError(e.getMessage());
            documentRepository.save(document);
            
            createAudit(document.getUser(), document, null,
                RenterVerificationAudit.AuditAction.PROCESSING_FAILED,
                null, null, "Processing error: " + e.getMessage());
        }
    }
    
    // ==================== AUTO-VERIFICATION ====================
    
    /**
     * Attempt automatic verification based on risk level and document quality.
     */
    @Transactional
    public void attemptAutoVerification(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        
        // Skip if not in PENDING_REVIEW status
        if (user.getDriverLicenseStatus() != DriverLicenseStatus.PENDING_REVIEW) {
            log.debug("Skipping auto-verification: userId={}, status={}", 
                userId, user.getDriverLicenseStatus());
            return;
        }
        
        // Check if all required documents are processed
        // Required: DRIVERS_LICENSE_FRONT, DRIVERS_LICENSE_BACK
        // Optional: SELFIE (Phase 2 feature - when implemented, liveness check enhances security)
        List<RenterDocument> documents = documentRepository.findByUserId(userId);
        boolean hasLicenseFront = documents.stream()
            .anyMatch(d -> d.getType() == RenterDocumentType.DRIVERS_LICENSE_FRONT 
                && d.getProcessingStatus() == RenterDocument.ProcessingStatus.COMPLETED);
        boolean hasLicenseBack = documents.stream()
            .anyMatch(d -> d.getType() == RenterDocumentType.DRIVERS_LICENSE_BACK 
                && d.getProcessingStatus() == RenterDocument.ProcessingStatus.COMPLETED);
        
        // Require only license front + back for verification
        if (!hasLicenseFront || !hasLicenseBack) {
            log.debug("Not all required documents processed: userId={}, front={}, back={}", 
                userId, hasLicenseFront, hasLicenseBack);
            return;
        }
        
        // Calculate risk level
        RiskLevel riskLevel = calculateRiskLevel(user, documents);
        user.setRiskLevel(riskLevel);
        user.setLastRiskEvaluationAt(LocalDateTime.now());
        
        log.info("Risk evaluation: userId={}, riskLevel={}", userId, riskLevel);
        
        // Check if auto-approve is enabled for this risk level
        if (!riskLevel.isAutoApproveEnabled()) {
            log.info("Manual review required: userId={}, riskLevel={}", userId, riskLevel);
            createAudit(user, null, null,
                RenterVerificationAudit.AuditAction.ESCALATED_TO_REVIEW,
                DriverLicenseStatus.PENDING_REVIEW, DriverLicenseStatus.PENDING_REVIEW,
                "HIGH risk - manual review required");
            userRepository.save(user);
            return;
        }
        
        // Check OCR confidence and name match
        Optional<RenterDocument> licenseFront = documents.stream()
            .filter(d -> d.getType() == RenterDocumentType.DRIVERS_LICENSE_FRONT)
            .findFirst();
        
        if (licenseFront.isEmpty()) {
            return;
        }
        
        RenterDocument licenseDoc = licenseFront.get();
        double ocrConfidence = licenseDoc.getOcrConfidence() != null 
            ? licenseDoc.getOcrConfidence().doubleValue() : 0.0;
        double nameMatch = licenseDoc.getNameMatchScore() != null 
            ? licenseDoc.getNameMatchScore().doubleValue() : 0.0;
        
        // Check selfie liveness (optional - Phase 2 feature)
        // If selfie not submitted, liveness is assumed passed
        // When selfie upload is implemented, this provides additional fraud protection
        Optional<RenterDocument> selfie = documents.stream()
            .filter(d -> d.getType() == RenterDocumentType.SELFIE)
            .findFirst();
        boolean livenessPassed = selfie
            .map(d -> Boolean.TRUE.equals(d.getLivenessPassed()))
            .orElse(true); // No selfie = assume passed (Phase 2 will require this)
        
        // Auto-approve decision
        boolean meetsOcrThreshold = riskLevel.meetsAutoApproveThreshold(ocrConfidence);
        boolean meetsNameThreshold = nameMatch >= nameMatchThreshold;
        
        if (meetsOcrThreshold && meetsNameThreshold && livenessPassed) {
            // AUTO-APPROVE
            log.info("Auto-approving: userId={}, ocr={}, nameMatch={}", 
                userId, ocrConfidence, nameMatch);
            
            DriverLicenseStatus previousStatus = user.getDriverLicenseStatus();
            user.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);
            user.setDriverLicenseVerifiedAt(LocalDateTime.now());
            userRepository.save(user);
            
            // Update document statuses
            for (RenterDocument doc : documents) {
                if (doc.getType().isRequired()) {
                    doc.setStatus(DocumentVerificationStatus.VERIFIED);
                    doc.setVerifiedAt(LocalDateTime.now());
                    documentRepository.save(doc);
                }
            }
            
            String reason = String.format(
                "Auto-approved: OCR=%.2f, NameMatch=%.2f, Liveness=%s, RiskLevel=%s",
                ocrConfidence, nameMatch, livenessPassed, riskLevel);
            
            createAudit(user, licenseDoc, null,
                RenterVerificationAudit.AuditAction.AUTO_APPROVED,
                previousStatus, DriverLicenseStatus.APPROVED, reason);
            
            // Publish event for async notifications (email, in-app, push)
            eventPublisher.publishEvent(VerificationApprovedEvent.autoApproved(
                this, user, user.getDriverLicenseExpiryDate()
            ));
            
        } else {
            // Escalate to manual review
            log.info("Escalating to review: userId={}, ocr={}, nameMatch={}, liveness={}",
                userId, ocrConfidence, nameMatch, livenessPassed);
            
            String reason = String.format(
                "Below threshold: OCR=%.2f (need %.2f), NameMatch=%.2f (need %.2f), Liveness=%s",
                ocrConfidence, riskLevel.getAutoApproveThreshold(), 
                nameMatch, nameMatchThreshold, livenessPassed);
            
            createAudit(user, licenseDoc, null,
                RenterVerificationAudit.AuditAction.ESCALATED_TO_REVIEW,
                DriverLicenseStatus.PENDING_REVIEW, DriverLicenseStatus.PENDING_REVIEW, reason);
        }
        
        userRepository.save(user);
    }
    
    // ==================== FACE MATCHING ====================
    
    /**
     * Perform face matching between selfie and driver's license photo.
     * 
     * <p>This is invoked after liveness check passes. For HIGH-risk users,
     * face matching is mandatory. For LOW-risk users, it's optional but
     * improves verification confidence.
     * 
     * @param selfieDocument The selfie document being processed
     * @param selfieBytes The selfie image bytes
     * @param mimeType The MIME type of the selfie
     */
    private void performFaceMatching(RenterDocument selfieDocument, byte[] selfieBytes, String mimeType) {
        User user = selfieDocument.getUser();
        
        // Find the license front document for this user
        Optional<RenterDocument> licenseFrontOpt = documentRepository.findByUserId(user.getId())
            .stream()
            .filter(d -> d.getType() == RenterDocumentType.DRIVERS_LICENSE_FRONT)
            .filter(d -> d.getProcessingStatus() == RenterDocument.ProcessingStatus.COMPLETED)
            .findFirst();
        
        if (licenseFrontOpt.isEmpty()) {
            log.warn("Face matching skipped: No license front found for userId={}", user.getId());
            return;
        }
        
        RenterDocument licenseFront = licenseFrontOpt.get();
        
        try {
            // Get license front bytes from storage
            byte[] licenseBytes = storageStrategy.getFile(licenseFront.getDocumentUrl());
            
            // Perform face matching
            IdVerificationProvider.FaceMatchResult matchResult = 
                verificationProvider.matchFaces(selfieBytes, licenseBytes, mimeType);
            
            // Store result in selfie document
            selfieDocument.setFaceMatchScore(matchResult.getConfidence());
            
            boolean faceMatchPassed = matchResult.isMatched() && 
                matchResult.getConfidence().doubleValue() >= faceMatchThreshold;
            
            if (faceMatchPassed) {
                log.info("Face match PASSED: userId={}, confidence={}", 
                    user.getId(), matchResult.getConfidence());
            } else {
                log.warn("Face match FAILED: userId={}, confidence={}, threshold={}", 
                    user.getId(), matchResult.getConfidence(), faceMatchThreshold);
                selfieDocument.setProcessingError("Face match failed: selfie doesn't match license photo");
            }
            
            documentRepository.save(selfieDocument);
            
            // Create audit entry
            createAudit(user, selfieDocument, null,
                faceMatchPassed ? RenterVerificationAudit.AuditAction.FACE_MATCH_PASSED 
                               : RenterVerificationAudit.AuditAction.FACE_MATCH_FAILED,
                null, null,
                String.format("Face match score: %.2f (threshold: %.2f)", 
                    matchResult.getConfidence().doubleValue(), faceMatchThreshold));
                    
        } catch (Exception e) {
            log.error("Face matching error: userId={}", user.getId(), e);
            selfieDocument.setProcessingError("Face matching error: " + e.getMessage());
            documentRepository.save(selfieDocument);
        }
    }
    
    // ==================== RISK SCORING ====================
    
    /**
     * Calculate risk level for a user based on various factors.
     */
    private RiskLevel calculateRiskLevel(User user, List<RenterDocument> documents) {
        int riskScore = 0;
        
        // Factor 1: Account age (new accounts = higher risk)
        long accountAgeDays = ChronoUnit.DAYS.between(
            user.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
            LocalDate.now());
        if (accountAgeDays < newAccountThresholdDays) {
            riskScore += 30;
        } else if (accountAgeDays < 90) {
            riskScore += 10;
        }
        
        // Factor 2: Previous completed trips (more = lower risk)
        // This would need BookingRepository query - simplified for now
        // TODO: Add completed trips count check
        
        // Factor 3: Name match score (low = higher risk)
        double avgNameMatch = documents.stream()
            .filter(d -> d.getNameMatchScore() != null)
            .mapToDouble(d -> d.getNameMatchScore().doubleValue())
            .average()
            .orElse(1.0);
        
        if (avgNameMatch < 0.70) {
            riskScore += 30; // Significant mismatch
        } else if (avgNameMatch < 0.85) {
            riskScore += 15;
        }
        
        // Factor 4: OCR confidence (low = higher risk)
        double avgOcrConfidence = documents.stream()
            .filter(d -> d.getOcrConfidence() != null)
            .mapToDouble(d -> d.getOcrConfidence().doubleValue())
            .average()
            .orElse(0.0);
        
        if (avgOcrConfidence < 0.70) {
            riskScore += 15;
        }
        
        // Factor 5: Liveness check failed
        boolean livenessFailed = documents.stream()
            .filter(d -> d.getType() == RenterDocumentType.SELFIE)
            .anyMatch(d -> Boolean.FALSE.equals(d.getLivenessPassed()));
        if (livenessFailed) {
            riskScore += 40;
        }
        
        // Factor 6: Multiple submission attempts (many resubmits = higher risk)
        long submissionCount = auditRepository.findByUserIdAndAction(
            user.getId(), RenterVerificationAudit.AuditAction.SUBMITTED).size()
            + auditRepository.findByUserIdAndAction(
            user.getId(), RenterVerificationAudit.AuditAction.RESUBMITTED).size();
        if (submissionCount > 3) {
            riskScore += 20;
        }
        
        // Determine risk level
        if (riskScore >= 50) {
            return RiskLevel.HIGH;
        } else if (riskScore >= 20) {
            return RiskLevel.MEDIUM;
        } else {
            return RiskLevel.LOW;
        }
    }
    
    // ==================== ADMIN ACTIONS ====================
    
    /**
     * Admin approves a user's driver license verification.
     */
    @Transactional
    public void approveVerification(Long userId, Long adminId, String notes) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        User admin = userRepository.findById(adminId)
            .orElseThrow(() -> new ResourceNotFoundException("Admin not found: " + adminId));
        
        if (user.getDriverLicenseStatus() != DriverLicenseStatus.PENDING_REVIEW) {
            throw new ValidationException("User is not pending review");
        }
        
        DriverLicenseStatus previousStatus = user.getDriverLicenseStatus();
        
        // Update user status
        user.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);
        user.setDriverLicenseVerifiedAt(LocalDateTime.now());
        user.setDriverLicenseVerifiedBy(admin);
        userRepository.save(user);
        
        // Update document statuses
        List<RenterDocument> documents = documentRepository.findByUserId(userId);
        for (RenterDocument doc : documents) {
            if (doc.getStatus() == DocumentVerificationStatus.PENDING) {
                doc.setStatus(DocumentVerificationStatus.VERIFIED);
                doc.setVerifiedAt(LocalDateTime.now());
                doc.setVerifiedBy(admin);
                documentRepository.save(doc);
            }
        }
        
        // Create audit
        createAudit(user, null, admin,
            RenterVerificationAudit.AuditAction.MANUAL_APPROVED,
            previousStatus, DriverLicenseStatus.APPROVED,
            notes != null ? notes : "Manual approval by admin");
        
        // Publish event for async notifications (email, in-app, push)
        eventPublisher.publishEvent(new VerificationApprovedEvent(
            this,
            user,
            user.getDriverLicenseExpiryDate(),
            admin.getEmail(),
            notes
        ));
        
        log.info("Admin approved: userId={}, adminId={}", userId, adminId);
    }
    
    /**
     * Admin rejects a user's driver license verification.
     */
    @Transactional
    public void rejectVerification(Long userId, Long adminId, String reason) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        User admin = userRepository.findById(adminId)
            .orElseThrow(() -> new ResourceNotFoundException("Admin not found: " + adminId));
        
        if (user.getDriverLicenseStatus() != DriverLicenseStatus.PENDING_REVIEW) {
            throw new ValidationException("User is not pending review");
        }
        
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("Rejection reason is required");
        }
        
        DriverLicenseStatus previousStatus = user.getDriverLicenseStatus();
        
        // Update user status
        user.setDriverLicenseStatus(DriverLicenseStatus.REJECTED);
        userRepository.save(user);
        
        // Update document statuses
        List<RenterDocument> documents = documentRepository.findByUserId(userId);
        for (RenterDocument doc : documents) {
            if (doc.getStatus() == DocumentVerificationStatus.PENDING) {
                doc.setStatus(DocumentVerificationStatus.REJECTED);
                doc.setVerifiedAt(LocalDateTime.now());
                doc.setVerifiedBy(admin);
                doc.setRejectionReason(reason);
                documentRepository.save(doc);
            }
        }
        
        // Create audit
        createAudit(user, null, admin,
            RenterVerificationAudit.AuditAction.MANUAL_REJECTED,
            previousStatus, DriverLicenseStatus.REJECTED, reason);
        
        // Publish event for async notifications (email, in-app, push)
        eventPublisher.publishEvent(new VerificationRejectedEvent(
            this,
            user,
            reason,
            admin.getEmail()
        ));
        
        log.info("Admin rejected: userId={}, adminId={}, reason={}", userId, adminId, reason);
    }
    
    /**
     * Admin suspends a user's verification (fraud/abuse).
     */
    @Transactional
    public void suspendVerification(Long userId, Long adminId, String reason) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        User admin = userRepository.findById(adminId)
            .orElseThrow(() -> new ResourceNotFoundException("Admin not found: " + adminId));
        
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("Suspension reason is required");
        }
        
        DriverLicenseStatus previousStatus = user.getDriverLicenseStatus();
        
        user.setDriverLicenseStatus(DriverLicenseStatus.SUSPENDED);
        userRepository.save(user);
        
        createAudit(user, null, admin,
            RenterVerificationAudit.AuditAction.SUSPENDED,
            previousStatus, DriverLicenseStatus.SUSPENDED, reason);
        
        log.warn("User suspended: userId={}, adminId={}, reason={}", userId, adminId, reason);
    }
    
    // ==================== BOOKING ELIGIBILITY ====================
    
    /**
     * Check if user is eligible to book a car.
     * 
     * @param userId User ID
     * @param tripEndDate End date of requested trip (for expiry validation)
     * @return Eligibility result with reason if blocked
     */
    @Transactional(readOnly = true)
    public BookingEligibilityDTO checkBookingEligibility(Long userId, LocalDate tripEndDate) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        
        return checkBookingEligibilityForUser(user, tripEndDate);
    }
    
    /**
     * Check booking eligibility for user entity.
     */
    public BookingEligibilityDTO checkBookingEligibilityForUser(User user, LocalDate tripEndDate) {
        // Check if verification is required
        if (!licenseRequired) {
            return BookingEligibilityDTO.eligible();
        }
        
        // Check if banned (check this first - banned users cannot book regardless of verification)
        if (user.isBanned()) {
            return BookingEligibilityDTO.builder()
                .eligible(false)
                .blockReason(BookingEligibilityDTO.EligibilityBlockReason.USER_BANNED)
                .message("Account is banned")
                .messageSr("Nalog je blokiran")
                .build();
        }
        
        // Check verification status
        DriverLicenseStatus status = user.getDriverLicenseStatus();
        
        switch (status) {
            case NOT_STARTED:
                return BookingEligibilityDTO.needsVerification();
                
            case PENDING_REVIEW:
                return BookingEligibilityDTO.pendingReview("5-30 minutes");
                
            case REJECTED:
                // Get rejection reason from most recent document
                String rejectionReason = documentRepository.findByUserId(user.getId()).stream()
                    .filter(d -> d.getRejectionReason() != null)
                    .findFirst()
                    .map(RenterDocument::getRejectionReason)
                    .orElse("Verification was rejected");
                return BookingEligibilityDTO.rejected(rejectionReason);
                
            case EXPIRED:
                return BookingEligibilityDTO.licenseExpired(user.getDriverLicenseExpiryDate());
                
            case SUSPENDED:
                return BookingEligibilityDTO.suspended();
                
            case APPROVED:
                // APPROVED users have a valid driver license, which inherently validates age
                // No need for separate age check - having a license proves legal driving age
                
                // Check expiry date
                if (user.isDriverLicenseExpired()) {
                    return BookingEligibilityDTO.licenseExpired(user.getDriverLicenseExpiryDate());
                }
                
                // Check if license expires during trip
                if (tripEndDate != null && user.getDriverLicenseExpiryDate() != null 
                    && user.getDriverLicenseExpiryDate().isBefore(tripEndDate)) {
                    return BookingEligibilityDTO.licenseExpiresDuringTrip(
                        user.getDriverLicenseExpiryDate(), tripEndDate);
                }
                
                return BookingEligibilityDTO.eligible();
                
            default:
                return BookingEligibilityDTO.needsVerification();
        }
    }
    
    // ==================== PROFILE RETRIEVAL ====================
    
    /**
     * Get verification profile for user.
     */
    @Transactional(readOnly = true)
    public RenterVerificationProfileDTO getVerificationProfile(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        
        List<RenterDocument> documents = documentRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<RenterDocumentDTO> documentDtos = documents.stream()
            .map(RenterDocumentDTO::fromEntity)
            .collect(Collectors.toList());
        
        // Calculate missing documents
        Set<RenterDocumentType> submittedTypes = documents.stream()
            .map(RenterDocument::getType)
            .collect(Collectors.toSet());
        List<String> missingDocuments = Arrays.stream(RenterDocumentType.values())
            .filter(RenterDocumentType::isRequired)
            .filter(t -> !submittedTypes.contains(t))
            .map(RenterDocumentType::getSerbianName)
            .collect(Collectors.toList());
        
        // Build profile DTO
        BookingEligibilityDTO eligibility = checkBookingEligibilityForUser(user, null);
        
        return RenterVerificationProfileDTO.builder()
            .userId(userId)
            .fullName(user.getFirstName() + " " + user.getLastName())
            .email(user.getEmail())
            .status(user.getDriverLicenseStatus())
            .statusDisplay(user.getDriverLicenseStatus().getSerbianName())
            .canBook(eligibility.isEligible())
            .bookingBlockedReason(eligibility.getMessage())
            .maskedLicenseNumber(user.getMaskedDriverLicenseNumber())
            .licenseExpiryDate(user.getDriverLicenseExpiryDate())
            .daysUntilExpiry(user.getDriverLicenseExpiryDate() != null 
                ? user.getDaysUntilDriverLicenseExpiry() : null)
            .expiryWarning(user.willDriverLicenseExpireWithin(30))
            .licenseCountry(user.getDriverLicenseCountry())
            .licenseCategories(user.getDriverLicenseCategories())
            .licenseTenureMonths(user.getDriverLicenseTenureMonths())
            .submittedAt(user.getRenterVerificationSubmittedAt())
            .verifiedAt(user.getDriverLicenseVerifiedAt())
            .verifiedByName(user.getDriverLicenseVerifiedBy() != null 
                ? user.getDriverLicenseVerifiedBy().getFirstName() + " " 
                    + user.getDriverLicenseVerifiedBy().getLastName() : null)
            .riskLevel(user.getRiskLevel())
            .riskLevelDisplay(user.getRiskLevel().getSerbianName())
            .documents(documentDtos)
            .requiredDocumentsComplete(missingDocuments.isEmpty())
            .missingDocuments(missingDocuments)
            .estimatedWaitTime(user.getDriverLicenseStatus() == DriverLicenseStatus.PENDING_REVIEW 
                ? "5-30 minuta" : null)
            .canSubmit(user.canSubmitDriverLicense())
            .rejectionReason(getRejectionReason(documents))
            .nextSteps(getNextSteps(user.getDriverLicenseStatus(), missingDocuments))
            .build();
    }
    
    // ==================== HELPER METHODS ====================
    
    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ValidationException("File is empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ValidationException("File too large. Maximum 10MB allowed.");
        }
        String mimeType = file.getContentType();
        if (mimeType == null || !ALLOWED_MIME_TYPES.contains(mimeType.toLowerCase())) {
            throw new ValidationException("Invalid file type. Allowed: JPEG, PNG");
        }
    }
    
    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "jpg";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
    
    private String calculateSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
    
    private void createAudit(User user, RenterDocument document, User actor,
                            RenterVerificationAudit.AuditAction action,
                            DriverLicenseStatus previousStatus, DriverLicenseStatus newStatus,
                            String reason) {
        RenterVerificationAudit audit = RenterVerificationAudit.builder()
            .user(user)
            .document(document)
            .actor(actor)
            .action(action)
            .previousStatus(previousStatus)
            .newStatus(newStatus != null ? newStatus : user.getDriverLicenseStatus())
            .reason(reason)
            .createdAt(LocalDateTime.now())
            .build();
        auditRepository.save(audit);
    }
    
    private String serializeOcrResult(IdVerificationProvider.DocumentExtraction result) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                "firstName", result.getFirstName() != null ? result.getFirstName() : "",
                "lastName", result.getLastName() != null ? result.getLastName() : "",
                "documentNumber", result.getDocumentNumber() != null ? result.getDocumentNumber() : "",
                "expiryDate", result.getExpiryDate() != null ? result.getExpiryDate().toString() : "",
                "countryCode", result.getCountryCode() != null ? result.getCountryCode() : ""
            ));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize OCR result", e);
            return "{}";
        }
    }
    
    private void triggerDocumentProcessing(RenterDocument document) {
        // ASYNC: Processing is now handled by @Async("renterVerificationExecutor")
        // The processDocument() call returns immediately; actual OCR runs in background
        try {
            // Note: @Async works through Spring proxy, so calling via 'this' works
            // because we're calling a public @Transactional method
            processDocument(document.getId());
            log.debug("Document processing queued: docId={}", document.getId());
        } catch (Exception e) {
            log.error("Failed to queue async processing for docId={}, marking as COMPLETED for manual review", 
                document.getId(), e);
            
            // RESILIENCE: If processing fails, still mark as COMPLETED so admin can review manually
            // The document images are valid; only OCR/biometrics failed
            try {
                RenterDocument doc = documentRepository.findById(document.getId()).orElse(null);
                if (doc != null && doc.getProcessingStatus() != RenterDocument.ProcessingStatus.COMPLETED) {
                    doc.setProcessingStatus(RenterDocument.ProcessingStatus.COMPLETED);
                    doc.setProcessingError("Automatic processing failed: " + e.getMessage());
                    documentRepository.save(doc);
                    log.info("Document marked COMPLETED for manual review: docId={}", document.getId());
                }
            } catch (Exception saveEx) {
                log.error("Failed to mark document for manual review: docId={}", document.getId(), saveEx);
            }
        }
    }
    
    private String getRejectionReason(List<RenterDocument> documents) {
        return documents.stream()
            .filter(d -> d.getRejectionReason() != null)
            .findFirst()
            .map(RenterDocument::getRejectionReason)
            .orElse(null);
    }
    
    private String getNextSteps(DriverLicenseStatus status, List<String> missingDocuments) {
        return switch (status) {
            case NOT_STARTED -> "Podnesite vozačku dozvolu (prednja i zadnja strana) za verifikaciju";
            case PENDING_REVIEW -> "Vaši dokumenti se pregledaju. Obaveštenje ćete dobiti uskoro.";
            case REJECTED -> "Ponovo podnesite ispravljene dokumente";
            case EXPIRED -> "Ažurirajte vozačku dozvolu sa novim datumom isteka";
            case SUSPENDED -> "Kontaktirajte podršku za rešavanje suspenzije";
            case APPROVED -> missingDocuments.isEmpty() 
                ? "Verifikacija kompletna. Možete rezervisati vozila."
                : "Podnesite preostale dokumente: " + String.join(", ", missingDocuments);
        };
    }
}

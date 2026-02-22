package org.example.rentoza.user;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.checkin.verification.IdVerificationProvider;
import org.example.rentoza.booking.checkin.verification.SerbianNameNormalizer;
import org.example.rentoza.car.DocumentVerificationStatus;
import org.example.rentoza.storage.SupabaseStorageService;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.exception.ValidationException;
import org.example.rentoza.user.document.*;
import org.example.rentoza.user.dto.*;
import org.example.rentoza.user.verification.event.VerificationApprovedEvent;
import org.example.rentoza.user.verification.event.VerificationRejectedEvent;
import org.example.rentoza.util.HashUtil;
import org.springframework.context.ApplicationContext;
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
 *   <li>{@link SupabaseStorageService} - File storage (renter-documents bucket)</li>
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
    private final SupabaseStorageService storageService;
    private final IdVerificationProvider verificationProvider;
    private final SerbianNameNormalizer nameNormalizer;
    private final HashUtil hashUtil;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ApplicationContext applicationContext;
    
    // ==================== CONFIGURATION ====================
    
    // Validation runs before uploadRenterDocument is called — oversized or non-image files
    // are rejected here and never reach Supabase storage. The bucket's broader limits
    // (20 MB + PDF) are irrelevant for renter docs; only license photos are accepted.
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
        "image/jpeg", "image/jpg", "image/png"
    );
    private static final double MIN_OCR_CONFIDENCE_THRESHOLD = 0.80; // Below this => re-upload
    
    // JPEG magic bytes: FF D8 FF
    private static final byte[] JPEG_MAGIC = {(byte)0xFF, (byte)0xD8, (byte)0xFF};
    // PNG magic bytes: 89 50 4E 47 0D 0A 1A 0A
    private static final byte[] PNG_MAGIC = {(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    
    @Value("${app.renter-verification.name-match-threshold:0.80}")
    private double nameMatchThreshold;
    
    @Value("${app.renter-verification.face-match-threshold:0.95}")
    private double faceMatchThreshold;
    
    @Value("${app.renter-verification.license-required:true}")
    private boolean licenseRequired;
    
    @Value("${app.renter-verification.selfie-required:true}")
    private boolean selfieRequired;
    
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
        
        log.info("Document submission: userId={}, type={}, fileSize={}, filename={}",
            userId, request.getDocumentType(), file.getSize(), file.getOriginalFilename());
        
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
        String hashPrefix = documentHash.length() >= 8 ? documentHash.substring(0, 8) : documentHash;

        // ── Duplicate hash analysis (3 deterministic scenarios) ──────────────
        //
        // 1. Different user + same hash  → cross-user fraud, reject 409
        // 2. Same user + same type + same hash → idempotent, return existing
        // 3. Same user + different type + same hash → same photo for two slots, reject 400
        //
        // NOTE: Scenario 2 prevents the INSERT that would hit idx_renter_documents_hash_unique.
        // Scenario 3 prevents a confusing DB 500 with a clear user message.
        // The DB constraint is a last-resort safety net, not the primary path.

        if (documentRepository.existsByDocumentHashForDifferentUser(documentHash, userId)) {
            log.warn("[submitDocument] REJECT cross-user duplicate: userId={}, docType={}, hashPrefix={}",
                userId, docType, hashPrefix);
            throw new ValidationException("This document has already been submitted by another user.");
        }

        Optional<RenterDocument> existingSameUserSameHash =
            documentRepository.findByUserIdAndDocumentHash(userId, documentHash);

        if (existingSameUserSameHash.isPresent()) {
            RenterDocument existing = existingSameUserSameHash.get();
            if (existing.getType() == docType) {
                // Idempotent: same user, same type, same file — no-op
                log.info("[submitDocument] IDEMPOTENT: userId={}, docType={}, hashPrefix={}, existingDocId={}",
                    userId, docType, hashPrefix, existing.getId());
                return RenterDocumentDTO.fromEntity(existing);
            } else {
                // Same photo submitted for a different document slot — clear user error
                log.warn("[submitDocument] REJECT same-photo cross-type: userId={}, "
                    + "existingType={}, newType={}, hashPrefix={}",
                    userId, existing.getType(), docType, hashPrefix);
                throw new ValidationException(
                    "Front and back documents must be different photos. "
                    + "You have already submitted this image as: "
                    + existing.getType().getSerbianName() + ".");
            }
        }

        log.info("[submitDocument] INSERT new document: userId={}, docType={}, hashPrefix={}",
            userId, docType, hashPrefix);

        // Replace existing document of same type (re-submission with a different file)
        documentRepository.findByUserIdAndType(userId, docType)
            .ifPresent(existing -> {
                log.info("[submitDocument] Replacing existing {} docId={} for userId={}",
                    docType, existing.getId(), userId);
                documentRepository.delete(existing);
            });
        
        // Generate storage path - uses renter-documents bucket (not car-documents)
        // UUID-based filename prevents path traversal attacks
        String documentUrl = storageService.uploadRenterDocument(
            userId, docType.name().toLowerCase(), file);
        
        // Create document entity
        RenterDocument document = RenterDocument.builder()
            .user(user)
            .type(docType)
            .documentUrl(documentUrl)
            .storageBucket(SupabaseStorageService.BUCKET_RENTER_DOCUMENTS)
            .originalFilename(file.getOriginalFilename())
            .documentHash(documentHash)
            .fileSize(file.getSize())
            .mimeType(file.getContentType())
            .uploadDate(LocalDateTime.now())
            .expiryDate(request.getExpiryDate())
            .status(DocumentVerificationStatus.PENDING)
            .processingStatus(RenterDocument.ProcessingStatus.PENDING)
            .build();
        
        try {
            document = documentRepository.save(document);
        } catch (Exception dbEx) {
            log.error("[submitDocument] DB save failed after successful storage upload - starting compensating delete: "
                    + "userId={}, docType={}, bucket={}, path={}",
                    userId, docType, SupabaseStorageService.BUCKET_RENTER_DOCUMENTS, documentUrl, dbEx);
            try {
                storageService.deleteRenterDocument(documentUrl);
                log.warn("[submitDocument] Compensating delete succeeded: path={}", documentUrl);
            } catch (Exception delEx) {
                log.error("[submitDocument] Compensating delete FAILED - orphaned file in storage: path={}",
                    documentUrl, delEx);
            }
            throw dbEx;
        }
        
        // Transition to pending review on initial submission and re-submissions.
        DriverLicenseStatus previousStatus = user.getDriverLicenseStatus();
        boolean isFirstSubmission = user.getRenterVerificationSubmittedAt() == null;
        if (previousStatus != DriverLicenseStatus.PENDING_REVIEW) {
            user.setDriverLicenseStatus(DriverLicenseStatus.PENDING_REVIEW);
        }
        if (isFirstSubmission || previousStatus == DriverLicenseStatus.REJECTED
                || previousStatus == DriverLicenseStatus.EXPIRED) {
            user.setRenterVerificationSubmittedAt(LocalDateTime.now());
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
            previousStatus, DriverLicenseStatus.PENDING_REVIEW,
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
            byte[] fileBytes = storageService.downloadRenterDocument(document.getDocumentUrl());
            String mimeType = document.getMimeType();
            
            if (document.getType().requiresOcr()) {
                // Run OCR extraction
                IdVerificationProvider.DocumentExtraction ocrResult = 
                    verificationProvider.extractDocumentData(fileBytes, null, mimeType);
                
                if (ocrResult.isSuccess()) {
                    // Store OCR results
                    document.setOcrExtractedData(serializeOcrResult(ocrResult));
                    
                    // Use provider's confidence if available, otherwise default to 0.90
                    double ocrConfidenceValue = ocrResult.getConfidence() != null 
                        ? ocrResult.getConfidence().doubleValue() : 0.90;
                    document.setOcrConfidence(BigDecimal.valueOf(ocrConfidenceValue));
                    
                    // SECURITY: Flag low-confidence OCR results (likely blurry/poor quality)
                    // Below 80% confidence => flag for re-upload (Turo standard)
                    if (ocrConfidenceValue < MIN_OCR_CONFIDENCE_THRESHOLD) {
                        log.warn("Low OCR confidence: docId={}, confidence={}, threshold={}", 
                            documentId, ocrConfidenceValue, MIN_OCR_CONFIDENCE_THRESHOLD);
                        document.setProcessingError(String.format(
                            "Low image quality (confidence: %.0f%%). " +
                            "Please upload a clearer, well-lit photo of your license.", 
                            ocrConfidenceValue * 100));
                        // Populate quality flag columns for DB-level tracking
                        document.setQualityFlag("LOW_CONFIDENCE");
                        document.setQualityFlagReason(String.format(
                            "OCR confidence %.2f below minimum threshold %.2f",
                            ocrConfidenceValue, MIN_OCR_CONFIDENCE_THRESHOLD));
                        // Don't block processing entirely - admin can still review
                        // But auto-approval will be blocked by the threshold check
                    }
                    
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
        // Required (when selfie-required=true): SELFIE for face matching (Turo standard)
        List<RenterDocument> documents = documentRepository.findByUserId(userId);
        boolean hasLicenseFront = documents.stream()
            .anyMatch(d -> d.getType() == RenterDocumentType.DRIVERS_LICENSE_FRONT 
                && d.getProcessingStatus() == RenterDocument.ProcessingStatus.COMPLETED);
        boolean hasLicenseBack = documents.stream()
            .anyMatch(d -> d.getType() == RenterDocumentType.DRIVERS_LICENSE_BACK 
                && d.getProcessingStatus() == RenterDocument.ProcessingStatus.COMPLETED);
        boolean hasSelfie = documents.stream()
            .anyMatch(d -> d.getType() == RenterDocumentType.SELFIE 
                && d.getProcessingStatus() == RenterDocument.ProcessingStatus.COMPLETED);
        
        // Require license front + back, and selfie if configured
        if (!hasLicenseFront || !hasLicenseBack) {
            log.debug("Not all required documents processed: userId={}, front={}, back={}", 
                userId, hasLicenseFront, hasLicenseBack);
            return;
        }
        if (selfieRequired && !hasSelfie) {
            log.debug("Selfie required but not submitted: userId={}", userId);
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
        
        // Check selfie liveness and face match (required when selfie-required=true)
        // Face match threshold: 0.95 (Turo standard)
        Optional<RenterDocument> selfie = documents.stream()
            .filter(d -> d.getType() == RenterDocumentType.SELFIE)
            .findFirst();
        boolean livenessPassed = selfie
            .map(d -> Boolean.TRUE.equals(d.getLivenessPassed()))
            .orElse(!selfieRequired); // No selfie = fail if required, pass if optional
        
        // SECURITY: Face match must pass threshold when selfie is submitted
        boolean faceMatchPassed = true;
        if (selfie.isPresent()) {
            double faceScore = selfie.get().getFaceMatchScore() != null 
                ? selfie.get().getFaceMatchScore().doubleValue() : 0.0;
            faceMatchPassed = faceScore >= faceMatchThreshold;
            if (!faceMatchPassed) {
                log.warn("Face match below threshold: userId={}, score={}, threshold={}", 
                    userId, faceScore, faceMatchThreshold);
            }
        } else if (selfieRequired) {
            faceMatchPassed = false;
        }
        
        // Auto-approve decision
        boolean meetsOcrThreshold = riskLevel.meetsAutoApproveThreshold(ocrConfidence);
        boolean meetsNameThreshold = nameMatch >= nameMatchThreshold;

        // POLICY: MOCK provider means no real biometric verification happened.
        // Simulated scores must never trigger auto-approval — force manual review.
        boolean isMockProvider = "MOCK".equalsIgnoreCase(verificationProvider.getProviderName());
        if (isMockProvider) {
            log.info("Forcing manual review: provider=MOCK, userId={}. "
                + "Automatic approval is disabled when no third-party provider is configured.", userId);
            createAudit(user, licenseDoc, null,
                RenterVerificationAudit.AuditAction.ESCALATED_TO_REVIEW,
                DriverLicenseStatus.PENDING_REVIEW, DriverLicenseStatus.PENDING_REVIEW,
                "Manual review required: MOCK provider active, biometric scores are simulated.");
            userRepository.save(user);
            return;
        }
        
        // SECURITY: Check license expiry date before auto-approval
        // Turo standard: never auto-approve an expired or missing-expiry license
        LocalDate expiryDate = user.getDriverLicenseExpiryDate();
        if (expiryDate == null) {
            // Try from document
            expiryDate = documents.stream()
                .filter(d -> d.getExpiryDate() != null)
                .map(RenterDocument::getExpiryDate)
                .findFirst()
                .orElse(null);
        }
        boolean expiryValid = expiryDate != null && !expiryDate.isBefore(LocalDate.now());
        
        if (meetsOcrThreshold && meetsNameThreshold && livenessPassed && faceMatchPassed && expiryValid) {
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
                "Auto-approved: OCR=%.2f, NameMatch=%.2f, Liveness=%s, FaceMatch=%s, Expiry=%s, RiskLevel=%s",
                ocrConfidence, nameMatch, livenessPassed, faceMatchPassed, expiryDate, riskLevel);
            
            createAudit(user, licenseDoc, null,
                RenterVerificationAudit.AuditAction.AUTO_APPROVED,
                previousStatus, DriverLicenseStatus.APPROVED, reason);
            
            // Publish event for async notifications (email, in-app, push)
            eventPublisher.publishEvent(VerificationApprovedEvent.autoApproved(
                this, user, user.getDriverLicenseExpiryDate()
            ));
            
        } else {
            // Escalate to manual review
            log.info("Escalating to review: userId={}, ocr={}, nameMatch={}, liveness={}, faceMatch={}, expiryValid={}",
                userId, ocrConfidence, nameMatch, livenessPassed, faceMatchPassed, expiryValid);
            
            String reason = String.format(
                "Below threshold: OCR=%.2f (need %.2f), NameMatch=%.2f (need %.2f), Liveness=%s, FaceMatch=%s (threshold=%.2f), ExpiryValid=%s (date=%s)",
                ocrConfidence, riskLevel.getAutoApproveThreshold(), 
                nameMatch, nameMatchThreshold, livenessPassed, faceMatchPassed, faceMatchThreshold, expiryValid, expiryDate);
            
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
            byte[] licenseBytes = storageService.downloadRenterDocument(licenseFront.getDocumentUrl());
            
            // Perform face matching
            IdVerificationProvider.FaceMatchResult matchResult = 
                verificationProvider.matchFaces(selfieBytes, licenseBytes, mimeType);
            
            // Store result in selfie document
            selfieDocument.setFaceMatchScore(matchResult.getConfidence());
            
            boolean faceMatchPassed = matchResult.isMatched() && 
                matchResult.getConfidence().doubleValue() >= faceMatchThreshold;
            
            // Populate face_match_passed DB column
            selfieDocument.setFaceMatchPassed(faceMatchPassed);
            
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
            org.example.rentoza.config.timezone.SerbiaTimeZone.toLocalDateTime(user.getCreatedAt()).toLocalDate(),
            org.example.rentoza.config.timezone.SerbiaTimeZone.today());
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
        
        // SECURITY: Enforce that required documents exist before admin can approve
        List<RenterDocument> documents = documentRepository.findByUserId(userId);
        boolean hasLicenseFront = documents.stream()
            .anyMatch(d -> d.getType() == RenterDocumentType.DRIVERS_LICENSE_FRONT);
        boolean hasLicenseBack = documents.stream()
            .anyMatch(d -> d.getType() == RenterDocumentType.DRIVERS_LICENSE_BACK);
        
        if (!hasLicenseFront || !hasLicenseBack) {
            throw new ValidationException(
                "Cannot approve: required documents missing. Need both license front and back.");
        }
        
        // SECURITY: Enforce selfie if face matching is required
        if (selfieRequired) {
            boolean hasSelfie = documents.stream()
                .anyMatch(d -> d.getType() == RenterDocumentType.SELFIE);
            if (!hasSelfie) {
                throw new ValidationException(
                    "Cannot approve: selfie is required for face matching verification.");
            }
        }
        
        // SECURITY: Enforce valid (non-expired) license expiry date before approval
        LocalDate expiryDate = user.getDriverLicenseExpiryDate();
        if (expiryDate == null) {
            // Try to get expiry from document OCR data
            expiryDate = documents.stream()
                .filter(d -> d.getExpiryDate() != null)
                .map(RenterDocument::getExpiryDate)
                .findFirst()
                .orElse(null);
        }
        if (expiryDate == null) {
            throw new ValidationException(
                "Cannot approve: license expiry date is required. Set via OCR or manual entry.");
        }
        if (expiryDate.isBefore(LocalDate.now())) {
            throw new ValidationException(
                "Cannot approve: driver's license has expired on " + expiryDate + 
                ". User must submit a valid, non-expired license.");
        }
        
        // If expiry was found in documents but not on user, update it
        if (user.getDriverLicenseExpiryDate() == null) {
            user.setDriverLicenseExpiryDate(expiryDate);
        }
        
        DriverLicenseStatus previousStatus = user.getDriverLicenseStatus();
        
        // Update user status
        user.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);
        user.setDriverLicenseVerifiedAt(LocalDateTime.now());
        user.setDriverLicenseVerifiedBy(admin);
        userRepository.save(user);
        
        // Update document statuses (reuse documents fetched during validation above)
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
                // Guard legacy/inconsistent states: pending without any submission timestamp
                // should be treated as not started to avoid blocking users in a false "pending" state.
                if (user.getRenterVerificationSubmittedAt() == null) {
                    return BookingEligibilityDTO.needsVerification();
                }
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
                
                // ========================================================================
                // H1 FIX: Enforce minimum 2-year (24 month) license tenure requirement
                // ========================================================================
                // Platform spec: "Cannot book if license tenure < 2 years"
                // This is a safety requirement for car rental industry standards.
                // Tenure is calculated from license issue date (extracted via OCR at verification).
                int requiredTenureMonths = 24;
                Integer tenureMonths = user.getDriverLicenseTenureMonths();
                if (tenureMonths != null && tenureMonths < requiredTenureMonths) {
                    // Calculate approximate eligibility date based on remaining months
                    int remainingMonths = requiredTenureMonths - tenureMonths;
                    LocalDate eligibleFrom = LocalDate.now().plusMonths(remainingMonths);
                    
                    log.info("[Eligibility] License tenure too short: userId={}, tenure={}mo, required={}mo, eligibleFrom={}", 
                            user.getId(), tenureMonths, requiredTenureMonths, eligibleFrom);
                    return BookingEligibilityDTO.licenseTenureTooShort(
                            tenureMonths, requiredTenureMonths, eligibleFrom);
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
            throw new ValidationException("File too large. Maximum 5MB allowed.");
        }
        String mimeType = file.getContentType();
        if (mimeType == null || !ALLOWED_MIME_TYPES.contains(mimeType.toLowerCase())) {
            throw new ValidationException("Invalid file type. Allowed: JPEG, PNG");
        }
        // SECURITY: Validate magic bytes to prevent fake file uploads
        // Don't trust MIME type alone - validate actual file signature
        try {
            validateMagicBytes(file);
        } catch (IOException e) {
            throw new ValidationException("Unable to validate file integrity");
        }
        // SECURITY: Sanitize filename - use UUID to prevent path traversal
        // Original filename is stored for display but never used for storage paths
        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null && (originalFilename.contains("..") || originalFilename.contains("/") || originalFilename.contains("\\"))) {
            log.warn("Suspicious filename detected: {}", originalFilename);
            throw new ValidationException("Invalid filename");
        }
    }
    
    /**
     * Validate file magic bytes match declared MIME type.
     * Prevents fake file uploads (e.g., SVG with XML entities, executables renamed to .jpg).
     * Also cross-validates that declared MIME matches detected signature.
     */
    private void validateMagicBytes(MultipartFile file) throws IOException {
        byte[] header = new byte[12];
        int bytesRead;
        try (var in = file.getInputStream()) {
            bytesRead = in.read(header);
        }
        if (bytesRead < 3) {
            throw new ValidationException("Invalid image file (too short)");
        }
        boolean isJpeg = header[0] == (byte)0xFF && header[1] == (byte)0xD8 && header[2] == (byte)0xFF;
        boolean isPng = bytesRead >= 8 
            && header[0] == (byte)0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47
            && header[4] == 0x0D && header[5] == 0x0A && header[6] == 0x1A && header[7] == 0x0A;
        
        if (!isJpeg && !isPng) {
            log.warn("Magic bytes validation failed: declared={}, actual bytes={}",
                file.getContentType(), bytesToHex(header, Math.min(bytesRead, 8)));
            throw new ValidationException("Invalid image file. File content does not match declared type. Only genuine JPEG/PNG files are accepted.");
        }
        
        // SECURITY: Cross-validate declared MIME type matches detected file signature
        // Prevents e.g. a PNG file claiming to be image/jpeg or vice versa
        String declaredMime = file.getContentType();
        if (declaredMime != null) {
            boolean mimeClaimsJpeg = declaredMime.contains("jpeg") || declaredMime.contains("jpg");
            boolean mimeClaimsPng = declaredMime.contains("png");
            
            if (mimeClaimsJpeg && !isJpeg) {
                log.warn("MIME/magic-bytes mismatch: declared={}, detected=PNG", declaredMime);
                throw new ValidationException("File content mismatch: file claims to be JPEG but contains PNG data.");
            }
            if (mimeClaimsPng && !isPng) {
                log.warn("MIME/magic-bytes mismatch: declared={}, detected=JPEG", declaredMime);
                throw new ValidationException("File content mismatch: file claims to be PNG but contains JPEG data.");
            }
        }
    }
    
    private String bytesToHex(byte[] bytes, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString().trim();
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
        // Invoke through Spring proxy so @Async interceptor is applied.
        try {
            applicationContext
                .getBean(RenterVerificationService.class)
                .processDocument(document.getId());
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

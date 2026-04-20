package org.example.rentoza.car;

import org.example.rentoza.admin.dto.DocumentReviewDto;
import org.example.rentoza.booking.util.FileSignatureValidator;
import org.example.rentoza.config.timezone.SerbiaTimeZone;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.exception.ValidationException;
import org.example.rentoza.storage.SupabaseStorageService;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Service for car document management (Serbian compliance).
 * 
 * <p>All documents are stored in Supabase Storage.
 * 
 * <p>Handles:
 * <ul>
 *   <li>Document upload with validation</li>
 *   <li>Admin verification workflow</li>
 *   <li>Document expiry tracking</li>
 * </ul>
 */
@Service
public class CarDocumentService {
    
    private static final Logger log = LoggerFactory.getLogger(CarDocumentService.class);
    
    // 10 MB max file size
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    
    // Allowed MIME types
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
        "application/pdf",
        "image/jpeg",
        "image/jpg",
        "image/png"
    );
    
    private final CarDocumentRepository documentRepository;
    private final CarRepository carRepository;
    private final SupabaseStorageService supabaseStorageService;
    
    @Autowired
    public CarDocumentService(
            CarDocumentRepository documentRepository,
            CarRepository carRepository,
            SupabaseStorageService supabaseStorageService) {
        this.documentRepository = documentRepository;
        this.carRepository = carRepository;
        this.supabaseStorageService = supabaseStorageService;
    }
    
    // ==================== DOCUMENT UPLOAD ====================
    
    /**
     * Upload document for car.
     * 
     * @param carId Car ID
     * @param type Document type
     * @param file Uploaded file
     * @param expiryDate When document expires
     * @param uploader Owner uploading document
     * @return Created document DTO
     */
    @Transactional
    public CarDocument uploadDocument(
            Long carId, 
            DocumentType type, 
            MultipartFile file, 
            LocalDate expiryDate,
            User uploader) throws IOException {
        
        // Validate car exists and uploader is owner.
        // Acquire a write lock on the car row to prevent concurrent document uploads
        // from deadlocking while updating expiry dates / optimistic-lock version.
        Car car = carRepository.findByIdForUpdate(carId)
            .orElseThrow(() -> new ResourceNotFoundException("Car not found: " + carId));
        
        if (!car.getOwner().getId().equals(uploader.getId())) {
            throw new ValidationException("Only car owner can upload documents");
        }
        
        // Validate file
        String normalizedMimeType = validateFile(file);

        invalidateComplianceVerification(car);

        // Serbian compliance: required documents MUST have an explicit expiry date
        if (type == DocumentType.REGISTRATION
                || type == DocumentType.TECHNICAL_INSPECTION
                || type == DocumentType.LIABILITY_INSURANCE) {
            if (expiryDate == null) {
                throw new ValidationException("Expiry date is required for " + type);
            }
        }
        
        LocalDate effectiveExpiry = expiryDate;
        
        // Check if replacing existing document
        DocumentVerificationStatus previousStatus = null;
        documentRepository.findByCarIdAndType(carId, type)
            .ifPresent(existing -> {
                log.info("Replacing existing {} for carId={}", type, carId);
                documentRepository.delete(existing);
            });
        
        // Upload to Supabase Storage
        String documentUrl;
        // Supabase path: cars/{carId}/documents/{docType}/{filename}
        documentUrl = supabaseStorageService.uploadCarDocument(carId, type.name(), file);
        log.debug("Uploaded to Supabase: {}", documentUrl);
        
        // Calculate file hash for integrity
        String hash = calculateSha256(file.getBytes());
        
        // Create document entity
        CarDocument document = CarDocument.builder()
            .car(car)
            .type(type)
            .documentUrl(documentUrl)
            .originalFilename(file.getOriginalFilename())
            .documentHash(hash)
            .fileSize(file.getSize())
            .mimeType(normalizedMimeType)
            .uploadDate(SerbiaTimeZone.now())
            .expiryDate(effectiveExpiry)
            .status(DocumentVerificationStatus.PENDING)
            .build();
        
        document = documentRepository.save(document);
        
        // Update car expiry dates
        updateCarExpiryDates(car, type, effectiveExpiry);
        
        log.info("Document uploaded: carId={}, type={}, docId={}", carId, type, document.getId());
        
        return document;
    }
    
    // ==================== ADMIN VERIFICATION ====================
    
    /**
     * Admin verifies document is valid.
     * 
     * @param documentId Document ID
     * @param admin Admin performing verification
     */
    @Transactional
    public void verifyDocument(Long documentId, User admin) {
        CarDocument document = documentRepository.findById(documentId)
            .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));
        
        if (document.isExpired()) {
            throw new ValidationException("Cannot verify expired document");
        }
        
        document.setStatus(DocumentVerificationStatus.VERIFIED);
        document.setVerifiedBy(admin);
        document.setVerifiedAt(SerbiaTimeZone.now());
        document.setRejectionReason(null);
        
        documentRepository.save(document);
        
        // Update car document verification timestamp
        Car car = document.getCar();
        car.setDocumentsVerifiedAt(SerbiaTimeZone.now());
        car.setDocumentsVerifiedBy(admin);
        carRepository.save(car);
        
        log.info("Document verified: docId={} by adminId={}", documentId, admin.getId());
    }
    
    /**
     * Admin rejects document with reason.
     * 
     * @param documentId Document ID
     * @param reason Rejection reason
     * @param admin Admin performing rejection
     */
    @Transactional
    public void rejectDocument(Long documentId, String reason, User admin) {
        CarDocument document = documentRepository.findById(documentId)
            .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));
        
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("Rejection reason is required");
        }
        
        document.setStatus(DocumentVerificationStatus.REJECTED);
        document.setVerifiedBy(admin);
        document.setVerifiedAt(SerbiaTimeZone.now());
        document.setRejectionReason(reason);
        
        documentRepository.save(document);
        
        log.warn("Document rejected: docId={}, reason='{}', by adminId={}", 
            documentId, reason, admin.getId());
    }
    
    // ==================== QUERIES ====================
    
    /**
     * Get document by ID.
     * 
     * @param documentId Document ID
     * @return Document entity
     */
    @Transactional(readOnly = true)
    public CarDocument getDocumentById(Long documentId) {
        return documentRepository.findById(documentId)
            .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));
    }

    /**
     * Get a document for admin review with all required associations initialized.
     *
     * <p>CRITICAL: Admin controllers must return DTOs and must not map detached
     * entities that still contain lazy proxies.
     */
    @Transactional(readOnly = true)
    public DocumentReviewDto getDocumentReview(Long documentId) {
        CarDocument doc = documentRepository.findByIdWithVerifiedBy(documentId)
            .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));
        return DocumentReviewDto.fromEntity(doc);
    }

    /**
     * Get all documents for a car with verifier eagerly loaded.
     *
     * <p>Used by admin review-detail screens to avoid N+1 queries.
     */
    @Transactional(readOnly = true)
    public List<CarDocument> getDocumentsForCarWithVerifiedBy(Long carId) {
        return documentRepository.findByCarIdWithVerifiedBy(carId);
    }

    /**
     * Read the stored document bytes from Supabase Storage.
     *
     * <p>All documents are stored in Supabase Storage.
     * Never leaks filesystem paths in errors.
     */
    @Transactional(readOnly = true)
    public byte[] getDocumentContent(CarDocument document) {
        if (document == null) {
            throw new ResourceNotFoundException("Document not found");
        }
        try {
            log.debug("Loading document from Supabase: {}", document.getDocumentUrl());
            return supabaseStorageService.downloadCarDocument(document.getDocumentUrl());
        } catch (IOException | RuntimeException e) {
            log.error("Failed to load document content: {}", e.getMessage());
            throw new ResourceNotFoundException("Document file not found");
        }
    }
    
    /**
     * Get all documents for a car.
     */
    @Transactional(readOnly = true)
    public List<CarDocument> getDocumentsForCar(Long carId) {
        return documentRepository.findByCarId(carId);
    }

    /**
     * Get all documents for a car after owner/admin authorization.
     */
    @Transactional(readOnly = true)
    public List<CarDocument> getDocumentsForCar(Long carId, User requester) {
        verifyDocumentMetadataAccess(carId, requester);
        return getDocumentsForCar(carId);
    }
    
    /**
     * Get all pending documents for admin review.
     */
    @Transactional(readOnly = true)
    public List<CarDocument> getPendingDocuments() {
        return documentRepository.findByStatus(DocumentVerificationStatus.PENDING);
    }
    
    /**
     * Check if car has all required documents verified.
     */
    @Transactional(readOnly = true)
    public boolean hasAllRequiredDocumentsVerified(Long carId) {
        // 3 required documents: REGISTRATION, TECHNICAL_INSPECTION, LIABILITY_INSURANCE
        long count = documentRepository.countRequiredVerifiedDocuments(carId);
        return count >= 3;
    }

    @Transactional(readOnly = true)
    public boolean hasAllRequiredDocumentsVerified(Long carId, User requester) {
        verifyDocumentMetadataAccess(carId, requester);
        return hasAllRequiredDocumentsVerified(carId);
    }
    
    // ==================== HELPER METHODS ====================
    
    private String validateFile(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new ValidationException("File is empty");
        }
        
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ValidationException("File too large. Maximum 10MB allowed.");
        }
        
        String mimeType = normalizeMimeType(file.getContentType());
        if (mimeType == null || !ALLOWED_MIME_TYPES.contains(mimeType)) {
            throw new ValidationException("Invalid file type. Allowed: PDF, JPEG, PNG");
        }

        try {
            FileSignatureValidator.validateImageSignature(file.getBytes(), mimeType);
        } catch (IllegalArgumentException ex) {
            throw new ValidationException(ex.getMessage());
        }

        return mimeType;
    }

    private String normalizeMimeType(String mimeType) {
        if (mimeType == null) {
            return null;
        }
        return mimeType.toLowerCase().split(";")[0].trim();
    }

    private void verifyDocumentMetadataAccess(Long carId, User requester) {
        Car car = carRepository.findById(carId)
                .orElseThrow(() -> new ResourceNotFoundException("Car not found: " + carId));

        boolean isOwner = requester != null && car.getOwner() != null
                && car.getOwner().getId().equals(requester.getId());
        boolean isAdmin = requester != null && requester.getRole() == Role.ADMIN;

        if (!isOwner && !isAdmin) {
            throw new AccessDeniedException("Unauthorized to access car document metadata");
        }
    }

    private void invalidateComplianceVerification(Car car) {
        car.setDocumentsVerifiedAt(null);
        car.setDocumentsVerifiedBy(null);

        if (car.getListingStatus() == ListingStatus.APPROVED || car.getListingStatus() == ListingStatus.REJECTED) {
            car.setListingStatus(ListingStatus.PENDING_APPROVAL);
            car.setApprovalStatus(ApprovalStatus.PENDING);
            car.setApprovedAt(null);
            car.setApprovedBy(null);
            car.setRejectionReason(null);
        }

        car.setAvailable(false);
    }
    
    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "bin";
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
    
    private void updateCarExpiryDates(Car car, DocumentType type, LocalDate expiryDate) {
        switch (type) {
            case REGISTRATION:
                car.setRegistrationExpiryDate(expiryDate);
                break;
            case TECHNICAL_INSPECTION:
                car.setTechnicalInspectionExpiryDate(expiryDate);
                if (expiryDate != null) {
                    car.setTechnicalInspectionDate(expiryDate.minusMonths(6));
                }
                break;
            case LIABILITY_INSURANCE:
                car.setInsuranceExpiryDate(expiryDate);
                break;
            default:
                break;
        }
        carRepository.save(car);
    }
}

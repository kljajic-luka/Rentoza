package org.example.rentoza.user.document;

import org.example.rentoza.car.DocumentVerificationStatus;
import org.example.rentoza.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for renter document operations.
 * 
 * <p>Mirrors {@link org.example.rentoza.car.CarDocumentRepository} patterns.
 */
@Repository
public interface RenterDocumentRepository extends JpaRepository<RenterDocument, Long> {
    
    // ==================== BASIC QUERIES ====================
    
    /**
     * Find all documents for a user.
     */
    List<RenterDocument> findByUserId(Long userId);
    
    /**
     * Find all documents for a user ordered by creation time.
     */
    List<RenterDocument> findByUserIdOrderByCreatedAtDesc(Long userId);
    
    /**
     * Find specific document type for a user.
     */
    Optional<RenterDocument> findByUserIdAndType(Long userId, RenterDocumentType type);
    
    /**
     * Find all documents of specific type for a user.
     */
    List<RenterDocument> findByUserIdAndTypeIn(Long userId, List<RenterDocumentType> types);
    
    /**
     * Find document by user and type with verification status.
     */
    Optional<RenterDocument> findByUserIdAndTypeAndStatus(
        Long userId, 
        RenterDocumentType type, 
        DocumentVerificationStatus status
    );
    
    // ==================== ADMIN REVIEW QUERIES ====================
    
    /**
     * Find all documents pending admin review.
     * Ordered by creation date (oldest first - FIFO queue).
     */
    /**
     * Find pending documents awaiting manual review.
     * Includes documents with COMPLETED processing (normal flow)
     * AND documents with PENDING/FAILED processing (for admin visibility into stuck/failed documents).
     * 
     * Admins can manually review even if OCR failed - the images are still viewable.
     */
    @Query("""
        SELECT d FROM RenterDocument d 
        JOIN FETCH d.user
        WHERE d.status = 'PENDING' 
        AND d.processingStatus IN ('COMPLETED', 'PENDING', 'FAILED')
        ORDER BY d.createdAt ASC
        """)
    List<RenterDocument> findPendingManualReview();
    
    /**
     * Find pending documents with pagination.
     * Uses JOIN FETCH to eagerly load user to avoid LazyInitializationException.
     * 
     * Includes all processing statuses except PROCESSING (actively being processed).
     * This ensures admins see:
     * - COMPLETED: Normal documents ready for review
     * - PENDING: Documents where async processing hasn't started/is disabled
     * - FAILED: Documents where OCR/biometrics failed (admin can still verify manually)
     */
    @Query("""
        SELECT d FROM RenterDocument d 
        JOIN FETCH d.user
        WHERE d.status = 'PENDING' 
        AND d.processingStatus IN ('COMPLETED', 'PENDING', 'FAILED')
        """)
    List<RenterDocument> findPendingManualReviewPaged(org.springframework.data.domain.Pageable pageable);
    
    /**
     * Count documents pending review (for queue stats).
     * Includes all processing statuses for accurate admin visibility.
     */
    @Query("""
        SELECT COUNT(d) FROM RenterDocument d 
        WHERE d.status = 'PENDING' 
        AND d.processingStatus IN ('COMPLETED', 'PENDING', 'FAILED')
        """)
    long countPendingReview();
    
    /**
     * Find documents by verification status.
     */
    List<RenterDocument> findByStatus(DocumentVerificationStatus status);
    
    // ==================== PROCESSING QUERIES ====================
    
    /**
     * Find documents awaiting processing.
     */
    List<RenterDocument> findByProcessingStatus(RenterDocument.ProcessingStatus status);
    
    /**
     * Find documents stuck in processing (for retry).
     */
    @Query("""
        SELECT d FROM RenterDocument d 
        WHERE d.processingStatus = 'PROCESSING' 
        AND d.updatedAt < :cutoff
        """)
    List<RenterDocument> findStuckProcessing(@Param("cutoff") LocalDateTime cutoff);
    
    // ==================== EXPIRY QUERIES ====================
    
    /**
     * Find documents expiring within N days.
     * For proactive notification/re-verification.
     */
    @Query("""
        SELECT d FROM RenterDocument d 
        WHERE d.expiryDate IS NOT NULL 
        AND d.expiryDate <= :warningDate 
        AND d.expiryDate > :today
        AND d.status = 'VERIFIED'
        """)
    List<RenterDocument> findExpiringDocuments(
        @Param("warningDate") LocalDate warningDate,
        @Param("today") LocalDate today
    );
    
    /**
     * Find expired documents that need status update.
     */
    @Query("""
        SELECT d FROM RenterDocument d 
        WHERE d.expiryDate IS NOT NULL 
        AND d.expiryDate < :today
        AND d.status = 'VERIFIED'
        """)
    List<RenterDocument> findExpiredDocuments(@Param("today") LocalDate today);
    
    // ==================== DUPLICATE DETECTION ====================
    
    /**
     * Check if document hash already exists (duplicate detection).
     */
    boolean existsByDocumentHash(String documentHash);
    
    /**
     * Check if document hash exists for different user (cross-user fraud detection).
     */
    @Query("""
        SELECT CASE WHEN COUNT(d) > 0 THEN true ELSE false END 
        FROM RenterDocument d 
        WHERE d.documentHash = :hash 
        AND d.user.id != :userId
        """)
    boolean existsByDocumentHashForDifferentUser(
        @Param("hash") String hash, 
        @Param("userId") Long userId
    );

    /**
     * Find any document belonging to a specific user with the given hash.
     * Used for same-user duplicate detection: idempotency vs cross-type same-photo guard.
     */
    @Query("""
        SELECT d FROM RenterDocument d 
        WHERE d.user.id = :userId 
        AND d.documentHash = :hash
        """)
    Optional<RenterDocument> findByUserIdAndDocumentHash(
        @Param("userId") Long userId,
        @Param("hash") String hash
    );
    
    // ==================== VERIFICATION CHECKS ====================
    
    /**
     * Check if user has verified driver's license documents.
     */
    @Query("""
        SELECT CASE WHEN COUNT(d) >= 2 THEN true ELSE false END 
        FROM RenterDocument d 
        WHERE d.user.id = :userId 
        AND d.type IN ('DRIVERS_LICENSE_FRONT', 'DRIVERS_LICENSE_BACK')
        AND d.status = 'VERIFIED'
        """)
    boolean hasVerifiedDriversLicense(@Param("userId") Long userId);
    
    /**
     * Check if user has submitted all required documents.
     */
    @Query("""
        SELECT CASE WHEN COUNT(DISTINCT d.type) >= 3 THEN true ELSE false END 
        FROM RenterDocument d 
        WHERE d.user.id = :userId 
        AND d.type IN ('DRIVERS_LICENSE_FRONT', 'DRIVERS_LICENSE_BACK', 'SELFIE')
        """)
    boolean hasSubmittedRequiredDocuments(@Param("userId") Long userId);
    
    /**
     * Get count of documents by status for a user.
     */
    @Query("""
        SELECT d.status, COUNT(d) 
        FROM RenterDocument d 
        WHERE d.user.id = :userId 
        GROUP BY d.status
        """)
    List<Object[]> countDocumentsByStatusForUser(@Param("userId") Long userId);
    
    // ==================== CLEANUP QUERIES ====================
    
    /**
     * Delete old rejected documents (GDPR retention).
     */
    @Query("""
        DELETE FROM RenterDocument d 
        WHERE d.status = 'REJECTED' 
        AND d.createdAt < :cutoff
        """)
    void deleteOldRejectedDocuments(@Param("cutoff") LocalDateTime cutoff);
    
    /**
     * Delete selfie documents older than retention period (90 days).
     */
    @Query("""
        DELETE FROM RenterDocument d 
        WHERE d.type = 'SELFIE' 
        AND d.createdAt < :cutoff
        """)
    void deleteOldSelfies(@Param("cutoff") LocalDateTime cutoff);
    
    // ==================== P0-5 FIX: OPTIMIZED RETENTION QUERIES ====================
    // These replace findAll().stream().filter() patterns in RenterDocumentRetentionScheduler
    
    /**
     * Find selfies older than cutoff date for retention cleanup.
     * P0-5 FIX: Database-level filtering instead of findAll().stream().filter()
     */
    @Query("""
        SELECT d FROM RenterDocument d 
        WHERE d.type = org.example.rentoza.user.document.RenterDocumentType.SELFIE 
        AND d.createdAt < :cutoff
        """)
    List<RenterDocument> findSelfiesOlderThan(@Param("cutoff") LocalDateTime cutoff);
    
    /**
     * Find rejected documents older than cutoff date for retention cleanup.
     * P0-5 FIX: Database-level filtering instead of findAll().stream().filter()
     */
    @Query("""
        SELECT d FROM RenterDocument d 
        WHERE d.processingStatus = org.example.rentoza.user.document.RenterDocument.ProcessingStatus.FAILED 
        AND d.createdAt < :cutoff
        """)
    List<RenterDocument> findRejectedDocumentsOlderThan(@Param("cutoff") LocalDateTime cutoff);
    
    /**
     * Find old documents with files for anonymization (excluding selfies).
     * P0-5 FIX: Database-level filtering instead of findAll().stream().filter()
     */
    @Query("""
        SELECT d FROM RenterDocument d 
        WHERE d.createdAt < :cutoff 
        AND d.documentUrl IS NOT NULL 
        AND d.type != org.example.rentoza.user.document.RenterDocumentType.SELFIE
        """)
    List<RenterDocument> findDocumentsForAnonymization(@Param("cutoff") LocalDateTime cutoff);
    
    /**
     * Count selfies pending deletion (for stats).
     * P0-5 FIX: Database aggregation instead of loading all records.
     */
    @Query("""
        SELECT COUNT(d) FROM RenterDocument d 
        WHERE d.type = org.example.rentoza.user.document.RenterDocumentType.SELFIE 
        AND d.createdAt < :cutoff
        """)
    long countSelfiesOlderThan(@Param("cutoff") LocalDateTime cutoff);
    
    /**
     * Count rejected documents pending deletion (for stats).
     * P0-5 FIX: Database aggregation instead of loading all records.
     */
    @Query("""
        SELECT COUNT(d) FROM RenterDocument d 
        WHERE d.processingStatus = org.example.rentoza.user.document.RenterDocument.ProcessingStatus.FAILED 
        AND d.createdAt < :cutoff
        """)
    long countRejectedDocumentsOlderThan(@Param("cutoff") LocalDateTime cutoff);
    
    /**
     * Count total selfies (for stats).
     * P0-5 FIX: Database aggregation instead of loading all records.
     */
    @Query("""
        SELECT COUNT(d) FROM RenterDocument d 
        WHERE d.type = org.example.rentoza.user.document.RenterDocumentType.SELFIE
        """)
    long countTotalSelfies();
}

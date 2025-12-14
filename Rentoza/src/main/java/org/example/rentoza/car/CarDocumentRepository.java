package org.example.rentoza.car;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for car document operations.
 */
@Repository
public interface CarDocumentRepository extends JpaRepository<CarDocument, Long> {
    
    /**
     * Find all documents for a car.
     */
    List<CarDocument> findByCarId(Long carId);

    /**
     * Find a document with its verifier eagerly loaded.
     *
     * <p>Used by admin review workflows to avoid LazyInitializationException
     * when mapping to DTOs outside the persistence context.
     */
    @Query("SELECT d FROM CarDocument d LEFT JOIN FETCH d.verifiedBy WHERE d.id = :documentId")
    Optional<CarDocument> findByIdWithVerifiedBy(Long documentId);

    /**
     * Find all documents for a car with verifier eagerly loaded.
     *
     * <p>Prevents N+1 queries when building admin review DTOs.
     */
    @Query("SELECT d FROM CarDocument d LEFT JOIN FETCH d.verifiedBy WHERE d.car.id = :carId")
    List<CarDocument> findByCarIdWithVerifiedBy(Long carId);
    
    /**
     * Find specific document type for a car.
     */
    Optional<CarDocument> findByCarIdAndType(Long carId, DocumentType type);
    
    /**
     * Find all pending documents for admin review.
     */
    List<CarDocument> findByStatus(DocumentVerificationStatus status);
    
    /**
     * Find documents expiring before a date (for future cron job).
     */
    @Query("SELECT d FROM CarDocument d WHERE d.expiryDate <= :date AND d.status = 'VERIFIED'")
    List<CarDocument> findExpiringDocuments(LocalDate date);
    
    /**
     * Count verified documents for a car.
     */
    @Query("SELECT COUNT(d) FROM CarDocument d WHERE d.car.id = :carId AND d.status = 'VERIFIED'")
    long countVerifiedDocuments(Long carId);
    
    /**
     * Check if all required documents are verified for a car.
     */
    @Query("""
        SELECT COUNT(d) FROM CarDocument d 
        WHERE d.car.id = :carId 
        AND d.status = 'VERIFIED' 
        AND d.type IN ('REGISTRATION', 'TECHNICAL_INSPECTION', 'LIABILITY_INSURANCE')
        """)
    long countRequiredVerifiedDocuments(Long carId);
}

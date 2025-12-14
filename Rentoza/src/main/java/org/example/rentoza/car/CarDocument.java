package org.example.rentoza.car;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.user.User;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Represents a single uploaded document for a car.
 * 
 * <p>Types:
 * <ul>
 *   <li>REGISTRATION - Vehicle registration (Saobraćajna dozvola)</li>
 *   <li>TECHNICAL_INSPECTION - Inspection certificate (Tehnički pregled)</li>
 *   <li>LIABILITY_INSURANCE - Insurance policy (Polisa Autoodgovornosti)</li>
 *   <li>AUTHORIZATION - Power of attorney (Ovlašćenje) - conditional</li>
 * </ul>
 */
@Entity
@Table(
    name = "car_documents",
    indexes = {
        @Index(name = "idx_car_documents_car_id", columnList = "car_id"),
        @Index(name = "idx_car_documents_type", columnList = "document_type"),
        @Index(name = "idx_car_documents_status", columnList = "verification_status"),
        @Index(name = "idx_car_documents_expiry", columnList = "expiry_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CarDocument {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * Car this document belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "car_id", nullable = false)
    private Car car;
    
    /**
     * Document type (REGISTRATION, TECHNICAL_INSPECTION, etc.).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 50)
    private DocumentType type;
    
    /**
     * Storage path (local) or URL (S3).
     */
    @Column(name = "document_url", nullable = false, length = 500)
    private String documentUrl;
    
    /**
     * Original filename for display.
     */
    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;
    
    /**
     * SHA256 hash for integrity verification.
     */
    @Column(name = "document_hash", nullable = false, length = 64)
    private String documentHash;
    
    /**
     * File size in bytes.
     */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;
    
    /**
     * MIME type (application/pdf, image/jpeg, etc.).
     */
    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;
    
    /**
     * When owner uploaded document.
     */
    @Column(name = "upload_date", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime uploadDate = LocalDateTime.now();
    
    /**
     * When document expires.
     * For TECHNICAL_INSPECTION: upload_date + 6 months.
     */
    @Column(name = "expiry_date")
    private LocalDate expiryDate;
    
    /**
     * Verification status.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 50)
    @Builder.Default
    private DocumentVerificationStatus status = DocumentVerificationStatus.PENDING;
    
    /**
     * Admin who verified/rejected.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verified_by")
    private User verifiedBy;
    
    /**
     * When verified/rejected.
     */
    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;
    
    /**
     * Rejection reason (if rejected).
     */
    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;
    
    // ================= HELPER METHODS =================
    
    /**
     * Check if document is expired.
     */
    public boolean isExpired() {
        return expiryDate != null && expiryDate.isBefore(LocalDate.now());
    }
    
    /**
     * Check if document will expire within N days.
     */
    public boolean willExpireWithin(int days) {
        if (expiryDate == null) return false;
        LocalDate warningDate = LocalDate.now().plusDays(days);
        return expiryDate.isBefore(warningDate) && expiryDate.isAfter(LocalDate.now());
    }
    
    /**
     * Get days until expiry (negative if expired).
     */
    public long getDaysUntilExpiry() {
        if (expiryDate == null) return Long.MAX_VALUE;
        return java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), expiryDate);
    }
    
    @PrePersist
    public void prePersist() {
        if (uploadDate == null) {
            uploadDate = LocalDateTime.now();
        }
    }
}

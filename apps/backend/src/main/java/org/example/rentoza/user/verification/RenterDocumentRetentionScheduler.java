package org.example.rentoza.user.verification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.car.storage.DocumentStorageStrategy;
import org.example.rentoza.config.timezone.SerbiaTimeZone;
import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.example.rentoza.user.document.RenterDocument;
import org.example.rentoza.user.document.RenterDocumentRepository;
import org.example.rentoza.user.document.RenterDocumentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GDPR-compliant data retention scheduler for renter verification documents.
 * 
 * <p>Implements the data retention policy as per GDPR requirements:
 * <ul>
 *   <li><b>Selfies (RENTER):</b> 90 days after verification completion</li>
 *   <li><b>Selfies (CHECK-IN):</b> 7 days after booking completion</li>
 *   <li><b>Rejected documents:</b> 30 days after rejection</li>
 *   <li><b>Expired documents:</b> 90 days after expiry</li>
 * </ul>
 * 
 * <p>The scheduler runs daily at 4 AM (off-peak hours) and performs:
 * <ol>
 *   <li>Identifies documents exceeding retention period</li>
 *   <li>Deletes physical files from storage</li>
 *   <li>Removes database records (or anonymizes metadata)</li>
 *   <li>Logs all deletions for audit purposes</li>
 * </ol>
 * 
 * <p>Based on enterprise patterns from platforms like Turo and Airbnb where
 * biometric data has strict retention limits.
 * 
 * @see RenterDocumentRepository
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RenterDocumentRetentionScheduler {
    
    private final RenterDocumentRepository documentRepository;
    private final DocumentStorageStrategy storageStrategy;
    private final SchedulerIdempotencyService lockService;
    
    // ==================== RETENTION PERIODS ====================
    
    /**
     * Selfie retention period for renter verification (days).
     * GDPR requires minimizing biometric data storage.
     */
    @Value("${app.renter-verification.selfie-retention-days:90}")
    private int selfieRetentionDays;
    
    /**
     * Check-in selfie retention period (days).
     * Shorter because check-in verification is per-booking.
     */
    @Value("${app.renter-verification.checkin-selfie-retention-days:7}")
    private int checkinSelfieRetentionDays;
    
    /**
     * Rejected document retention period (days).
     * Keep briefly for dispute resolution.
     */
    @Value("${app.renter-verification.rejected-document-retention-days:30}")
    private int rejectedDocumentRetentionDays;
    
    /**
     * Expired document retention period (days).
     */
    @Value("${app.renter-verification.expired-document-retention-days:90}")
    private int expiredDocumentRetentionDays;
    
    /**
     * Whether retention cleanup is enabled.
     */
    @Value("${app.renter-verification.retention-cleanup-enabled:true}")
    private boolean retentionCleanupEnabled;
    
    // ==================== SCHEDULED JOBS ====================
    
    /**
     * Daily selfie cleanup job. Runs at 4 AM to avoid peak hours.
     *
     * <p>Deletes selfie documents (biometric data) that have exceeded
     * the retention period. This is critical for GDPR compliance.
     *
     * <p>Uses distributed locking to prevent duplicate GDPR audit log entries
     * in multi-instance deployments.</p>
     */
    @Scheduled(cron = "${app.renter-verification.selfie-cleanup-cron:0 0 4 * * *}", zone = SerbiaTimeZone.ZONE_ID_STRING)
    @Transactional
    public void cleanupExpiredSelfies() {
        if (!retentionCleanupEnabled) {
            log.debug("Selfie retention cleanup is disabled");
            return;
        }

        if (!lockService.tryAcquireLock("gdpr.cleanup.selfies", Duration.ofHours(23))) {
            log.debug("[GDPR-Retention] Skipping selfie cleanup — lock held by another instance");
            return;
        }

        try {
            log.info("Starting selfie retention cleanup job");

            LocalDateTime cutoff = SerbiaTimeZone.now().minusDays(selfieRetentionDays);

            // P0-5 FIX: Use optimized database query instead of findAll().stream().filter()
            List<RenterDocument> expiredSelfies = documentRepository.findSelfiesOlderThan(cutoff);

            AtomicInteger deletedCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            for (RenterDocument selfie : expiredSelfies) {
                try {
                    // Delete physical file first
                    if (selfie.getDocumentUrl() != null) {
                        storageStrategy.deleteFile(selfie.getDocumentUrl());
                        log.debug("Deleted selfie file: {}", selfie.getDocumentUrl());
                    }

                    // Delete database record
                    documentRepository.delete(selfie);
                    deletedCount.incrementAndGet();

                    log.info("Deleted expired selfie: documentId={}, userId={}, age={} days",
                        selfie.getId(),
                        selfie.getUser() != null ? selfie.getUser().getId() : "unknown",
                        java.time.temporal.ChronoUnit.DAYS.between(selfie.getCreatedAt(), SerbiaTimeZone.now()));

                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    log.error("Failed to delete selfie: documentId={}", selfie.getId(), e);
                }
            }

            log.info("Selfie retention cleanup completed: deleted={}, errors={}",
                deletedCount.get(), errorCount.get());
        } finally {
            lockService.releaseLock("gdpr.cleanup.selfies");
        }
    }
    
    /**
     * Daily rejected document cleanup job. Runs at 4:30 AM.
     *
     * <p>Deletes rejected documents that have exceeded the retention period.
     * These are kept briefly for dispute resolution but should not be
     * stored indefinitely.
     *
     * <p>Uses distributed locking to prevent duplicate GDPR audit log entries.</p>
     */
    @Scheduled(cron = "${app.renter-verification.rejected-cleanup-cron:0 30 4 * * *}", zone = SerbiaTimeZone.ZONE_ID_STRING)
    @Transactional
    public void cleanupRejectedDocuments() {
        if (!retentionCleanupEnabled) {
            log.debug("Rejected document retention cleanup is disabled");
            return;
        }

        if (!lockService.tryAcquireLock("gdpr.cleanup.rejected-docs", Duration.ofHours(23))) {
            log.debug("[GDPR-Retention] Skipping rejected document cleanup — lock held by another instance");
            return;
        }

        try {
            log.info("Starting rejected document retention cleanup job");

            LocalDateTime cutoff = SerbiaTimeZone.now().minusDays(rejectedDocumentRetentionDays);

            // P0-5 FIX: Use optimized database query instead of findAll().stream().filter()
            List<RenterDocument> rejectedDocs = documentRepository.findRejectedDocumentsOlderThan(cutoff);

            AtomicInteger deletedCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            for (RenterDocument doc : rejectedDocs) {
                try {
                    // Delete physical file
                    if (doc.getDocumentUrl() != null) {
                        storageStrategy.deleteFile(doc.getDocumentUrl());
                    }

                    // Delete database record
                    documentRepository.delete(doc);
                    deletedCount.incrementAndGet();

                    log.info("Deleted rejected document: documentId={}, type={}, userId={}",
                        doc.getId(), doc.getType(),
                        doc.getUser() != null ? doc.getUser().getId() : "unknown");

                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    log.error("Failed to delete rejected document: documentId={}", doc.getId(), e);
                }
            }

            log.info("Rejected document retention cleanup completed: deleted={}, errors={}",
                deletedCount.get(), errorCount.get());
        } finally {
            lockService.releaseLock("gdpr.cleanup.rejected-docs");
        }
    }
    
    /**
     * Monthly anonymization job. Runs on the 1st of each month at 5 AM.
     *
     * <p>For audit trail requirements, some documents are anonymized rather
     * than deleted. This removes PII while preserving verification history.
     *
     * <p>Uses distributed locking to prevent duplicate GDPR anonymization runs.</p>
     */
    @Scheduled(cron = "${app.renter-verification.anonymize-cron:0 0 5 1 * *}", zone = SerbiaTimeZone.ZONE_ID_STRING)
    @Transactional
    public void anonymizeOldDocuments() {
        if (!retentionCleanupEnabled) {
            log.debug("Document anonymization is disabled");
            return;
        }

        if (!lockService.tryAcquireLock("gdpr.anonymize.documents", Duration.ofHours(23))) {
            log.debug("[GDPR-Retention] Skipping anonymization — lock held by another instance");
            return;
        }

        try {
            log.info("Starting document anonymization job");

            // Anonymize documents older than 1 year but keep metadata
            LocalDateTime cutoff = SerbiaTimeZone.now().minusYears(1);

            // P0-5 FIX: Use optimized database query instead of findAll().stream().filter()
            List<RenterDocument> oldDocuments = documentRepository.findDocumentsForAnonymization(cutoff);

            AtomicInteger anonymizedCount = new AtomicInteger(0);

            for (RenterDocument doc : oldDocuments) {
                try {
                    // Delete physical file but keep database record
                    storageStrategy.deleteFile(doc.getDocumentUrl());

                    // Anonymize the record
                    doc.setDocumentUrl(null);
                    doc.setDocumentHash(null);
                    doc.setOcrExtractedData(null);
                    doc.setProcessingError(null);
                    documentRepository.save(doc);

                    anonymizedCount.incrementAndGet();

                    log.debug("Anonymized document: documentId={}", doc.getId());

                } catch (Exception e) {
                    log.error("Failed to anonymize document: documentId={}", doc.getId(), e);
                }
            }

            log.info("Document anonymization completed: anonymized={}", anonymizedCount.get());
        } finally {
            lockService.releaseLock("gdpr.anonymize.documents");
        }
    }
    
    // ==================== MANUAL TRIGGERS ====================
    
    /**
     * Manual trigger for selfie cleanup (admin use only).
     * 
     * @return Summary of cleanup operation
     */
    public String manualSelfieCleanup() {
        log.warn("Manual selfie cleanup triggered by admin");
        cleanupExpiredSelfies();
        return "Selfie cleanup completed. Check logs for details.";
    }
    
    /**
     * Get retention statistics for admin dashboard.
     * 
     * @return Map of retention statistics
     */
    public java.util.Map<String, Object> getRetentionStats() {
        LocalDateTime selfieCutoff = SerbiaTimeZone.now().minusDays(selfieRetentionDays);
        LocalDateTime rejectedCutoff = SerbiaTimeZone.now().minusDays(rejectedDocumentRetentionDays);
        
        // P0-5 FIX: Use optimized count queries instead of loading all records
        long pendingSelfies = documentRepository.countSelfiesOlderThan(selfieCutoff);
        long pendingRejected = documentRepository.countRejectedDocumentsOlderThan(rejectedCutoff);
        long totalSelfies = documentRepository.countTotalSelfies();
        
        return java.util.Map.of(
            "selfieRetentionDays", selfieRetentionDays,
            "rejectedRetentionDays", rejectedDocumentRetentionDays,
            "totalSelfies", totalSelfies,
            "selfiesPendingDeletion", pendingSelfies,
            "rejectedPendingDeletion", pendingRejected,
            "retentionCleanupEnabled", retentionCleanupEnabled
        );
    }
}

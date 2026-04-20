package org.example.rentoza.user;

/**
 * Risk assessment level for renter verification.
 * 
 * <p>Determines verification requirements and auto-approval thresholds:
 * <ul>
 *   <li>{@code LOW} - Returning users with history: auto-approve at 95% OCR confidence</li>
 *   <li>{@code MEDIUM} - New users, no red flags: auto-approve at 90% OCR + liveness</li>
 *   <li>{@code HIGH} - Risk flags detected: manual review required</li>
 * </ul>
 * 
 * <p>Risk scoring factors:
 * <ul>
 *   <li>Account age (< 30 days = higher risk)</li>
 *   <li>Completed trips (more = lower risk)</li>
 *   <li>Dispute history (disputes = higher risk)</li>
 *   <li>Name mismatch severity</li>
 *   <li>OCR confidence scores</li>
 *   <li>Velocity flags (multiple accounts, rapid submissions)</li>
 * </ul>
 */
public enum RiskLevel {
    
    /**
     * Low risk: Returning user with verified history.
     * 
     * <p>Criteria:
     * <ul>
     *   <li>Account age > 90 days</li>
     *   <li>At least 3 completed trips</li>
     *   <li>No disputes or cancellations</li>
     *   <li>Previously verified (returning verification)</li>
     * </ul>
     * 
     * <p>Auto-approve threshold: OCR confidence > 0.95
     */
    LOW("Nizak rizik", 0.95, true),
    
    /**
     * Medium risk: New user with no red flags.
     * 
     * <p>Criteria:
     * <ul>
     *   <li>Account age 30-90 days OR new with verified email/phone</li>
     *   <li>No dispute history</li>
     *   <li>Standard verification flow</li>
     * </ul>
     * 
     * <p>Auto-approve threshold: OCR confidence > 0.90 AND liveness passed
     */
    MEDIUM("Srednji rizik", 0.90, true),
    
    /**
     * High risk: Risk flags detected, requires manual review.
     * 
     * <p>Triggers:
     * <ul>
     *   <li>Account age < 30 days</li>
     *   <li>Name mismatch > 0.30 (significant discrepancy)</li>
     *   <li>Low OCR confidence (< 0.70)</li>
     *   <li>Dispute history</li>
     *   <li>Multiple failed verification attempts</li>
     *   <li>Velocity flags (e.g., same device/IP as banned user)</li>
     * </ul>
     * 
     * <p>Auto-approve: DISABLED - manual review required
     */
    HIGH("Visok rizik", 0.00, false);
    
    private final String serbianName;
    private final double autoApproveThreshold;
    private final boolean autoApproveEnabled;
    
    RiskLevel(String serbianName, double autoApproveThreshold, boolean autoApproveEnabled) {
        this.serbianName = serbianName;
        this.autoApproveThreshold = autoApproveThreshold;
        this.autoApproveEnabled = autoApproveEnabled;
    }
    
    /**
     * Serbian display name for admin UI.
     */
    public String getSerbianName() {
        return serbianName;
    }
    
    /**
     * Minimum OCR confidence for auto-approval at this risk level.
     */
    public double getAutoApproveThreshold() {
        return autoApproveThreshold;
    }
    
    /**
     * Whether auto-approval is enabled for this risk level.
     */
    public boolean isAutoApproveEnabled() {
        return autoApproveEnabled;
    }
    
    /**
     * Check if given OCR confidence meets auto-approve threshold.
     * 
     * @param ocrConfidence OCR extraction confidence (0.0-1.0)
     * @return true if auto-approve criteria met
     */
    public boolean meetsAutoApproveThreshold(double ocrConfidence) {
        return autoApproveEnabled && ocrConfidence >= autoApproveThreshold;
    }
}

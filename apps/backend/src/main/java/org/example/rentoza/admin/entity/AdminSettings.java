package org.example.rentoza.admin.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Singleton-style admin settings entity.
 * 
 * <p>Design: Single row in database with ID=1, updated via PUT.
 * This replaces the localStorage-based frontend mock implementation
 * with a proper database-backed solution.
 * 
 * <p><b>Settings Categories:</b>
 * <ul>
 *   <li>Notification Preferences (email, push, SMS)</li>
 *   <li>Reporting Options (weekly/monthly, format)</li>
 *   <li>Regional Settings (timezone, currency)</li>
 *   <li>Security Settings (2FA, session timeout)</li>
 * </ul>
 * 
 * @since Phase 4 - Production Readiness
 */
@Entity
@Table(name = "admin_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    // ==================== NOTIFICATION SETTINGS ====================
    
    /**
     * Enable email notifications for admin alerts.
     */
    @Column(name = "email_notifications", nullable = false)
    @Builder.Default
    private Boolean emailNotifications = true;
    
    /**
     * Enable push notifications (browser/mobile).
     */
    @Column(name = "push_notifications", nullable = false)
    @Builder.Default
    private Boolean pushNotifications = false;
    
    /**
     * Enable SMS notifications for critical alerts.
     */
    @Column(name = "sms_notifications", nullable = false)
    @Builder.Default
    private Boolean smsNotifications = false;
    
    // ==================== REPORTING SETTINGS ====================
    
    /**
     * Send weekly summary report email.
     */
    @Column(name = "weekly_report", nullable = false)
    @Builder.Default
    private Boolean weeklyReport = true;
    
    /**
     * Send monthly summary report email.
     */
    @Column(name = "monthly_report", nullable = false)
    @Builder.Default
    private Boolean monthlyReport = false;
    
    /**
     * Report file format: 'pdf', 'csv', 'xlsx'
     */
    @Column(name = "report_format", length = 10, nullable = false)
    @Builder.Default
    private String reportFormat = "pdf";
    
    // ==================== REGIONAL SETTINGS ====================
    
    /**
     * Admin timezone for displaying timestamps.
     * E.g., 'Europe/Belgrade', 'UTC', 'America/New_York'
     */
    @Column(name = "timezone", length = 50, nullable = false)
    @Builder.Default
    private String timezone = "Europe/Belgrade";
    
    /**
     * Currency format for financial displays.
     * E.g., 'RSD', 'EUR', 'USD'
     */
    @Column(name = "currency_format", length = 10, nullable = false)
    @Builder.Default
    private String currencyFormat = "RSD";
    
    // ==================== SECURITY SETTINGS ====================
    
    /**
     * Whether two-factor authentication is enabled for admin.
     */
    @Column(name = "two_factor_enabled", nullable = false)
    @Builder.Default
    private Boolean twoFactorEnabled = false;
    
    /**
     * Send alerts on new admin login.
     */
    @Column(name = "login_alerts", nullable = false)
    @Builder.Default
    private Boolean loginAlerts = true;
    
    /**
     * Session timeout in minutes.
     */
    @Column(name = "session_timeout", length = 10, nullable = false)
    @Builder.Default
    private String sessionTimeout = "60";
    
    // ==================== TIMESTAMPS ====================
    
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    
    /**
     * Create default settings instance.
     * 
     * @return AdminSettings with default values
     */
    public static AdminSettings createDefault() {
        return AdminSettings.builder()
                .emailNotifications(true)
                .pushNotifications(false)
                .smsNotifications(false)
                .weeklyReport(true)
                .monthlyReport(false)
                .reportFormat("pdf")
                .timezone("Europe/Belgrade")
                .currencyFormat("RSD")
                .twoFactorEnabled(false)
                .loginAlerts(true)
                .sessionTimeout("60")
                .build();
    }
}

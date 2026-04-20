package org.example.rentoza.admin.service;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.AdminSettingsDto;
import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.AdminSettings;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.admin.repository.AdminUserRepository;
import org.example.rentoza.admin.repository.AdminSettingsRepository;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.User;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing admin settings.
 * 
 * <p>Implements singleton pattern: single row in DB, created on first access.
 * Settings are cached for performance (cache evicted on update).
 * 
 * @since Phase 4 - Production Readiness
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminSettingsService {
    
    private final AdminSettingsRepository adminSettingsRepository;
    private final AdminUserRepository adminUserRepository;
    private final AdminAuditService auditService;
    private final CurrentUser currentUser;
    
    /**
     * Get current admin settings.
     * Creates default settings if none exist (first access).
     * 
     * @return AdminSettingsDto with current settings
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "adminSettings", key = "'singleton'")
    public AdminSettingsDto getSettings() {
        log.debug("Fetching admin settings from database");
        
        AdminSettings settings = adminSettingsRepository.findFirst()
                .orElseGet(() -> {
                    log.info("No admin settings found, creating defaults");
                    return adminSettingsRepository.save(AdminSettings.createDefault());
                });
        
        return toDto(settings);
    }
    
    /**
     * Update admin settings.
     * Creates settings if none exist, otherwise updates existing.
     * 
     * @param dto New settings values
     * @return Updated AdminSettingsDto
     */
    @Transactional
    @CacheEvict(value = "adminSettings", key = "'singleton'")
    public AdminSettingsDto updateSettings(AdminSettingsDto dto) {
        log.info("Updating admin settings");
        
        AdminSettings settings = adminSettingsRepository.findFirst()
                .orElseGet(() -> {
                    log.info("No admin settings found during update, creating new");
                    return AdminSettings.createDefault();
                });
        String beforeState = auditService.toJson(toDto(settings));
        
        // Update only fields that have a real operator-visible effect today.
        // AUDIT-M5-FIX: twoFactorEnabled and sessionTimeout remain in the entity for
        // low-risk compatibility, but they are intentionally not exposed or mutated
        // until they are wired to runtime auth/session enforcement.
        settings.setEmailNotifications(dto.getEmailNotifications());
        settings.setPushNotifications(dto.getPushNotifications());
        settings.setSmsNotifications(dto.getSmsNotifications());
        settings.setWeeklyReport(dto.getWeeklyReport());
        settings.setMonthlyReport(dto.getMonthlyReport());
        settings.setReportFormat(dto.getReportFormat());
        settings.setTimezone(dto.getTimezone());
        settings.setCurrencyFormat(dto.getCurrencyFormat());
        settings.setLoginAlerts(dto.getLoginAlerts());
        
        AdminSettings saved = adminSettingsRepository.save(settings);
        AdminSettingsDto updated = toDto(saved);
        auditSettingsMutation(beforeState, updated, "Admin settings updated");
        log.info("Admin settings updated successfully");
        
        return updated;
    }
    
    /**
     * Reset settings to defaults.
     * 
     * @return AdminSettingsDto with default values
     */
    @Transactional
    @CacheEvict(value = "adminSettings", key = "'singleton'")
    public AdminSettingsDto resetToDefaults() {
        log.info("Resetting admin settings to defaults");
        
        AdminSettings settings = adminSettingsRepository.findFirst()
                .orElseGet(AdminSettings::createDefault);
        String beforeState = auditService.toJson(toDto(settings));
        
        // Reset all fields to defaults
        AdminSettings defaults = AdminSettings.createDefault();
        settings.setEmailNotifications(defaults.getEmailNotifications());
        settings.setPushNotifications(defaults.getPushNotifications());
        settings.setSmsNotifications(defaults.getSmsNotifications());
        settings.setWeeklyReport(defaults.getWeeklyReport());
        settings.setMonthlyReport(defaults.getMonthlyReport());
        settings.setReportFormat(defaults.getReportFormat());
        settings.setTimezone(defaults.getTimezone());
        settings.setCurrencyFormat(defaults.getCurrencyFormat());
        settings.setLoginAlerts(defaults.getLoginAlerts());
        
        AdminSettings saved = adminSettingsRepository.save(settings);
        AdminSettingsDto reset = toDto(saved);
        auditSettingsMutation(beforeState, reset, "Admin settings reset to defaults");
        log.info("Admin settings reset to defaults");
        
        return reset;
    }
    
    /**
     * Convert entity to DTO.
     */
    private AdminSettingsDto toDto(AdminSettings entity) {
        return AdminSettingsDto.builder()
                .emailNotifications(entity.getEmailNotifications())
                .pushNotifications(entity.getPushNotifications())
                .smsNotifications(entity.getSmsNotifications())
                .weeklyReport(entity.getWeeklyReport())
                .monthlyReport(entity.getMonthlyReport())
                .reportFormat(entity.getReportFormat())
                .timezone(entity.getTimezone())
                .currencyFormat(entity.getCurrencyFormat())
                .loginAlerts(entity.getLoginAlerts())
                .build();
    }

    private void auditSettingsMutation(String beforeState, AdminSettingsDto afterSettings, @NonNull String reason) {
        User admin = adminUserRepository.findById(currentUser.id())
                .orElseThrow(() -> new IllegalStateException("Authenticated admin not found for settings audit"));

        // AUDIT-M5-FIX: Settings mutations are privileged admin actions and must be audited.
        auditService.logAction(
                admin,
                AdminAction.CONFIG_UPDATED,
                ResourceType.CONFIG,
                null,
                beforeState,
                auditService.toJson(afterSettings),
                reason
        );
    }
}

package org.example.rentoza.admin.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.AdminSettingsDto;
import org.example.rentoza.admin.service.AdminSettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for admin settings management.
 * 
 * <p>Endpoints:
 * <ul>
 *   <li>GET /api/admin/settings - Retrieve current settings</li>
 *   <li>PUT /api/admin/settings - Update all settings</li>
 *   <li>POST /api/admin/settings/reset - Reset to defaults</li>
 * </ul>
 * 
 * <p>All endpoints require ROLE_ADMIN.
 * 
 * @since Phase 4 - Production Readiness
 */
@RestController
@RequestMapping("/api/admin/settings")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminSettingsController {
    
    private final AdminSettingsService adminSettingsService;
    
    /**
     * Get current admin settings.
     * Creates default settings on first access if none exist.
     * 
     * @return AdminSettingsDto with current settings
     */
    @GetMapping
    public ResponseEntity<AdminSettingsDto> getSettings() {
        log.debug("GET /api/admin/settings - fetching admin settings");
        AdminSettingsDto settings = adminSettingsService.getSettings();
        return ResponseEntity.ok(settings);
    }
    
    /**
     * Update admin settings.
     * Validates all fields before persisting.
     * 
     * @param dto New settings values (all fields required)
     * @return Updated AdminSettingsDto
     */
    @PutMapping
    public ResponseEntity<AdminSettingsDto> updateSettings(
            @Valid @RequestBody AdminSettingsDto dto) {
        log.info("PUT /api/admin/settings - updating admin settings");
        AdminSettingsDto updated = adminSettingsService.updateSettings(dto);
        return ResponseEntity.ok(updated);
    }
    
    /**
     * Reset settings to default values.
     * 
     * @return AdminSettingsDto with default values
     */
    @PostMapping("/reset")
    public ResponseEntity<AdminSettingsDto> resetSettings() {
        log.info("POST /api/admin/settings/reset - resetting to defaults");
        AdminSettingsDto defaults = adminSettingsService.resetToDefaults();
        return ResponseEntity.ok(defaults);
    }
}

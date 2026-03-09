package org.example.rentoza.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for admin settings GET/PUT operations.
 * 
 * <p>Maps 1:1 with frontend AdminSettings interface.
 * Used for both request and response payloads.
 * 
 * @since Phase 4 - Production Readiness
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminSettingsDto {
    
    // ==================== NOTIFICATION SETTINGS ====================
    
    @NotNull(message = "emailNotifications is required")
    private Boolean emailNotifications;
    
    @NotNull(message = "pushNotifications is required")
    private Boolean pushNotifications;
    
    @NotNull(message = "smsNotifications is required")
    private Boolean smsNotifications;
    
    // ==================== REPORTING SETTINGS ====================
    
    @NotNull(message = "weeklyReport is required")
    private Boolean weeklyReport;
    
    @NotNull(message = "monthlyReport is required")
    private Boolean monthlyReport;
    
    @NotBlank(message = "reportFormat is required")
    @Pattern(regexp = "^(pdf|csv|xlsx)$", message = "reportFormat must be pdf, csv, or xlsx")
    private String reportFormat;
    
    // ==================== REGIONAL SETTINGS ====================
    
    @NotBlank(message = "timezone is required")
    private String timezone;
    
    @NotBlank(message = "currencyFormat is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "currencyFormat must be a 3-letter ISO currency code")
    private String currencyFormat;
    
    // ==================== SECURITY SETTINGS ====================

    @NotNull(message = "loginAlerts is required")
    private Boolean loginAlerts;
}

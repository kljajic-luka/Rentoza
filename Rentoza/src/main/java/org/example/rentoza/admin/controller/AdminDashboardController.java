package org.example.rentoza.admin.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.DashboardKpiDto;
import org.example.rentoza.admin.service.AdminDashboardService;
import org.example.rentoza.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Admin Dashboard Controller.
 * 
 * <p>Provides endpoints for the admin dashboard:
 * <ul>
 *   <li>GET /api/admin/dashboard/kpis - Real-time KPI data</li>
 *   <li>POST /api/admin/dashboard/refresh - Force metrics refresh</li>
 * </ul>
 * 
 * <p>Security: All endpoints require ROLE_ADMIN.
 * 
 * @see AdminDashboardService
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminDashboardController {
    
    private final AdminDashboardService dashboardService;
    private final CurrentUser currentUser;
    
    /**
     * Get real-time dashboard KPIs.
     * 
     * <p>Returns:
     * <ul>
     *   <li>Active trips count</li>
     *   <li>Total revenue (current month)</li>
     *   <li>Revenue/user/booking growth percentages</li>
     *   <li>Pending approvals count</li>
     *   <li>Open disputes count</li>
     *   <li>Suspended users count</li>
     *   <li>Platform health score (0-100)</li>
     * </ul>
     * 
     * <p>Performance: ~50-100ms for typical dataset.
     * Consider Redis caching for >10K users.
     * 
     * @return Dashboard KPIs
     */
    @GetMapping("/kpis")
    public ResponseEntity<DashboardKpiDto> getDashboardKpis() {
        log.debug("Admin {} requesting dashboard KPIs", currentUser.id());
        
        DashboardKpiDto kpis = dashboardService.getDashboardKpis();
        
        log.debug("Dashboard KPIs calculated: activeTrips={}, disputes={}, healthScore={}",
            kpis.getActiveTripsCount(),
            kpis.getOpenDisputesCount(),
            kpis.getPlatformHealthScore());
        
        return ResponseEntity.ok(kpis);
    }
    
    /**
     * Force manual metrics snapshot.
     * 
     * <p>Triggers an immediate metrics snapshot save.
     * Normally runs automatically every hour.
     * 
     * @return Success confirmation
     */
    @PostMapping("/refresh")
    public ResponseEntity<String> refreshMetrics() {
        log.info("Admin {} triggered manual metrics refresh", currentUser.id());
        
        dashboardService.saveMetricsSnapshot();
        
        return ResponseEntity.ok("Metrics snapshot saved successfully");
    }
}

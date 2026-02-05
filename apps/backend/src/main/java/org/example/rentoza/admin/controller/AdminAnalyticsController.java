package org.example.rentoza.admin.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.*;
import org.example.rentoza.admin.service.AdminAnalyticsService;
import org.example.rentoza.security.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Admin Analytics Controller for business intelligence.
 * 
 * <p><b>ENDPOINTS:</b>
 * <ul>
 *   <li>GET /api/admin/analytics/revenue-trend - Revenue trend analysis</li>
 *   <li>GET /api/admin/analytics/cohort - User cohort analysis</li>
 *   <li>GET /api/admin/analytics/top-performers - Top hosts and cars</li>
 * </ul>
 * 
 * <p><b>CACHING:</b> Heavy queries cached for 15 minutes.
 * 
 * <p><b>SECURITY:</b> All endpoints require ROLE_ADMIN.
 */
@RestController
@RequestMapping("/api/admin/analytics")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminAnalyticsController {
    
    private final AdminAnalyticsService analyticsService;
    private final CurrentUser currentUser;
    
    /**
     * Get revenue trend analysis.
     * 
     * <p><b>Query Parameters:</b>
     * <ul>
     *   <li>period - DAILY, WEEKLY, or MONTHLY</li>
     *   <li>startDate - Start of analysis (ISO date)</li>
     *   <li>endDate - End of analysis (ISO date)</li>
     * </ul>
     * 
     * @return Revenue trend with data points and growth rate
     */
    @GetMapping("/revenue-trend")
    public ResponseEntity<RevenueTrendDto> getRevenueTrend(
            @RequestParam(defaultValue = "MONTHLY") String period,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        
        log.debug("Admin {} requesting revenue trend: {} from {} to {}", 
                  currentUser.id(), period, startDate, endDate);
        
        RevenueTrendDto trend = analyticsService.getRevenueTrend(period, startDate, endDate);
        
        return ResponseEntity.ok(trend);
    }
    
    /**
     * Get user cohort analysis.
     * 
     * <p><b>Query Parameters:</b>
     * <ul>
     *   <li>cohort - Signup month (YYYY-MM)</li>
     *   <li>monthsToTrack - Number of months to analyze (default: 6)</li>
     * </ul>
     * 
     * @return Cohort retention and revenue metrics
     */
    @GetMapping("/cohort")
    public ResponseEntity<CohortAnalysisDto> getCohortAnalysis(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth cohort,
            @RequestParam(defaultValue = "6") int monthsToTrack) {
        
        log.debug("Admin {} requesting cohort analysis: {} for {} months", 
                  currentUser.id(), cohort, monthsToTrack);
        
        CohortAnalysisDto analysis = analyticsService.getCohortAnalysis(cohort, monthsToTrack);
        
        return ResponseEntity.ok(analysis);
    }
    
    /**
     * Get top performing hosts and cars.
     * 
     * <p><b>Query Parameters:</b>
     * <ul>
     *   <li>topN - Number of top performers (default: 10)</li>
     *   <li>startDate - Start of analysis period</li>
     *   <li>endDate - End of analysis period</li>
     * </ul>
     * 
     * @return Top hosts and cars by revenue
     */
    @GetMapping("/top-performers")
    public ResponseEntity<TopPerformersDto> getTopPerformers(
            @RequestParam(defaultValue = "10") int topN,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        
        log.debug("Admin {} requesting top {} performers from {} to {}", 
                  currentUser.id(), topN, startDate, endDate);
        
        TopPerformersDto performers = analyticsService.getTopPerformers(topN, startDate, endDate);
        
        return ResponseEntity.ok(performers);
    }
}

package org.example.rentoza.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Dashboard KPI DTO for admin panel.
 * 
 * <p>Contains all key performance indicators displayed on the admin dashboard.
 * Values are calculated in real-time or from cached metrics snapshots.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardKpiDto {
    
    // ==================== REAL-TIME METRICS ====================
    
    /** Number of trips currently in progress (IN_TRIP status) */
    private Long activeTripsCount;
    
    /** Total revenue in RSD (current month) */
    private BigDecimal totalRevenueThisMonth;
    
    /** Revenue growth percentage vs last month */
    private Double revenueGrowthPercent;
    
    /** Number of car listings pending admin approval */
    private Long pendingApprovalsCount;
    
    /** Number of disputes awaiting admin resolution */
    private Long openDisputesCount;
    
    /** Number of currently banned/suspended users */
    private Long suspendedUsersCount;
    
    // ==================== PERIOD METRICS ====================
    
    /** New user registrations this month */
    private Long newUsersThisMonth;
    
    /** User growth percentage vs last month */
    private Double userGrowthPercent;
    
    /** Total bookings this month */
    private Long bookingsThisMonth;
    
    /** Booking growth percentage vs last month */
    private Double bookingGrowthPercent;
    
    /** Completed trips this month */
    private Long completedTripsThisMonth;
    
    // ==================== PLATFORM TOTALS ====================
    
    /** Total registered users on platform */
    private Long totalUsers;
    
    /** Total active car listings */
    private Long totalActiveCars;
    
    /** Total bookings all-time */
    private Long totalBookings;
    
    // ==================== HEALTH INDICATORS ====================
    
    /** Average response time to disputes (hours) */
    private Double avgDisputeResolutionHours;
    
    /** Average time to approve car listings (hours) */
    private Double avgCarApprovalHours;
    
    /** Platform health score (0-100) */
    private Integer platformHealthScore;
    
    // ==================== METADATA ====================
    
    /** When KPIs were last calculated */
    private LocalDateTime calculatedAt;
    
    /** Cache TTL remaining in seconds (0 if real-time) */
    private Long cacheTtlSeconds;
}

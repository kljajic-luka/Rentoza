package org.example.rentoza.admin.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * KPI Snapshot Entity for Admin Dashboard.
 * 
 * <p>Purpose: Store periodic snapshots of key metrics for dashboard and trend analysis.
 * 
 * <p><b>Strategy:</b>
 * <ul>
 *   <li>Scheduled job runs every hour to capture real-time metrics</li>
 *   <li>Retention: Keep last 12 months (delete older records)</li>
 *   <li>Dashboard: Get latest snapshot (< 30s old, cached)</li>
 *   <li>Trends: Query historical data for charts</li>
 *   <li>Alerts: Trigger if metrics deviate from baseline</li>
 * </ul>
 * 
 * @see AdminDashboardService for KPI calculation logic
 */
@Entity
@Table(
    name = "admin_metrics",
    indexes = {
        @Index(name = "idx_metrics_created_at", columnList = "created_at"),
        @Index(name = "idx_metrics_snapshot_date", columnList = "snapshot_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminMetrics {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    // ==================== REAL-TIME METRICS ====================
    
    /**
     * Number of currently active trips (IN_PROGRESS bookings).
     */
    @Column(name = "active_trips_count", nullable = false)
    @Builder.Default
    private Integer activeTripsCount = 0;
    
    /**
     * Total revenue in cents (to avoid float precision issues).
     * Sum of all completed booking totals.
     */
    @Column(name = "total_revenue_cents", nullable = false)
    @Builder.Default
    private Long totalRevenueCents = 0L;
    
    /**
     * Number of car listings pending approval.
     */
    @Column(name = "pending_approvals_count")
    @Builder.Default
    private Integer pendingApprovalsCount = 0;
    
    /**
     * Number of open disputes awaiting resolution.
     */
    @Column(name = "open_disputes_count")
    @Builder.Default
    private Integer openDisputesCount = 0;
    
    /**
     * Number of currently suspended/banned users.
     */
    @Column(name = "suspended_users_count")
    @Builder.Default
    private Integer suspendedUsersCount = 0;
    
    // ==================== PERIOD METRICS ====================
    
    /**
     * New user registrations (in snapshot period).
     */
    @Column(name = "new_users_count")
    @Builder.Default
    private Integer newUsersCount = 0;
    
    /**
     * New car listings added (in snapshot period).
     */
    @Column(name = "new_cars_count")
    @Builder.Default
    private Integer newCarsCount = 0;
    
    /**
     * Completed bookings (in snapshot period).
     */
    @Column(name = "completed_bookings_count")
    @Builder.Default
    private Integer completedBookingsCount = 0;
    
    /**
     * Total registered users on platform.
     */
    @Column(name = "total_users_count")
    @Builder.Default
    private Integer totalUsersCount = 0;
    
    /**
     * Total active car listings on platform.
     */
    @Column(name = "total_cars_count")
    @Builder.Default
    private Integer totalCarsCount = 0;
    
    // ==================== GROWTH METRICS (%) ====================
    
    /**
     * Revenue growth percentage compared to previous period.
     */
    @Column(name = "revenue_growth_percent")
    private Double revenueGrowthPercent;
    
    /**
     * User growth percentage compared to previous period.
     */
    @Column(name = "user_growth_percent")
    private Double userGrowthPercent;
    
    /**
     * Booking growth percentage compared to previous period.
     */
    @Column(name = "booking_growth_percent")
    private Double bookingGrowthPercent;
    
    // ==================== TIMESTAMPS ====================
    
    /**
     * When metrics were calculated (for display purposes).
     */
    @Column(name = "snapshot_date")
    private LocalDateTime snapshotDate;
    
    /**
     * When record was created (auto-set).
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

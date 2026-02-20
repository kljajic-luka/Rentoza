package org.example.rentoza.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.DashboardKpiDto;
import org.example.rentoza.admin.dto.RecentBookingDto;
import org.example.rentoza.admin.entity.AdminMetrics;
import org.example.rentoza.admin.repository.AdminMetricsRepository;
import org.example.rentoza.admin.repository.AdminUserRepository;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.car.ListingStatus;
import org.example.rentoza.config.timezone.SerbiaTimeZone;
import org.example.rentoza.user.UserRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Admin Dashboard Service for real-time KPI calculation.
 * 
 * <p>Provides metrics for the admin dashboard:
 * <ul>
 *   <li>Active trips count</li>
 *   <li>Total revenue (current month)</li>
 *   <li>Pending approvals count</li>
 *   <li>Open disputes count</li>
 *   <li>User/booking growth percentages</li>
 * </ul>
 * 
 * <p><b>CACHING STRATEGY:</b>
 * <ul>
 *   <li>KPIs are calculated on-demand (real-time)</li>
 *   <li>Scheduled job saves hourly snapshots to admin_metrics</li>
 *   <li>Historical data used for trend charts</li>
 * </ul>
 * 
 * @see DashboardKpiDto
 * @see AdminMetrics
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AdminDashboardService {
    
    private final BookingRepository bookingRepo;
    private final CarRepository carRepo;
    private final UserRepository userRepo;
    private final AdminUserRepository adminUserRepo;
    private final DamageClaimRepository damageClaimRepo;
    private final AdminMetricsRepository metricsRepo;
    
    /**
     * Get real-time dashboard KPIs.
     * 
     * <p>Calculates all metrics fresh from the database.
     * Cached for 5 minutes to reduce load on high-traffic periods.
     * 
     * @return Dashboard KPIs
     */
    @Cacheable(value = "adminMetrics", key = "'kpis'")
    public DashboardKpiDto getDashboardKpis() {
        log.debug("Calculating dashboard KPIs (cache miss)");
        
        LocalDateTime now = SerbiaTimeZone.now();
        LocalDateTime startOfMonth = now.toLocalDate().withDayOfMonth(1).atStartOfDay();
        LocalDateTime startOfLastMonth = startOfMonth.minusMonths(1);
        LocalDateTime endOfLastMonthExclusive = startOfMonth;
        
        Instant nowInstant = Instant.now();
        Instant startOfMonthInstant = SerbiaTimeZone.toInstant(startOfMonth);
        Instant startOfLastMonthInstant = SerbiaTimeZone.toInstant(startOfLastMonth);
        Instant endOfLastMonthExclusiveInstant = SerbiaTimeZone.toInstant(endOfLastMonthExclusive);
        
        // ==================== REAL-TIME METRICS ====================
        
        Long activeTripsCount = bookingRepo.countActiveTrips();
        Long pendingApprovalsCount = carRepo.countByListingStatus(ListingStatus.PENDING_APPROVAL);
        Long openDisputesCount = damageClaimRepo.countOpenDisputes();
        Long suspendedUsersCount = adminUserRepo.countBannedUsers();
        
        // ==================== REVENUE CALCULATION ====================
        
        // This month's completed bookings
        Long completedThisMonth = bookingRepo.countByStatusAndCreatedAtBetween(
            BookingStatus.COMPLETED, startOfMonth, now);
        BigDecimal revenueThisMonth = calculateRevenueForPeriod(startOfMonthInstant, nowInstant);
        BigDecimal revenueLastMonth = calculateRevenueForPeriod(startOfLastMonthInstant, endOfLastMonthExclusiveInstant);
        
        Double revenueGrowth = calculateGrowthPercent(revenueThisMonth, revenueLastMonth);
        
        // ==================== USER METRICS ====================
        
        Long totalUsers = userRepo.count();
        Long newUsersThisMonth = adminUserRepo.countUsersSince(startOfMonthInstant);
        Long newUsersLastMonth = countUsersInPeriod(startOfLastMonthInstant, endOfLastMonthExclusiveInstant);
        
        Double userGrowth = calculateGrowthPercent(
            newUsersThisMonth != null ? newUsersThisMonth : 0L,
            newUsersLastMonth != null ? newUsersLastMonth : 0L
        );
        
        // ==================== BOOKING METRICS ====================
        
        Long totalBookings = bookingRepo.count();
        Long bookingsThisMonth = countBookingsInPeriod(startOfMonth, now);
        Long bookingsLastMonth = countBookingsInPeriod(startOfLastMonth, endOfLastMonthExclusive);
        
        Double bookingGrowth = calculateGrowthPercent(
            bookingsThisMonth != null ? bookingsThisMonth : 0L,
            bookingsLastMonth != null ? bookingsLastMonth : 0L
        );
        
        // ==================== CAR METRICS ====================
        
        Long totalActiveCars = carRepo.countAvailableCars();
        
        // ==================== HEALTH SCORE ====================
        
        Integer healthScore = calculatePlatformHealthScore(
            activeTripsCount,
            openDisputesCount,
            suspendedUsersCount,
            pendingApprovalsCount
        );
        
        return DashboardKpiDto.builder()
            // Real-time
            .activeTripsCount(activeTripsCount != null ? activeTripsCount : 0L)
            .totalRevenueThisMonth(revenueThisMonth)
            .revenueGrowthPercent(revenueGrowth)
            .pendingApprovalsCount(pendingApprovalsCount)
            .openDisputesCount(openDisputesCount != null ? openDisputesCount : 0L)
            .suspendedUsersCount(suspendedUsersCount != null ? suspendedUsersCount : 0L)
            // Period metrics
            .newUsersThisMonth(newUsersThisMonth != null ? newUsersThisMonth : 0L)
            .userGrowthPercent(userGrowth)
            .bookingsThisMonth(bookingsThisMonth != null ? bookingsThisMonth : 0L)
            .bookingGrowthPercent(bookingGrowth)
            .completedTripsThisMonth(completedThisMonth != null ? completedThisMonth : 0L)
            // Platform totals
            .totalUsers(totalUsers)
            .totalActiveCars(totalActiveCars != null ? totalActiveCars : 0L)
            .totalBookings(totalBookings)
            // Health
            .platformHealthScore(healthScore)
            // Metadata
            .calculatedAt(now)
            .cacheTtlSeconds(0L) // Real-time, no cache
            .build();
    }
    
    /**
     * Save hourly metrics snapshot.
     * 
     * <p>Scheduled to run every hour at minute 0.
     * Stores current KPIs for historical trend analysis.
     */
    @Scheduled(cron = "0 0 * * * *", zone = "Europe/Belgrade") // Every hour at minute 0
    @Transactional
    public void saveMetricsSnapshot() {
        log.info("Saving hourly metrics snapshot");
        
        try {
            DashboardKpiDto kpis = getDashboardKpis();
            
            AdminMetrics snapshot = AdminMetrics.builder()
                .activeTripsCount(kpis.getActiveTripsCount().intValue())
                .totalRevenueCents(kpis.getTotalRevenueThisMonth()
                    .multiply(BigDecimal.valueOf(100)).longValue())
                .pendingApprovalsCount(kpis.getPendingApprovalsCount().intValue())
                .openDisputesCount(kpis.getOpenDisputesCount().intValue())
                .suspendedUsersCount(kpis.getSuspendedUsersCount().intValue())
                .newUsersCount(kpis.getNewUsersThisMonth().intValue())
                .completedBookingsCount(kpis.getCompletedTripsThisMonth().intValue())
                .totalUsersCount(kpis.getTotalUsers().intValue())
                .totalCarsCount(kpis.getTotalActiveCars().intValue())
                .revenueGrowthPercent(kpis.getRevenueGrowthPercent())
                .userGrowthPercent(kpis.getUserGrowthPercent())
                .bookingGrowthPercent(kpis.getBookingGrowthPercent())
                .snapshotDate(LocalDateTime.now())
                .build();
            
            metricsRepo.save(snapshot);
            
            log.info("Metrics snapshot saved: activeTrips={}, revenue={}, disputes={}",
                kpis.getActiveTripsCount(),
                kpis.getTotalRevenueThisMonth(),
                kpis.getOpenDisputesCount());
            
        } catch (Exception e) {
            log.error("Failed to save metrics snapshot: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Clean up old metrics (retention: 12 months).
     * 
     * <p>Scheduled to run daily at 3 AM.
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "Europe/Belgrade") // Daily at 3 AM
    @Transactional
    public void cleanupOldMetrics() {
        LocalDateTime cutoff = LocalDateTime.now().minusMonths(12);
        int deleted = metricsRepo.deleteOlderThan(cutoff);
        
        if (deleted > 0) {
            log.info("Cleaned up {} old metrics snapshots (older than {})", deleted, cutoff);
        }
    }
    
    // ==================== PRIVATE HELPERS ====================
    
    /**
     * Calculate revenue for a time period.
     * Sums totalAmount from completed bookings.
     */
    private BigDecimal calculateRevenueForPeriod(Instant start, Instant end) {
        return bookingRepo.sumTotalAmountByCompletedBookingsInPeriod(start, end);
    }
    
    /**
     * Count users registered in a specific period.
     */
    private Long countUsersInPeriod(Instant start, Instant end) {
        return adminUserRepo.countUsersBetween(start, end);
    }
    
    /**
     * Count bookings created in a specific period.
     */
    private Long countBookingsInPeriod(LocalDateTime start, LocalDateTime end) {
        return bookingRepo.countBookingsInPeriod(start, end);
    }
    
    /**
     * Calculate percentage growth between two values.
     * 
     * @param current Current period value
     * @param previous Previous period value
     * @return Growth percentage (can be negative)
     */
    private Double calculateGrowthPercent(Number current, Number previous) {
        if (previous == null || previous.doubleValue() == 0) {
            if (current != null && current.doubleValue() > 0) {
                return 100.0; // 100% growth from 0
            }
            return 0.0;
        }
        
        double currentVal = current != null ? current.doubleValue() : 0;
        double previousVal = previous.doubleValue();
        
        return ((currentVal - previousVal) / previousVal) * 100.0;
    }
    
    /**
     * Calculate growth percentage for BigDecimal values.
     */
    private Double calculateGrowthPercent(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
            if (current != null && current.compareTo(BigDecimal.ZERO) > 0) {
                return 100.0;
            }
            return 0.0;
        }
        
        BigDecimal currentVal = current != null ? current : BigDecimal.ZERO;
        BigDecimal growth = currentVal.subtract(previous)
            .divide(previous, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
        
        return growth.doubleValue();
    }
    
    /**
     * Calculate platform health score (0-100).
     * 
     * <p>Factors:
     * <ul>
     *   <li>Active trips: +40 points (more is better, up to baseline)</li>
     *   <li>Open disputes: -10 points per dispute (max -30)</li>
     *   <li>Suspended users: -5 points per user (max -20)</li>
     *   <li>Pending approvals: -5 points per approval (max -10)</li>
     * </ul>
     */
    private Integer calculatePlatformHealthScore(
            Long activeTrips,
            Long openDisputes,
            Long suspendedUsers,
            Long pendingApprovals) {
        
        int score = 100;
        
        // Add points for active trips (healthy platform has trips)
        if (activeTrips != null && activeTrips > 0) {
            // Already healthy, keep at 100
        } else {
            score -= 10; // No active trips suggests low activity
        }
        
        // Deduct for open disputes
        if (openDisputes != null && openDisputes > 0) {
            score -= Math.min(openDisputes.intValue() * 10, 30);
        }
        
        // Deduct for suspended users
        if (suspendedUsers != null && suspendedUsers > 0) {
            score -= Math.min(suspendedUsers.intValue() * 5, 20);
        }
        
        // Deduct for pending approvals (backlog)
        if (pendingApprovals != null && pendingApprovals > 0) {
            score -= Math.min(pendingApprovals.intValue() * 5, 10);
        }
        
        return Math.max(score, 0); // Minimum 0
    }

    /**
     * Get recent bookings for dashboard overview.
     * 
     * @param limit Maximum number of bookings to return
     * @return List of recent bookings with basic info
     */
    public List<RecentBookingDto> getRecentBookings(int limit) {
        PageRequest pageRequest = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        
        List<Booking> bookings = bookingRepo.findAll(pageRequest).getContent();
        
        return bookings.stream()
            .map(RecentBookingDto::fromEntity)
            .collect(Collectors.toList());
    }
}

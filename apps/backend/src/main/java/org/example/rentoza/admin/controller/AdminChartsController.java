package org.example.rentoza.admin.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.RevenueTrendDto;
import org.example.rentoza.admin.service.AdminAnalyticsService;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.config.timezone.SerbiaTimeZone;
import org.example.rentoza.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.*;

/**
 * Admin Charts Controller for dashboard visualizations.
 * 
 * <p><b>P1-5 FIX:</b> Provides real data for admin dashboard charts,
 * replacing frontend mock data.
 * 
 * <p><b>ENDPOINTS:</b>
 * <ul>
 *   <li>GET /api/admin/charts/revenue - Revenue chart data (monthly)</li>
 *   <li>GET /api/admin/charts/trips - Trip activity chart data (weekly)</li>
 * </ul>
 * 
 * <p><b>SECURITY:</b> All endpoints require ROLE_ADMIN.
 */
@RestController
@RequestMapping("/api/admin/charts")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminChartsController {
    
    private final AdminAnalyticsService analyticsService;
    private final BookingRepository bookingRepository;
    private final CurrentUser currentUser;
    
    /**
     * Get revenue chart data for the specified number of months.
     * 
     * <p>Returns data formatted for Chart.js line/bar charts.
     * 
     * @param months Number of months to include (3, 6, or 12)
     * @param currencyCode Currency code (default: RSD)
     * @return Revenue chart data with labels and values
     */
    @GetMapping("/revenue")
    public ResponseEntity<RevenueChartResponse> getRevenueChart(
            @RequestParam(defaultValue = "6") int months,
            @RequestParam(defaultValue = "RSD") String currencyCode) {
        
        log.debug("Admin {} requesting revenue chart for {} months", currentUser.id(), months);
        
        // Calculate date range
        LocalDate endDate = SerbiaTimeZone.today();
        LocalDate startDate = endDate.minusMonths(months - 1).withDayOfMonth(1);
        
        // Get revenue trend from analytics service
        RevenueTrendDto trend = analyticsService.getRevenueTrend("MONTHLY", startDate, endDate);
        
        // Convert to chart format
        List<String> labels = new ArrayList<>();
        List<Double> totalRevenue = new ArrayList<>();
        
        Locale serbianLocale = new Locale("sr", "RS");
        
        for (RevenueTrendDto.DataPoint dp : trend.getDataPoints()) {
            // Format month label (e.g., "Jan", "Feb")
            String monthLabel = dp.getDate().getMonth()
                    .getDisplayName(TextStyle.SHORT, serbianLocale);
            labels.add(monthLabel);
            totalRevenue.add(dp.getRevenue().doubleValue());
        }
        
        return ResponseEntity.ok(new RevenueChartResponse(labels, totalRevenue, currencyCode));
    }
    
    /**
     * Get trip activity chart data for the specified number of weeks.
     * 
     * <p>Returns completed and canceled trip counts per week.
     * 
     * @param weeks Number of weeks to include (default: 6)
     * @return Trip activity data with labels and counts
     */
    @GetMapping("/trips")
    public ResponseEntity<TripActivityResponse> getTripActivity(
            @RequestParam(defaultValue = "6") int weeks) {
        
        log.debug("Admin {} requesting trip activity for {} weeks", currentUser.id(), weeks);
        
        LocalDate today = SerbiaTimeZone.today();
        
        // Calculate start of first week
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        LocalDate rangeStart = weekStart.minusWeeks(weeks - 1);
        
        LocalDateTime startDateTime = rangeStart.atStartOfDay();
        LocalDateTime endDateTime = today.atTime(23, 59, 59, 999999999);
        
        // Fetch completed bookings
        List<Booking> completedBookings = bookingRepository.findByStatusAndApprovedAtBetween(
            BookingStatus.COMPLETED, startDateTime, endDateTime
        );
        
        // Fetch cancelled bookings
        List<Booking> cancelledBookings = bookingRepository.findByStatusAndApprovedAtBetween(
            BookingStatus.CANCELLED, startDateTime, endDateTime
        );
        
        // Group by week
        Map<LocalDate, Long> completedByWeek = groupBookingsByWeek(completedBookings);
        Map<LocalDate, Long> cancelledByWeek = groupBookingsByWeek(cancelledBookings);
        
        // Build response
        List<String> labels = new ArrayList<>();
        List<Integer> completedTrips = new ArrayList<>();
        List<Integer> canceledTrips = new ArrayList<>();
        
        Locale serbianLocale = new Locale("sr", "RS");
        
        for (int i = 0; i < weeks; i++) {
            LocalDate weekDate = rangeStart.plusWeeks(i);
            
            // Format label (e.g., "Jan N1", "Feb N2")
            String monthLabel = weekDate.getMonth()
                    .getDisplayName(TextStyle.SHORT, serbianLocale);
            int weekOfMonth = (weekDate.getDayOfMonth() - 1) / 7 + 1;
            labels.add(monthLabel + " N" + weekOfMonth);
            
            LocalDate normalizedWeek = weekDate.with(DayOfWeek.MONDAY);
            completedTrips.add(completedByWeek.getOrDefault(normalizedWeek, 0L).intValue());
            canceledTrips.add(cancelledByWeek.getOrDefault(normalizedWeek, 0L).intValue());
        }
        
        return ResponseEntity.ok(new TripActivityResponse(labels, completedTrips, canceledTrips));
    }
    
    private Map<LocalDate, Long> groupBookingsByWeek(List<Booking> bookings) {
        Map<LocalDate, Long> byWeek = new HashMap<>();
        
        for (Booking booking : bookings) {
            if (booking.getApprovedAt() != null) {
                LocalDate weekStart = booking.getApprovedAt().toLocalDate().with(DayOfWeek.MONDAY);
                byWeek.merge(weekStart, 1L, Long::sum);
            }
        }
        
        return byWeek;
    }
    
    // ========== Response DTOs ==========
    
    public record RevenueChartResponse(
        List<String> labels,
        List<Double> totalRevenue,
        String currencyCode
    ) {}
    
    public record TripActivityResponse(
        List<String> labels,
        List<Integer> completedTrips,
        List<Integer> canceledTrips
    ) {}
}

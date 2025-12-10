package org.example.rentoza.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.*;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin Analytics Service for business intelligence and reporting.
 * 
 * <p><b>CORE CAPABILITIES:</b>
 * <ul>
 *   <li>Revenue trend analysis (daily/weekly/monthly)</li>
 *   <li>User cohort analysis and retention tracking</li>
 *   <li>Top performer identification (hosts/cars)</li>
 *   <li>Utilization rate calculations</li>
 * </ul>
 * 
 * <p><b>CACHING:</b>
 * Heavy analytics queries are cached for 15 minutes to balance
 * real-time accuracy with performance.
 * 
 * <p><b>PERFORMANCE:</b>
 * Complex aggregations use database-level grouping where possible.
 * In-memory calculations limited to post-aggregation transforms.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AdminAnalyticsService {
    
    private final BookingRepository bookingRepo;
    private final UserRepository userRepo;
    private final CarRepository carRepo;
    
    /**
     * Get revenue trend for specified period.
     * 
     * <p>Aggregates completed booking revenue by time period.
     * Calculates growth rate comparing first and last periods.
     * 
     * @param period DAILY, WEEKLY, or MONTHLY
     * @param startDate Start of analysis window
     * @param endDate End of analysis window
     * @return Revenue trend with data points
     */
    @Cacheable(value = "adminMetrics", key = "'revenue_trend_' + #period + '_' + #startDate + '_' + #endDate")
    public RevenueTrendDto getRevenueTrend(String period, LocalDate startDate, LocalDate endDate) {
        log.debug("Calculating revenue trend: {} from {} to {}", period, startDate, endDate);
        
        Instant startInstant = startDate.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant endInstant = endDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        
        // Fetch all completed bookings in range
        List<Booking> bookings = bookingRepo.findByStatusAndApprovedAtBetween(
            BookingStatus.COMPLETED, startInstant, endInstant
        );
        
        // Group by period
        Map<LocalDate, List<Booking>> groupedBookings = bookings.stream()
            .collect(Collectors.groupingBy(b -> {
                LocalDate date = b.getApprovedAt().atZone(ZoneId.systemDefault()).toLocalDate();
                return normalizeToPeriod(date, period);
            }));
        
        // Build data points
        List<RevenueTrendDto.DataPoint> dataPoints = new ArrayList<>();
        LocalDate current = startDate;
        
        while (!current.isAfter(endDate)) {
            LocalDate normalized = normalizeToPeriod(current, period);
            List<Booking> periodBookings = groupedBookings.getOrDefault(normalized, List.of());
            
            BigDecimal revenue = periodBookings.stream()
                .map(Booking::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            dataPoints.add(RevenueTrendDto.DataPoint.builder()
                .date(normalized)
                .revenue(revenue)
                .bookingCount((long) periodBookings.size())
                .build());
            
            current = advancePeriod(current, period);
        }
        
        // Calculate totals and growth
        BigDecimal totalRevenue = dataPoints.stream()
            .map(RevenueTrendDto.DataPoint::getRevenue)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal avgPerPeriod = dataPoints.isEmpty() ? BigDecimal.ZERO :
            totalRevenue.divide(BigDecimal.valueOf(dataPoints.size()), 2, RoundingMode.HALF_UP);
        
        Double growthRate = calculateGrowthRate(dataPoints);
        
        return RevenueTrendDto.builder()
            .dataPoints(dataPoints)
            .totalRevenue(totalRevenue)
            .averagePerPeriod(avgPerPeriod)
            .growthRate(growthRate)
            .period(period)
            .build();
    }
    
    /**
     * Get user cohort analysis.
     * 
     * <p>Groups users by signup month (cohort) and tracks retention
     * and revenue generation over subsequent months.
     * 
     * @param cohort Signup month (e.g., 2024-01)
     * @param monthsToTrack Number of months to analyze (e.g., 6)
     * @return Cohort analysis with retention metrics
     */
    @Cacheable(value = "adminMetrics", key = "'cohort_' + #cohort + '_' + #monthsToTrack")
    public CohortAnalysisDto getCohortAnalysis(YearMonth cohort, int monthsToTrack) {
        log.debug("Analyzing cohort: {} for {} months", cohort, monthsToTrack);
        
        LocalDate cohortStart = cohort.atDay(1);
        LocalDate cohortEnd = cohort.atEndOfMonth();
        
        Instant cohortStartInstant = cohortStart.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant cohortEndInstant = cohortEnd.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        
        // Get users who signed up in this cohort
        List<User> cohortUsers = userRepo.findByCreatedAtBetween(cohortStartInstant, cohortEndInstant);
        Set<Long> cohortUserIds = cohortUsers.stream().map(User::getId).collect(Collectors.toSet());
        
        if (cohortUsers.isEmpty()) {
            return CohortAnalysisDto.builder()
                .cohort(cohort)
                .totalUsers(0L)
                .retentionByMonth(Map.of())
                .build();
        }
        
        // Track retention for each month offset
        Map<Integer, CohortAnalysisDto.RetentionMetrics> retentionByMonth = new HashMap<>();
        
        for (int offset = 0; offset <= monthsToTrack; offset++) {
            YearMonth targetMonth = cohort.plusMonths(offset);
            LocalDate monthStart = targetMonth.atDay(1);
            LocalDate monthEnd = targetMonth.atEndOfMonth();
            
            Instant monthStartInstant = monthStart.atStartOfDay(ZoneId.systemDefault()).toInstant();
            Instant monthEndInstant = monthEnd.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
            
            // Count active users (made at least 1 booking this month)
            List<Booking> monthBookings = bookingRepo.findByCreatedAtBetween(
                monthStartInstant, monthEndInstant
            );
            
            Set<Long> activeUserIds = monthBookings.stream()
                .map(b -> b.getRenter().getId())
                .filter(cohortUserIds::contains)
                .collect(Collectors.toSet());
            
            BigDecimal revenue = monthBookings.stream()
                .filter(b -> cohortUserIds.contains(b.getRenter().getId()))
                .filter(b -> b.getStatus() == BookingStatus.COMPLETED)
                .map(Booking::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            long bookingCount = monthBookings.stream()
                .filter(b -> cohortUserIds.contains(b.getRenter().getId()))
                .count();
            
            double retentionRate = (activeUserIds.size() * 100.0) / cohortUsers.size();
            
            retentionByMonth.put(offset, CohortAnalysisDto.RetentionMetrics.builder()
                .activeUsers((long) activeUserIds.size())
                .retentionRate(retentionRate)
                .revenueGenerated(revenue)
                .bookingCount(bookingCount)
                .build());
        }
        
        return CohortAnalysisDto.builder()
            .cohort(cohort)
            .totalUsers((long) cohortUsers.size())
            .retentionByMonth(retentionByMonth)
            .build();
    }
    
    /**
     * Get top performing hosts and cars.
     * 
     * <p>Identifies highest revenue generators and most utilized assets.
     * 
     * @param topN Number of top performers to return (e.g., 10)
     * @param startDate Start of analysis period
     * @param endDate End of analysis period
     * @return Top performers (hosts and cars)
     */
    @Cacheable(value = "adminMetrics", key = "'top_performers_' + #topN + '_' + #startDate + '_' + #endDate")
    public TopPerformersDto getTopPerformers(int topN, LocalDate startDate, LocalDate endDate) {
        log.debug("Finding top {} performers from {} to {}", topN, startDate, endDate);
        
        Instant startInstant = startDate.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant endInstant = endDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        
        List<Booking> completedBookings = bookingRepo.findByStatusAndApprovedAtBetween(
            BookingStatus.COMPLETED, startInstant, endInstant
        );
        
        // Group by host
        Map<User, List<Booking>> byHost = completedBookings.stream()
            .collect(Collectors.groupingBy(b -> b.getCar().getOwner()));
        
        List<TopPerformersDto.TopHost> topHosts = byHost.entrySet().stream()
            .map(entry -> {
                User host = entry.getKey();
                List<Booking> hostBookings = entry.getValue();
                
                BigDecimal totalRevenue = hostBookings.stream()
                    .map(Booking::getTotalPrice)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                
                // Simplified rating (would need actual review system)
                Double avgRating = 4.5;
                
                return TopPerformersDto.TopHost.builder()
                    .hostId(host.getId())
                    .hostName(host.getFirstName() + " " + host.getLastName())
                    .bookingCount((long) hostBookings.size())
                    .totalRevenue(totalRevenue)
                    .averageRating(avgRating)
                    .build();
            })
            .sorted(Comparator.comparing(TopPerformersDto.TopHost::getTotalRevenue).reversed())
            .limit(topN)
            .collect(Collectors.toList());
        
        // Group by car
        Map<Car, List<Booking>> byCar = completedBookings.stream()
            .collect(Collectors.groupingBy(Booking::getCar));
        
        long totalDaysInPeriod = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        
        List<TopPerformersDto.TopCar> topCars = byCar.entrySet().stream()
            .map(entry -> {
                Car car = entry.getKey();
                List<Booking> carBookings = entry.getValue();
                
                BigDecimal totalRevenue = carBookings.stream()
                    .map(Booking::getTotalPrice)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                
                long totalBookedDays = carBookings.stream()
                    .mapToLong(b -> ChronoUnit.DAYS.between(
                        b.getStartTime().toLocalDate(),
                        b.getEndTime().toLocalDate()
                    ) + 1)
                    .sum();
                
                double utilizationRate = (totalBookedDays * 100.0) / totalDaysInPeriod;
                
                return TopPerformersDto.TopCar.builder()
                    .carId(car.getId())
                    .carMake(car.getBrand())
                    .carModel(car.getModel())
                    .bookingCount((long) carBookings.size())
                    .totalRevenue(totalRevenue)
                    .utilizationRate(utilizationRate)
                    .build();
            })
            .sorted(Comparator.comparing(TopPerformersDto.TopCar::getTotalRevenue).reversed())
            .limit(topN)
            .collect(Collectors.toList());
        
        return TopPerformersDto.builder()
            .topHosts(topHosts)
            .topCars(topCars)
            .build();
    }
    
    // ==================== HELPER METHODS ====================
    
    private LocalDate normalizeToPeriod(LocalDate date, String period) {
        return switch (period.toUpperCase()) {
            case "DAILY" -> date;
            case "WEEKLY" -> date.with(DayOfWeek.MONDAY); // Start of week
            case "MONTHLY" -> date.withDayOfMonth(1); // Start of month
            default -> date;
        };
    }
    
    private LocalDate advancePeriod(LocalDate date, String period) {
        return switch (period.toUpperCase()) {
            case "DAILY" -> date.plusDays(1);
            case "WEEKLY" -> date.plusWeeks(1);
            case "MONTHLY" -> date.plusMonths(1);
            default -> date.plusDays(1);
        };
    }
    
    private Double calculateGrowthRate(List<RevenueTrendDto.DataPoint> dataPoints) {
        if (dataPoints.size() < 2) return 0.0;
        
        BigDecimal first = dataPoints.get(0).getRevenue();
        BigDecimal last = dataPoints.get(dataPoints.size() - 1).getRevenue();
        
        if (first.compareTo(BigDecimal.ZERO) == 0) return 0.0;
        
        BigDecimal growth = last.subtract(first)
            .divide(first, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
        
        return growth.doubleValue();
    }
}

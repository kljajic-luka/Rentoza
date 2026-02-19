package org.example.rentoza.owner;

import lombok.RequiredArgsConstructor;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.dto.BookingResponseDTO;
import org.example.rentoza.booking.cancellation.HostCancellationStats;
import org.example.rentoza.booking.cancellation.HostCancellationStatsRepository;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.owner.dto.HostCancellationStatsDTO;
import org.example.rentoza.owner.dto.OwnerEarningsDTO;
import org.example.rentoza.owner.dto.OwnerStatsDTO;
import org.example.rentoza.review.Review;
import org.example.rentoza.review.ReviewDirection;
import org.example.rentoza.review.ReviewRepository;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OwnerService {

    private final UserRepository userRepo;
    private final CarRepository carRepo;
    private final BookingRepository bookingRepo;
    private final ReviewRepository reviewRepo;
    private final HostCancellationStatsRepository cancellationStatsRepo;

    /**
     * Get comprehensive statistics for owner dashboard
     * Optimized to avoid N+1 query problems
     */
    @Transactional(readOnly = true)
    public OwnerStatsDTO getOwnerStats(String ownerEmail) {
        User owner = userRepo.findByEmail(ownerEmail)
                .orElseThrow(() -> new RuntimeException("Owner not found"));

        // 1. Count total cars owned by this owner
        List<Car> ownerCars = carRepo.findByOwner(owner);
        int totalCars = ownerCars.size();

        // 2. Fetch all bookings for all owner's cars in a single query (avoids N+1)
        List<Booking> allBookings = bookingRepo.findByCarOwnerIdWithDetails(owner.getId());
        int totalBookings = allBookings.size();

        // 3. Calculate monthly earnings (current month only)
        LocalDate startOfMonth = LocalDate.now().withDayOfMonth(1);
        BigDecimal monthlyEarnings = allBookings.stream()
                .filter(booking -> (booking.getStatus() == BookingStatus.COMPLETED ||
                                   booking.getStatus() == BookingStatus.ACTIVE) &&
                                   booking.getStartDate() != null &&
                                   !booking.getStartDate().isBefore(startOfMonth))
                .map(Booking::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 4. Calculate average rating from visible reviews received by owner
        // P0-2 FIX: Visibility-filtered rating (double-blind enforcement)
        java.time.Instant visibilityTimeout = java.time.Instant.now().minus(14, java.time.temporal.ChronoUnit.DAYS);
        Double rawRating = reviewRepo.findVisibleAverageRatingForReviewee(
                owner.getId(), ReviewDirection.FROM_USER, visibilityTimeout);
        double averageRating = rawRating != null ? Math.round(rawRating * 10.0) / 10.0 : 0.0;

        return new OwnerStatsDTO(totalCars, totalBookings, monthlyEarnings, averageRating);
    }

    /**
     * Get all bookings for owner's cars
     * Optimized to avoid N+1 query problems
     */
    @Transactional(readOnly = true)
    public List<BookingResponseDTO> getOwnerBookings(String ownerEmail) {
        User owner = userRepo.findByEmail(ownerEmail)
                .orElseThrow(() -> new RuntimeException("Owner not found"));

        // Fetch all bookings for all owner's cars in a single query (avoids N+1)
        List<Booking> allBookings = bookingRepo.findByCarOwnerIdWithDetails(owner.getId());

        // Sort by start date (newest first)
        allBookings.sort((b1, b2) -> {
            if (b2.getStartDate() == null) return -1;
            if (b1.getStartDate() == null) return 1;
            return b2.getStartDate().compareTo(b1.getStartDate());
        });

        // Batch fetch owner reviews for all bookings (avoids N+1)
        List<Long> bookingIds = allBookings.stream().map(Booking::getId).toList();
        List<Review> ownerReviews = reviewRepo.findByBookingIdInAndDirection(bookingIds, ReviewDirection.FROM_OWNER);
        Map<Long, Boolean> hasOwnerReviewMap = new HashMap<>();
        for (Review review : ownerReviews) {
            hasOwnerReviewMap.put(review.getBooking().getId(), true);
        }

        // Convert to DTOs and set review flags
        List<BookingResponseDTO> result = new ArrayList<>();
        for (Booking booking : allBookings) {
            BookingResponseDTO dto = new BookingResponseDTO(booking);
            dto.setHasOwnerReview(hasOwnerReviewMap.getOrDefault(booking.getId(), false));
            result.add(dto);
        }

        return result;
    }

    /**
     * Get owner earnings breakdown
     * Optimized to avoid N+1 query problems
     */
    @Transactional(readOnly = true)
    public OwnerEarningsDTO getOwnerEarnings(String ownerEmail) {
        User owner = userRepo.findByEmail(ownerEmail)
                .orElseThrow(() -> new RuntimeException("Owner not found"));

        // Fetch all bookings for all owner's cars in a single query (avoids N+1)
        List<Booking> allBookings = bookingRepo.findByCarOwnerIdWithDetails(owner.getId());

        // Calculate time boundaries
        LocalDate startOfMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate startOfYear = LocalDate.now().withDayOfYear(1);

        BigDecimal totalEarnings = BigDecimal.ZERO;
        BigDecimal monthlyEarnings = BigDecimal.ZERO;
        BigDecimal yearlyEarnings = BigDecimal.ZERO;
        int totalBookings = 0;

        // Use a helper class to accumulate both aggregated data and individual booking details
        class CarEarningAccumulator {
            Long carId;
            String carBrand;
            String carModel;
            BigDecimal totalEarnings = BigDecimal.ZERO;
            int bookingCount = 0;
            List<OwnerEarningsDTO.BookingDetailDTO> bookingDetails = new ArrayList<>();
        }

        Map<Long, CarEarningAccumulator> carEarningsMap = new HashMap<>();

        for (Booking booking : allBookings) {
            if (booking.getStatus() == BookingStatus.COMPLETED || booking.getStatus() == BookingStatus.ACTIVE) {
                Car car = booking.getCar();
                BigDecimal price = booking.getTotalPrice();

                totalEarnings = totalEarnings.add(price);
                totalBookings++;

                if (booking.getStartDate() != null && !booking.getStartDate().isBefore(startOfMonth)) {
                    monthlyEarnings = monthlyEarnings.add(price);
                }
                if (booking.getStartDate() != null && !booking.getStartDate().isBefore(startOfYear)) {
                    yearlyEarnings = yearlyEarnings.add(price);
                }

                // Update per-car earnings with individual booking details
                CarEarningAccumulator accumulator = carEarningsMap.get(car.getId());
                if (accumulator == null) {
                    accumulator = new CarEarningAccumulator();
                    accumulator.carId = car.getId();
                    accumulator.carBrand = car.getBrand();
                    accumulator.carModel = car.getModel();
                    carEarningsMap.put(car.getId(), accumulator);
                }

                // Add to aggregated data
                accumulator.totalEarnings = accumulator.totalEarnings.add(price);
                accumulator.bookingCount++;

                // Add individual booking detail
                accumulator.bookingDetails.add(new OwnerEarningsDTO.BookingDetailDTO(
                        booking.getId(),
                        booking.getStartDate(),
                        booking.getEndDate(),
                        booking.getTotalPrice(),
                        booking.getStatus().toString()
                ));
            }
        }

        // Convert accumulators to DTOs
        List<OwnerEarningsDTO.CarEarningDTO> carEarningsList = carEarningsMap.values().stream()
                .map(acc -> new OwnerEarningsDTO.CarEarningDTO(
                        acc.carId,
                        acc.carBrand,
                        acc.carModel,
                        acc.totalEarnings,
                        acc.bookingCount,
                        acc.bookingDetails
                ))
                .sorted((c1, c2) -> c2.getEarnings().compareTo(c1.getEarnings()))
                .toList();

        return new OwnerEarningsDTO(totalEarnings, monthlyEarnings, yearlyEarnings, totalBookings, carEarningsList);
    }

    /**
     * Get host cancellation statistics for penalty tier display.
     * 
     * <p>Returns the host's current cancellation standing including:
     * <ul>
     *   <li>Cancellation counts (yearly, 30-day rolling)</li>
     *   <li>Current penalty tier (0, 1, 2, 3+)</li>
     *   <li>Next penalty amount if they cancel again</li>
     *   <li>Suspension status (if applicable)</li>
     * </ul>
     * 
     * <p>If the host has no cancellation history (new host), returns
     * a zero-valued DTO with tier 0 and first-offence penalty amount.
     * 
     * @param hostId Host user ID
     * @return HostCancellationStatsDTO with all tracking data
     */
    @Transactional(readOnly = true)
    public HostCancellationStatsDTO getHostCancellationStats(Long hostId) {
        // Find existing stats or return empty DTO for new hosts
        return cancellationStatsRepo.findByHostId(hostId)
                .map(HostCancellationStatsDTO::fromEntity)
                .orElseGet(() -> HostCancellationStatsDTO.fromEntity(null));
    }
}

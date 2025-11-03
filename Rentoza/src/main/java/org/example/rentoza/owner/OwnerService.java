package org.example.rentoza.owner;

import lombok.RequiredArgsConstructor;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.dto.BookingResponseDTO;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.owner.dto.OwnerEarningsDTO;
import org.example.rentoza.owner.dto.OwnerStatsDTO;
import org.example.rentoza.review.Review;
import org.example.rentoza.review.ReviewDirection;
import org.example.rentoza.review.ReviewRepository;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Get comprehensive statistics for owner dashboard
     */
    @Transactional(readOnly = true)
    public OwnerStatsDTO getOwnerStats(String ownerEmail) {
        User owner = userRepo.findByEmail(ownerEmail)
                .orElseThrow(() -> new RuntimeException("Owner not found"));

        // 1. Count total cars owned by this owner
        List<Car> ownerCars = carRepo.findByOwner(owner);
        int totalCars = ownerCars.size();

        // 2. Count total bookings across all owner's cars
        int totalBookings = 0;
        for (Car car : ownerCars) {
            totalBookings += bookingRepo.findByCar(car).size();
        }

        // 3. Calculate monthly earnings (current month only)
        LocalDate startOfMonth = LocalDate.now().withDayOfMonth(1);

        double monthlyEarnings = 0.0;
        for (Car car : ownerCars) {
            List<Booking> carBookings = bookingRepo.findByCar(car);
            for (Booking booking : carBookings) {
                // Count only COMPLETED or ACTIVE bookings from current month
                if ((booking.getStatus() == BookingStatus.COMPLETED || booking.getStatus() == BookingStatus.ACTIVE)
                        && booking.getStartDate() != null
                        && !booking.getStartDate().isBefore(startOfMonth)) {
                    monthlyEarnings += booking.getTotalPrice();
                }
            }
        }

        // 4. Calculate average rating from reviews received by owner
        List<Review> receivedReviews = reviewRepo.findByReviewee(owner);
        double averageRating = 0.0;
        if (!receivedReviews.isEmpty()) {
            double totalRating = receivedReviews.stream()
                    .mapToInt(Review::getRating)
                    .average()
                    .orElse(0.0);
            averageRating = Math.round(totalRating * 10.0) / 10.0; // Round to 1 decimal
        }

        return new OwnerStatsDTO(totalCars, totalBookings, monthlyEarnings, averageRating);
    }

    /**
     * Get all bookings for owner's cars
     */
    @Transactional(readOnly = true)
    public List<BookingResponseDTO> getOwnerBookings(String ownerEmail) {
        User owner = userRepo.findByEmail(ownerEmail)
                .orElseThrow(() -> new RuntimeException("Owner not found"));

        List<Car> ownerCars = carRepo.findByOwner(owner);
        List<Booking> allBookings = new ArrayList<>();

        for (Car car : ownerCars) {
            allBookings.addAll(bookingRepo.findByCar(car));
        }

        // Sort by start date (newest first)
        allBookings.sort((b1, b2) -> {
            if (b2.getStartDate() == null) return -1;
            if (b1.getStartDate() == null) return 1;
            return b2.getStartDate().compareTo(b1.getStartDate());
        });

        // Convert to DTOs and check for owner reviews
        List<BookingResponseDTO> result = new ArrayList<>();
        for (Booking booking : allBookings) {
            BookingResponseDTO dto = new BookingResponseDTO(booking);
            // Check if owner has already reviewed this booking
            dto.setHasOwnerReview(reviewRepo.existsByBookingAndDirection(booking, ReviewDirection.FROM_OWNER));
            result.add(dto);
        }

        return result;
    }

    /**
     * Get owner earnings breakdown
     */
    @Transactional(readOnly = true)
    public OwnerEarningsDTO getOwnerEarnings(String ownerEmail) {
        User owner = userRepo.findByEmail(ownerEmail)
                .orElseThrow(() -> new RuntimeException("Owner not found"));

        List<Car> ownerCars = carRepo.findByOwner(owner);

        // Calculate time boundaries
        LocalDate startOfMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate startOfYear = LocalDate.now().withDayOfYear(1);

        double totalEarnings = 0.0;
        double monthlyEarnings = 0.0;
        double yearlyEarnings = 0.0;
        int totalBookings = 0;

        Map<Long, OwnerEarningsDTO.CarEarningDTO> carEarningsMap = new HashMap<>();

        for (Car car : ownerCars) {
            List<Booking> carBookings = bookingRepo.findByCar(car);
            double carTotal = 0.0;
            int carBookingCount = 0;

            for (Booking booking : carBookings) {
                if (booking.getStatus() == BookingStatus.COMPLETED || booking.getStatus() == BookingStatus.ACTIVE) {
                    double price = booking.getTotalPrice();
                    totalEarnings += price;
                    carTotal += price;
                    carBookingCount++;
                    totalBookings++;

                    if (booking.getStartDate() != null && !booking.getStartDate().isBefore(startOfMonth)) {
                        monthlyEarnings += price;
                    }
                    if (booking.getStartDate() != null && !booking.getStartDate().isBefore(startOfYear)) {
                        yearlyEarnings += price;
                    }
                }
            }

            if (carBookingCount > 0) {
                carEarningsMap.put(car.getId(), new OwnerEarningsDTO.CarEarningDTO(
                        car.getId(),
                        car.getBrand(),
                        car.getModel(),
                        carTotal,
                        carBookingCount
                ));
            }
        }

        List<OwnerEarningsDTO.CarEarningDTO> carEarningsList = new ArrayList<>(carEarningsMap.values());
        // Sort by earnings (highest first)
        carEarningsList.sort((c1, c2) -> Double.compare(c2.getEarnings(), c1.getEarnings()));

        return new OwnerEarningsDTO(totalEarnings, monthlyEarnings, yearlyEarnings, totalBookings, carEarningsList);
    }
}

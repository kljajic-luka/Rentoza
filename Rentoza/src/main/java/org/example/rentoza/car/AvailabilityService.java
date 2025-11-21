package org.example.rentoza.car;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingTimeUtil;
import org.example.rentoza.car.dto.AvailabilitySearchRequestDTO;
import org.hibernate.Hibernate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Service for time-aware availability searches.
 *
 * Purpose:
 * - Search cars by location AND date/time availability
 * - Filter out cars with overlapping bookings
 * - Support pagination for large result sets
 *
 * Design:
 * - Uses existing CarRepository for location-based filtering
 * - Uses BookingRepository to fetch active/completed bookings
 * - Uses BookingTimeUtil to derive effective booking DateTimes
 * - Performs time-aware overlap detection in service layer (not JPQL)
 *
 * Rationale for Service-Layer Filtering:
 * - Deriving DateTime from time windows requires business logic
 * - Complex JPQL/Criteria queries would be fragile and hard to test
 * - Service-layer approach is more maintainable and testable
 *
 * Performance Considerations:
 * - Location filtering uses indexed column (car.location)
 * - Booking fetch is optimized (only ACTIVE/COMPLETED statuses)
 * - Stream-based filtering is efficient for typical result sizes (< 100 cars per location)
 * - For high-volume scenarios, consider caching or materialized views
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AvailabilityService {

    private final CarRepository carRepository;
    private final BookingRepository bookingRepository;
    private final BookingTimeUtil bookingTimeUtil;

    /**
     * Search for cars available in a specific location and time range.
     *
     * Algorithm:
     * 1. Fetch all cars in the requested location (filtered by available=true)
     * 2. For each car, check if it's available in the requested time range
     * 3. Filter out cars with overlapping bookings
     * 4. Apply pagination to results
     *
     * @param request Validated AvailabilitySearchRequestDTO
     * @return Paginated list of available cars
     */
    @Transactional(readOnly = true)
    public Page<Car> searchAvailableCars(AvailabilitySearchRequestDTO request) {
        log.debug("[AvailabilityService] Searching cars: location={}, startDateTime={}, endDateTime={}",
            request.getNormalizedLocation(),
            request.getStartDateTime(),
            request.getEndDateTime()
        );

        // Step 1: Find all cars in location (available=true)
        String normalizedLocation = request.getNormalizedLocation();
        List<Car> carsInLocation = carRepository.findAvailableWithDetailsByLocation(normalizedLocation);

        log.debug("[AvailabilityService] Found {} cars in location '{}'", carsInLocation.size(), normalizedLocation);

        // Initialize additional bags to avoid lazy loading outside the transaction
        carsInLocation.forEach(car -> {
            Hibernate.initialize(car.getAddOns());
            Hibernate.initialize(car.getImageUrls());
        });

        // Step 2: Filter by time-based availability
        LocalDateTime requestedStart = request.getStartDateTime();
        LocalDateTime requestedEnd = request.getEndDateTime();

        List<Car> availableCars = carsInLocation.stream()
            .filter(car -> isCarAvailableInTimeRange(car, requestedStart, requestedEnd))
            .toList();

        log.debug("[AvailabilityService] After time filtering: {} available cars", availableCars.size());

        // Step 3: Apply pagination
        Pageable pageable = PageRequest.of(request.getPage(), request.getSize());

        // Calculate pagination indices
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), availableCars.size());

        // Extract page slice
        List<Car> pageContent = (start < availableCars.size())
            ? availableCars.subList(start, end)
            : List.of();

        // Create Page object with total count supplier
        Page<Car> resultPage = PageableExecutionUtils.getPage(
            pageContent,
            pageable,
            availableCars::size
        );

        log.info("[AvailabilityService] Returning page {}/{} with {} cars",
            resultPage.getNumber() + 1,
            resultPage.getTotalPages(),
            resultPage.getNumberOfElements()
        );

        return resultPage;
    }

    /**
     * Check if a car is available in the requested time range.
     *
     * Algorithm:
     * 1. Fetch all confirmed bookings for the car (ACTIVE, COMPLETED)
     * 2. For each booking, derive effective start/end DateTime using BookingTimeUtil
     * 3. Check for time-aware overlap with requested range
     * 4. Return false if ANY overlap exists, true otherwise
     *
     * Overlap Condition:
     * (requestedStart < bookingEnd) AND (requestedEnd > bookingStart)
     *
     * Edge Cases:
     * - Booking ends exactly when request starts → NO overlap (allowed)
     * - Request ends exactly when booking starts → NO overlap (allowed)
     * - Same-day bookings with different time windows → handled correctly
     *
     * @param car Car entity to check
     * @param requestedStart Requested rental start DateTime
     * @param requestedEnd Requested rental end DateTime
     * @return true if car is available, false if any booking overlaps
     */
    private boolean isCarAvailableInTimeRange(
        Car car,
        LocalDateTime requestedStart,
        LocalDateTime requestedEnd
    ) {
        // Fetch all confirmed bookings for this car
        // Uses BookingRepository.findPublicBookingsForCar which returns ACTIVE + COMPLETED bookings only
        List<Booking> confirmedBookings = bookingRepository.findPublicBookingsForCar(car.getId());

        // Check for overlaps
        boolean hasOverlap = confirmedBookings.stream().anyMatch(booking -> {
            try {
                // Derive effective booking DateTimes
                LocalDateTime bookingStart = bookingTimeUtil.derivePickupDateTime(booking);
                LocalDateTime bookingEnd = bookingTimeUtil.deriveDropoffDateTime(booking);

                // Overlap condition: (requestedStart < bookingEnd) AND (requestedEnd > bookingStart)
                boolean overlaps = requestedStart.isBefore(bookingEnd) && requestedEnd.isAfter(bookingStart);

                if (overlaps) {
                    log.debug("[AvailabilityService] Overlap detected for carId={}, bookingId={}: " +
                        "requested=[{} to {}], booking=[{} to {}]",
                        car.getId(), booking.getId(),
                        requestedStart, requestedEnd,
                        bookingStart, bookingEnd
                    );
                }

                return overlaps;
            } catch (Exception e) {
                // Log error but don't fail entire search (defensive programming)
                log.error("[AvailabilityService] Error deriving booking times for bookingId={}: {}",
                    booking.getId(), e.getMessage(), e);
                // Treat as potential conflict (err on side of caution)
                return true;
            }
        });

        return !hasOverlap; // Available if no overlaps
    }
}

package org.example.rentoza.car;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.availability.BlockedDate;
import org.example.rentoza.availability.BlockedDateRepository;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingTimeUtil;
import org.example.rentoza.booking.util.BookingDurationPolicy;
import org.example.rentoza.car.dto.AvailabilitySearchRequestDTO;
import org.example.rentoza.car.dto.UnavailableRangeDTO;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.hibernate.Hibernate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.example.rentoza.review.ReviewDirection;
import org.example.rentoza.review.ReviewRepository;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final BlockedDateRepository blockedDateRepository;
    private final BookingTimeUtil bookingTimeUtil;
    private final ReviewRepository reviewRepository;
    private final MarketplaceComplianceService marketplaceComplianceService;

    /**
     * Search for cars available in a specific location and time range.
     *
     * Algorithm (P2 batch-optimized):
     * 1. Fetch candidate cars (geospatial or location-string based)
     * 2. Batch-query blocked-date and booking overlaps for ALL candidates at once
     *    (replaces per-car N+1 calls)
     * 3. Apply rental-duration and filter criteria
     * 4. Sort and paginate
     *
     * @param request Validated AvailabilitySearchRequestDTO
     * @return Paginated list of available cars
     */
    @Transactional(readOnly = true)
    public Page<Car> searchAvailableCars(AvailabilitySearchRequestDTO request) {
        log.debug("[AvailabilityService] Searching cars: location={}, startDateTime={}, endDateTime={}, " +
                "hasGeo={}, hasFilters={}",
            request.getNormalizedLocation(),
            request.getStartDateTime(),
            request.getEndDateTime(),
            request.hasGeospatialCoordinates(),
            request.hasFilters()
        );

        // Step 1: Find candidate cars (geospatial or location-string based)
        List<Car> candidateCars = fetchCandidateCars(request);
        log.debug("[AvailabilityService] Found {} candidate cars", candidateCars.size());

        // Initialize additional bags to avoid lazy loading outside the transaction
        candidateCars.forEach(car -> {
            Hibernate.initialize(car.getAddOns());
            Hibernate.initialize(car.getImages());
        });

        // CRITICAL: Only keep marketplace-visible cars in discovery results.
        candidateCars = marketplaceComplianceService.filterMarketplaceVisible(candidateCars);

        // Step 2: Batch availability filtering (P2 fix — eliminates N+1)
        LocalDateTime requestedStart = request.getStartDateTime();
        LocalDateTime requestedEnd = request.getEndDateTime();

        List<Car> availableCars;
        if (candidateCars.isEmpty()) {
            availableCars = List.of();
        } else {
            List<Long> candidateIds = candidateCars.stream()
                    .map(Car::getId)
                    .toList();

            // Single query: find car IDs blocked by effective blocked dates
            Set<Long> blockedByDates = Set.copyOf(
                    blockedDateRepository.findCarIdsWithEffectiveOverlappingBlockedDates(
                            candidateIds,
                            requestedStart.toLocalDate(),
                            requestedEnd.toLocalDate()
                    ));

            // Single query: find car IDs with overlapping bookings in blocking status
            Set<Long> blockedByBookings = Set.copyOf(
                    bookingRepository.findCarIdsWithOverlappingBookings(
                            candidateIds,
                            requestedStart,
                            requestedEnd
                    ));

            // Combine unavailable sets and filter
            Set<Long> unavailableCarIds = new java.util.HashSet<>(blockedByDates);
            unavailableCarIds.addAll(blockedByBookings);

            availableCars = candidateCars.stream()
                    .filter(car -> !unavailableCarIds.contains(car.getId()))
                    .toList();

            log.debug("[AvailabilityService] Batch availability: {} blocked by dates, {} by bookings, " +
                            "{} available (from {} candidates)",
                    blockedByDates.size(), blockedByBookings.size(),
                    availableCars.size(), candidateCars.size());
        }

        // Step 3a: ALWAYS filter by rental duration constraints (min/max days)
        // This is critical - owners set these limits and they must be respected
        availableCars = filterByRentalDuration(availableCars, requestedStart, requestedEnd);
        log.debug("[AvailabilityService] After rental duration filtering: {} available cars", availableCars.size());

        // Step 3b: Apply additional filters (price, make, features, etc.) if specified
        if (request.hasFilters()) {
            availableCars = applyFilters(availableCars, request);
            log.debug("[AvailabilityService] After additional filters: {} cars", availableCars.size());
        }

        // Step 4: Apply sorting (P1 FIX: sort was accepted but never applied)
        availableCars = applySorting(availableCars, request.getSort());

        // Step 5: Apply pagination
        Pageable pageable = PageRequest.of(request.getPage(), request.getSize());

        // Calculate pagination indices
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), availableCars.size());

        // Extract page slice
        List<Car> pageContent = (start < availableCars.size())
            ? availableCars.subList(start, end)
            : List.of();

        // Create Page object with total count supplier
        final List<Car> finalAvailableCars = availableCars;
        Page<Car> resultPage = PageableExecutionUtils.getPage(
            pageContent,
            pageable,
            finalAvailableCars::size
        );

        log.info("[AvailabilityService] Returning page {}/{} with {} cars{}",
            resultPage.getNumber() + 1,
            resultPage.getTotalPages(),
            resultPage.getNumberOfElements(),
            request.hasFilters() ? " [FILTERED]" : ""
        );

        return resultPage;
    }

    /**
     * Fetch candidate cars based on search mode:
     * - Geospatial mode: Uses spatial index query (findNearby)
     * - Location-string mode: Uses city-based text query
     *
     * @param request Search request with location and optional coordinates
     * @return List of candidate cars to filter
     */
    private List<Car> fetchCandidateCars(AvailabilitySearchRequestDTO request) {
        if (request.hasGeospatialCoordinates()) {
            // GEOSPATIAL MODE: Use spatial index for proximity search
            log.debug("[AvailabilityService] Using geospatial search: lat={}, lng={}, radius={}km",
                request.getLatitude(), request.getLongitude(), request.getRadiusKm());

            List<Car> nearbyCars = carRepository.findNearby(
                request.getLatitude(),
                request.getLongitude(),
                request.getRadiusKm()
            );

            // Eagerly load features for filtering (native query doesn't use EntityGraph)
            nearbyCars.forEach(car -> {
                Hibernate.initialize(car.getOwner());
                Hibernate.initialize(car.getFeatures());
            });

            return nearbyCars;
        } else {
            // LOCATION-STRING MODE: Use city-based text query (fallback)
            String normalizedLocation = request.getNormalizedLocation();
            log.debug("[AvailabilityService] Using location-string search: '{}'", normalizedLocation);

            return carRepository.findAvailableWithDetailsByLocation(normalizedLocation);
        }
    }

    /**
     * Filter cars by rental duration constraints (min/max days).
     *
     * This is always applied regardless of other filters because:
     * - Owners set these limits for business/insurance reasons
     * - A 12-day search should never show a car with maxRentalDays=10
     *
     * @param cars List of candidate cars
     * @param requestedStart Search start datetime
     * @param requestedEnd Search end datetime
     * @return Filtered list with only cars that allow the requested duration
     */
    private List<Car> filterByRentalDuration(
            List<Car> cars,
            LocalDateTime requestedStart,
            LocalDateTime requestedEnd
    ) {
        // Calculate requested rental days from search date range
        long rentalDays = BookingDurationPolicy.calculate(requestedStart, requestedEnd).billablePeriods();

        final long finalRentalDays = rentalDays;

        return cars.stream()
            .filter(car -> {
                // Check minimum rental days constraint
                Integer minRentalDays = car.getMinRentalDays();
                if (minRentalDays != null && finalRentalDays < minRentalDays) {
                    log.trace("[AvailabilityService] Car {} filtered: rental {} days < min {} days",
                        car.getId(), finalRentalDays, minRentalDays);
                    return false;
                }

                // Check maximum rental days constraint
                Integer maxRentalDays = car.getMaxRentalDays();
                if (maxRentalDays != null && finalRentalDays > maxRentalDays) {
                    log.trace("[AvailabilityService] Car {} filtered: rental {} days > max {} days",
                        car.getId(), finalRentalDays, maxRentalDays);
                    return false;
                }

                return true;
            })
            .toList();
    }

    /**
     * Apply in-memory filters to candidate cars.
     *
     * This is efficient for typical availability search result sizes (< 100 cars).
     * Filters are applied after geospatial/location and time-based filtering
     * have already reduced the candidate set.
     *
     * @param cars List of candidate cars
     * @param request Request containing filter criteria
     * @return Filtered list of cars
     */
    private List<Car> applyFilters(List<Car> cars, AvailabilitySearchRequestDTO request) {
        return cars.stream()
            .filter(car -> matchesFilters(car, request))
            .toList();
    }

    /**
     * Apply in-memory sorting to filtered car results.
     *
     * P1 FIX: Sort was accepted in AvailabilitySearchRequestDTO but never applied.
     * Supported sort fields: pricePerDay, year, brand, model, seats, id, rating
     * Format: "field,direction" (e.g., "pricePerDay,asc")
     * Default: id DESC (newest first) when no sort specified.
     *
     * Rating sort performs a single batch query to ReviewRepository
     * to avoid N+1 per-car lookups.
     *
     * @param cars List of cars to sort
     * @param sort Sort string (e.g., "pricePerDay,asc")
     * @return Sorted list of cars (new list, original unchanged)
     */
    private List<Car> applySorting(List<Car> cars, String sort) {
        if (cars == null || cars.isEmpty()) {
            return cars;
        }

        Comparator<Car> comparator = null;

        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            if (parts.length == 2) {
                String field = parts[0].trim();
                boolean ascending = parts[1].trim().equalsIgnoreCase("asc");

                comparator = switch (field) {
                    case "pricePerDay" -> Comparator.comparing(
                            Car::getPricePerDay, Comparator.nullsLast(Comparator.naturalOrder()));
                    case "year" -> Comparator.comparing(
                            Car::getYear, Comparator.nullsLast(Comparator.naturalOrder()));
                    case "brand" -> Comparator.comparing(
                            (Car c) -> c.getBrand() != null ? c.getBrand().toLowerCase() : "",
                            Comparator.naturalOrder());
                    case "model" -> Comparator.comparing(
                            (Car c) -> c.getModel() != null ? c.getModel().toLowerCase() : "",
                            Comparator.naturalOrder());
                    case "seats" -> Comparator.comparing(
                            Car::getSeats, Comparator.nullsLast(Comparator.naturalOrder()));
                    case "id" -> Comparator.comparing(
                            Car::getId, Comparator.nullsLast(Comparator.naturalOrder()));
                    case "rating" -> {
                        // Batch-fetch owner ratings in a single query
                        Map<Long, Double> ratingMap = buildOwnerRatingMap(cars);
                        yield Comparator.comparingDouble(
                                (Car c) -> c.getOwner() != null
                                        ? ratingMap.getOrDefault(c.getOwner().getId(), 0.0)
                                        : 0.0);
                    }
                    default -> null;
                };

                if (comparator != null && !ascending) {
                    comparator = comparator.reversed();
                }
            }
        }

        // Default sort: newest first (id DESC)
        if (comparator == null) {
            comparator = Comparator.comparing(
                    Car::getId, Comparator.nullsLast(Comparator.reverseOrder()));
        }

        return cars.stream().sorted(comparator).toList();
    }

    /**
     * Build a map of ownerId → average rating using a single batch query.
     * P0-2 FIX: Uses visibility-filtered query to enforce double-blind.
     */
    private Map<Long, Double> buildOwnerRatingMap(List<Car> cars) {
        Set<Long> ownerIds = cars.stream()
                .map(Car::getOwner)
                .filter(Objects::nonNull)
                .map(org.example.rentoza.user.User::getId)
                .collect(Collectors.toSet());

        Map<Long, Double> ratingMap = new HashMap<>();
        if (!ownerIds.isEmpty()) {
            java.time.Instant visibilityTimeout = java.time.Instant.now().minus(14, ChronoUnit.DAYS);
            reviewRepository.findVisibleAverageRatingsForReviewees(ownerIds, ReviewDirection.FROM_USER, visibilityTimeout)
                    .forEach(row -> ratingMap.put((Long) row[0], (Double) row[1]));
        }
        return ratingMap;
    }

    /**
     * Check if a car matches all active filter criteria.
     * Delegates to {@link CarFilterEngine#matchesCar} for consistent filter semantics.
     *
     * @param car Car to check
     * @param request Request containing filter criteria
     * @return true if car matches all filters (or no filters are active)
     */
    private boolean matchesFilters(Car car, AvailabilitySearchRequestDTO request) {
        // NOTE: Rental duration constraints (min/max days) are checked by
        // filterByRentalDuration() which runs BEFORE this method.
        // Do NOT duplicate that logic here.
        return CarFilterEngine.matchesCar(car, request);
    }

    /**
     * Find available cars for a specific owner within a time range.
     *
     * @param ownerId Owner's user ID
     * @param start Requested start DateTime
     * @param end Requested end DateTime
     * @return List of available cars
     */
    @Transactional(readOnly = true)
    public List<Car> getAvailableCarsForOwner(Long ownerId, LocalDateTime start, LocalDateTime end) {
        log.debug("[AvailabilityService] Fetching available cars for ownerId={} between {} and {}", ownerId, start, end);

        // 1. Fetch all active cars for the owner
        List<Car> ownerCars = carRepository.findByOwnerIdAndAvailableTrue(ownerId);

        // 2. Filter by availability (bookings + blocked dates)
        return ownerCars.stream()
                .filter(car -> isCarAvailableInTimeRange(car, start, end))
                .toList();
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
        // 1. Check for Blocked Dates (Owner blocked time)
        // Uses status-aware query: booking-linked rows only count if booking is still occupying.
        // This prevents no-show / cancellation stale rows from suppressing availability.
        boolean hasBlockedDates = blockedDateRepository.existsEffectiveOverlappingBlockedDates(
                car.getId(),
                requestedStart.toLocalDate(),
                requestedEnd.toLocalDate()
        );

        if (hasBlockedDates) {
            log.debug("[AvailabilityService] Car {} has effective blocked dates overlapping request {} - {}",
                    car.getId(), requestedStart, requestedEnd);
            return false;
        }

        // 2. Fetch all confirmed bookings for this car
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

    /**
     * Get all unavailable time ranges for a specific car, including:
     * - Active/pending bookings
     * - Owner-blocked dates
     * - Unusable gaps (periods too short for minimum rental duration)
     *
     * Gap Detection Logic:
     * If the gap between two consecutive unavailable ranges is less than
     * `car.minRentalDays * 24 hours`, the gap is marked as unavailable by
     * extending the earlier range to cover it. This prevents "hugging" issues
     * where unusable voids are created in the schedule.
     *
     * @param carId Car ID to check
     * @param queryStart Start of query window (inclusive)
     * @param queryEnd End of query window (inclusive)
     * @return List of unavailable ranges, sorted by start time
     */
    @Transactional(readOnly = true)
    public List<UnavailableRangeDTO> getUnavailableRanges(
            Long carId,
            LocalDateTime queryStart,
            LocalDateTime queryEnd
    ) {
        log.debug("[AvailabilityService] Fetching unavailable ranges for carId={} between {} and {}",
                carId, queryStart, queryEnd);

        // Validate car exists, is approved, and get minRentalDays
        Car car = carRepository.findById(carId)
                .orElseThrow(() -> new ResourceNotFoundException("Car not found with ID: " + carId));

        // Block availability probing for unapproved listings
        if (car.getListingStatus() != ListingStatus.APPROVED) {
            throw new ResourceNotFoundException("Car not found with ID: " + carId);
        }

        int minRentalDays = car.getMinRentalDays() != null ? car.getMinRentalDays() : 1;
        long minHours = minRentalDays * 24L;

        // Step 1: Fetch active bookings (blocking statuses)
        List<Booking> activeBookings = bookingRepository.findByCarIdAndTimeRangeBlocking(
                carId,
                queryStart.minusDays(365), // Fetch wider range to catch gaps
                queryEnd.plusDays(365)
        );

        // Step 2: Fetch effective blocked dates (status-aware — excludes stale no-show / cancelled rows)
        List<BlockedDate> blockedDates = blockedDateRepository.findEffectiveByCarIdOrderByStartDateAsc(carId);

        // Step 3: Convert to UnavailableRangeDTO list
        List<UnavailableRangeDTO> allRanges = new ArrayList<>();

        // Convert bookings
        for (Booking booking : activeBookings) {
            allRanges.add(new UnavailableRangeDTO(
                    booking.getStartTime(),
                    booking.getEndTime(),
                    UnavailableRangeDTO.UnavailabilityReason.BOOKING
            ));
        }

        // Convert blocked dates (LocalDate to LocalDateTime)
        for (BlockedDate blocked : blockedDates) {
            LocalDateTime blockStart = blocked.getStartDate().atStartOfDay();
            LocalDateTime blockEnd = blocked.getEndDate().atTime(LocalTime.MAX);

            // Only include if overlaps with query window
            if (blockStart.isBefore(queryEnd) && blockEnd.isAfter(queryStart)) {
                allRanges.add(new UnavailableRangeDTO(
                        blockStart,
                        blockEnd,
                        UnavailableRangeDTO.UnavailabilityReason.BLOCKED_DATE
                ));
            }
        }

        // Step 4: Sort by start time
        allRanges.sort(Comparator.comparing(UnavailableRangeDTO::start));

        // Step 5: Detect and merge small gaps
        List<UnavailableRangeDTO> mergedRanges = new ArrayList<>();
        for (int i = 0; i < allRanges.size(); i++) {
            UnavailableRangeDTO current = allRanges.get(i);

            // Try to merge with next ranges if gap is too small
            LocalDateTime currentEnd = current.end();
            int j = i + 1;

            while (j < allRanges.size()) {
                UnavailableRangeDTO next = allRanges.get(j);
                long gapHours = ChronoUnit.HOURS.between(currentEnd, next.start());

                if (gapHours > 0 && gapHours < minHours) {
                    // Gap is too small - extend current range to cover it
                    currentEnd = next.end();
                    j++;
                } else {
                    break;
                }
            }

            // Add merged range (or original if no merge)
            if (!currentEnd.equals(current.end())) {
                mergedRanges.add(new UnavailableRangeDTO(
                        current.start(),
                        currentEnd,
                        current.reason() // Keep original reason
                ));
            } else {
                mergedRanges.add(current);
            }

            i = j - 1; // Skip merged ranges
        }

        // Step 6: Filter to query window
        return mergedRanges.stream()
                .filter(range -> range.start().isBefore(queryEnd) && range.end().isAfter(queryStart))
                .collect(Collectors.toList());
    }
}

package org.example.rentoza.car;

import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.dto.CarRequestDTO;
import org.example.rentoza.car.dto.CarResponseDTO;
import org.example.rentoza.car.dto.CarSearchCriteria;
import org.example.rentoza.car.storage.CarImageStorageService;
import org.example.rentoza.common.GeoPoint;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.review.Review;
import org.example.rentoza.review.ReviewDirection;
import org.example.rentoza.review.ReviewRepository;
import org.example.rentoza.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CarService {

    private static final Logger log = LoggerFactory.getLogger(CarService.class);

    private final CarRepository repo;
    private final BookingRepository bookingRepo;
    private final ReviewRepository reviewRepo;
    private final org.example.rentoza.security.CurrentUser currentUser;
    private final CarImageStorageService carImageStorageService;

    public CarService(
            CarRepository repo,
            BookingRepository bookingRepo,
            ReviewRepository reviewRepo,
            org.example.rentoza.security.CurrentUser currentUser,
            CarImageStorageService carImageStorageService
    ) {
        this.repo = repo;
        this.bookingRepo = bookingRepo;
        this.reviewRepo = reviewRepo;
        this.currentUser = currentUser;
        this.carImageStorageService = carImageStorageService;
    }

    @Transactional
    public Car addCarWithLocalImages(CarRequestDTO dto, User owner, List<MultipartFile> images) {
        // This path is used by multipart/form-data uploads.
        // We intentionally DO NOT persist any base64 data into the database.
        dto.setImageUrl(null);
        dto.setImageUrls(null);

        Car savedCar = addCar(dto, owner);

        List<String> imageUrls = carImageStorageService.storeCarImages(savedCar.getId(), images);
        if (!imageUrls.isEmpty()) {
            savedCar.setImageUrl(imageUrls.get(0));
            savedCar.setImageUrls(new ArrayList<>(imageUrls));
            savedCar = repo.save(savedCar);
        }

        return savedCar;
    }

    public Car addCar(CarRequestDTO dto, User owner) {
        // ========================================================================
        // TURO-STANDARD VALIDATION (Feature 3 Hardening)
        // ========================================================================

        // 1. Required fields
        if (dto.getBrand() == null || dto.getBrand().isBlank()) {
            throw new RuntimeException("Brand is required");
        }
        if (dto.getModel() == null || dto.getModel().isBlank()) {
            throw new RuntimeException("Model is required");
        }

        // 2. Price range: min 10, max 50,000 RSD
        if (dto.getPricePerDay() == null || dto.getPricePerDay().compareTo(BigDecimal.TEN) < 0) {
            throw new RuntimeException("Price per day must be at least 10 RSD");
        }
        if (dto.getPricePerDay().compareTo(new BigDecimal("50000")) > 0) {
            throw new RuntimeException("Price per day must be at most 50,000 RSD");
        }

        // 3. Car age max 15 years (Turo standard)
        if (dto.getYear() != null) {
            int currentYear = java.time.Year.now().getValue();
            if (currentYear - dto.getYear() > 15) {
                throw new RuntimeException("Car too old. Maximum vehicle age is 15 years.");
            }
            if (dto.getYear() > currentYear + 1) {
                throw new RuntimeException("Invalid year. Cannot be more than 1 year in the future.");
            }
        }

        // 4. Current mileage max 300,000 km (Turo standard)
        if (dto.getCurrentMileageKm() != null && dto.getCurrentMileageKm() > 300_000) {
            throw new RuntimeException("Mileage too high. Maximum 300,000 km.");
        }
        if (dto.getCurrentMileageKm() != null && dto.getCurrentMileageKm() < 0) {
            throw new RuntimeException("Mileage cannot be negative.");
        }

        // 5. Daily mileage limit validation
        if (dto.getDailyMileageLimitKm() != null) {
            if (dto.getDailyMileageLimitKm() < 50) {
                throw new RuntimeException("Daily mileage limit must be at least 50 km.");
            }
            if (dto.getDailyMileageLimitKm() > 1000) {
                throw new RuntimeException("Daily mileage limit must be at most 1,000 km.");
            }
        }

        // 6. Geospatial coordinates are REQUIRED (Phase 2.4)
        if (dto.getLocationLatitude() == null || dto.getLocationLongitude() == null) {
            throw new RuntimeException("Location coordinates (latitude/longitude) are required");
        }

        // 7. Photo count validation on JSON path (5–10 photos, Turo standard)
        if (dto.getImageUrls() != null) {
            if (dto.getImageUrls().size() < 5) {
                throw new RuntimeException("Minimum 5 photos required.");
            }
            if (dto.getImageUrls().size() > 10) {
                throw new RuntimeException("Maximum 10 photos allowed.");
            }
        }

        // 8. Duplicate license plate check
        if (dto.getLicensePlate() != null && !dto.getLicensePlate().isBlank()) {
            String plate = dto.getLicensePlate().trim().toUpperCase();
            boolean duplicatePlate = repo.existsByLicensePlateIgnoreCase(plate);
            if (duplicatePlate) {
                throw new RuntimeException("A car with license plate " + plate + " is already registered.");
            }
        }

        // 9. Sanitize description (strip HTML to prevent stored XSS)
        if (dto.getDescription() != null) {
            dto.setDescription(sanitizeText(dto.getDescription()));
        }

        // Create new car entity with default values
        Car car = new Car();

        // Map basic required fields
        car.setBrand(dto.getBrand().trim());
        car.setModel(dto.getModel().trim());
        car.setYear(dto.getYear());
        car.setPricePerDay(dto.getPricePerDay());
        car.setLocation(dto.getLocation().trim().toLowerCase());
        car.setOwner(owner);
        car.setApprovalStatus(ApprovalStatus.PENDING);
        car.setAvailable(false);

        // Set geospatial location (Phase 2.4 - REQUIRED)
        GeoPoint geoPoint = new GeoPoint();
        geoPoint.setLatitude(dto.getLocationLatitude());
        geoPoint.setLongitude(dto.getLocationLongitude());
        geoPoint.setAddress(dto.getLocationAddress() != null ? dto.getLocationAddress() : dto.getLocation());
        geoPoint.setCity(dto.getLocationCity());
        geoPoint.setZipCode(dto.getLocationZipCode());
        car.setLocationGeoPoint(geoPoint);

        // Map optional license plate
        if (dto.getLicensePlate() != null && !dto.getLicensePlate().isBlank()) {
            car.setLicensePlate(dto.getLicensePlate().trim());
        }

        // Map optional description
        if (dto.getDescription() != null && !dto.getDescription().isBlank()) {
            car.setDescription(dto.getDescription().trim());
        }

        // Map specifications (override defaults if provided)
        if (dto.getSeats() != null) {
            car.setSeats(dto.getSeats());
        }
        if (dto.getFuelType() != null) {
            car.setFuelType(dto.getFuelType());
        }
        if (dto.getFuelConsumption() != null) {
            car.setFuelConsumption(dto.getFuelConsumption());
        }
        if (dto.getTransmissionType() != null) {
            car.setTransmissionType(dto.getTransmissionType());
        }

        // Map features (initialize empty set, then add if provided)
        if (dto.getFeatures() != null && !dto.getFeatures().isEmpty()) {
            car.setFeatures(new HashSet<>(dto.getFeatures()));
        }

        // Map add-ons (initialize empty set, then add if provided)
        if (dto.getAddOns() != null && !dto.getAddOns().isEmpty()) {
            car.setAddOns(new HashSet<>(dto.getAddOns()));
        }

        // Map rental policies (override defaults if provided)
        if (dto.getCancellationPolicy() != null) {
            car.setCancellationPolicy(dto.getCancellationPolicy());
        }
        if (dto.getMinRentalDays() != null) {
            car.setMinRentalDays(dto.getMinRentalDays());
        }
        if (dto.getMaxRentalDays() != null) {
            car.setMaxRentalDays(dto.getMaxRentalDays());
        }

        // Map images
        if (dto.getImageUrl() != null && !dto.getImageUrl().isBlank()) {
            car.setImageUrl(dto.getImageUrl());
        }
        if (dto.getImageUrls() != null && !dto.getImageUrls().isEmpty()) {
            car.setImageUrls(new ArrayList<>(dto.getImageUrls()));
        }

        // Map Turo-standard fields: mileage limit, current mileage, instant-book
        if (dto.getDailyMileageLimitKm() != null) {
            car.setDailyMileageLimitKm(dto.getDailyMileageLimitKm());
        }
        if (dto.getCurrentMileageKm() != null) {
            car.setCurrentMileageKm(dto.getCurrentMileageKm());
        }
        // Map instant-book setting into CarBookingSettings
        if (dto.getInstantBookEnabled() != null) {
            CarBookingSettings settings = car.getEffectiveBookingSettings();
            settings.setInstantBookEnabled(dto.getInstantBookEnabled());
            car.setBookingSettings(settings);
        }

        Car savedCar = repo.save(car);

        // Log successful persistence for debugging
        log.debug("Car saved successfully: id={}, brand={}, model={}, year={}, seats={}, fuelType={}, " +
                  "transmissionType={}, features={}, addOns={}, cancellationPolicy={}, description={}",
                savedCar.getId(), savedCar.getBrand(), savedCar.getModel(), savedCar.getYear(),
                savedCar.getSeats(), savedCar.getFuelType(), savedCar.getTransmissionType(),
                savedCar.getFeatures().size(), savedCar.getAddOns().size(),
                savedCar.getCancellationPolicy(),
                savedCar.getDescription() != null ? savedCar.getDescription().substring(0, Math.min(50, savedCar.getDescription().length())) + "..." : "null");

        return savedCar;
    }

    @Transactional(readOnly = true)
    public List<CarResponseDTO> getAllCars() {
        // Public listing - only show available cars to users
        // Privacy: Use fuzzy locations for non-owners
        Long currentUserId = currentUser.idOrNull();
        return repo.findByAvailableTrueAndApprovalStatus(ApprovalStatus.APPROVED)
                .stream()
                .map(car -> mapToResponseWithPrivacy(car, currentUserId))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<CarResponseDTO> getCarsByLocation(String location) {
        // Public listing - only show available cars to users
        // Privacy: Use fuzzy locations for non-owners
        Long currentUserId = currentUser.idOrNull();
        return repo.findByLocationIgnoreCaseAndAvailableTrueAndApprovalStatus(location, ApprovalStatus.APPROVED)
                .stream()
                .map(car -> mapToResponseWithPrivacy(car, currentUserId))
                .collect(Collectors.toList());
    }

    /**
     * Get cars by owner email with ownership verification.
     * RLS-ENFORCED: Returns cars only if requester is the owner or admin.
     * Prevents Owner A from viewing Owner B's private inventory.
     *
     * @param email Owner's email
     * @return List of cars owned by the user
     * @throws org.springframework.security.access.AccessDeniedException if requester is not the owner or admin
     */
    @Transactional(readOnly = true)
    public List<CarResponseDTO> getCarsByOwner(String email) {
        // RLS ENFORCEMENT: Verify requester is the owner or admin
        String requesterEmail = currentUser.email();
        if (!requesterEmail.equalsIgnoreCase(email) && !currentUser.isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Unauthorized to access cars for owner: " + email
            );
        }

        Long currentUserId = currentUser.idOrNull();
        return repo.findByOwnerEmailIgnoreCase(email)
                .stream()
                .map(car -> mapToResponseWithPrivacy(car, currentUserId)) // Owners will see exact location
                .collect(Collectors.toList());
    }

    /**
     * Get car by ID with full details (features, addOns, images).
     * Uses @EntityGraph to load all collections in a single query.
     *
     * PERFORMANCE:
     * - Single query with LEFT JOIN FETCH for all collections
     * - No N+1 queries when accessing features/addOns
     * - Optimized for detail page views
     *
     * @throws ResourceNotFoundException if car not found
     */
    @Transactional(readOnly = true)
    public CarResponseDTO getCarById(Long id) {
        log.debug("[CarService] Fetching car with ID: {}", id);

        // Use the detail-loading method to prevent LazyInitializationException
        Car car = repo.findWithDetailsById(id)
                .orElseThrow(() -> {
                    log.warn("[CarService] Car not found with ID: {}", id);
                    return new ResourceNotFoundException("Car not found with ID: " + id);
                });

        log.debug("[CarService] Found car: {} {} (ID={})", car.getBrand(), car.getModel(), car.getId());

        // P0 FIX: Unapproved listings should not be publicly accessible.
        // Only the car owner and admins can view unapproved listings.
        Long currentUserId = currentUser.idOrNull();
        boolean isOwner = currentUserId != null && car.getOwner() != null
                && car.getOwner().getId().equals(currentUserId);
        boolean isAdmin = currentUser.isAdmin();

        if (car.getApprovalStatus() != ApprovalStatus.APPROVED && !isOwner && !isAdmin) {
            log.warn("[CarService] Blocked access to unapproved car ID={} (status={})", id, car.getApprovalStatus());
            throw new ResourceNotFoundException("Car not found with ID: " + id);
        }

        // Privacy check: Is current user the owner or has an active booking?
        boolean hasActiveBooking = false;

        if (currentUserId != null) {
            // Check for approved/active booking (user should see exact location if booking is approved)
            // Includes: APPROVED, ACTIVE, CHECK_IN_OPEN, CHECK_IN_HOST_COMPLETE,
            //           CHECK_IN_COMPLETE, IN_TRIP, CHECKOUT_OPEN, CHECKOUT_GUEST_COMPLETE,
            //           CHECKOUT_HOST_COMPLETE
            hasActiveBooking = bookingRepo.findByCarIdAndRenterEmailIgnoreCaseAndStatusIn(
                car.getId(),
                currentUser.email(),
                List.of(
                    BookingStatus.APPROVED,
                    BookingStatus.ACTIVE,
                    BookingStatus.CHECK_IN_OPEN,
                    BookingStatus.CHECK_IN_HOST_COMPLETE,
                    BookingStatus.CHECK_IN_COMPLETE,
                    BookingStatus.IN_TRIP,
                    BookingStatus.CHECKOUT_OPEN,
                    BookingStatus.CHECKOUT_GUEST_COMPLETE,
                    BookingStatus.CHECKOUT_HOST_COMPLETE
                )
            ).stream().findAny().isPresent();
        }

        CarResponseDTO dto = new CarResponseDTO(car, hasActiveBooking, currentUserId);

        // Populate owner stats for detailed view
        if (car.getOwner() != null) {
            Long ownerId = car.getOwner().getId();

            // Fetch average rating
            Double rating = reviewRepo.findAverageRatingForRevieweeAndDirection(
                    ownerId, ReviewDirection.FROM_USER
            );
            dto.setOwnerRating(rating != null ? rating : 0.0);

            // Fetch trip count
            long trips = bookingRepo.countByOwnerIdAndStatus(ownerId, BookingStatus.COMPLETED);
            dto.setOwnerTripCount((int) trips);
        }

        return dto;
    }

    @Transactional
    public CarResponseDTO updateCar(Long carId, CarRequestDTO dto, User requester) {
        Car car = repo.findById(carId)
                .orElseThrow(() -> new RuntimeException("Car not found with ID: " + carId));

        // Verify ownership
        if (!car.getOwner().getId().equals(requester.getId())) {
            throw new RuntimeException("You do not have permission to edit this car");
        }

        // ====== Turo-standard validation for updates ======

        // Price upper bound
        if (dto.getPricePerDay() != null && dto.getPricePerDay().compareTo(new BigDecimal("50000")) > 0) {
            throw new RuntimeException("Price per day must be at most 50,000 RSD");
        }

        // Car age max 15 years + future-year guard
        if (dto.getYear() != null) {
            int currentYear = java.time.Year.now().getValue();
            if (currentYear - dto.getYear() > 15) {
                throw new RuntimeException("Car too old. Maximum vehicle age is 15 years.");
            }
            if (dto.getYear() > currentYear + 1) {
                throw new RuntimeException("Invalid year. Cannot be more than 1 year in the future.");
            }
        }

        // Current mileage max 300,000 km
        if (dto.getCurrentMileageKm() != null && dto.getCurrentMileageKm() > 300_000) {
            throw new RuntimeException("Mileage too high. Maximum 300,000 km.");
        }

        // Daily mileage limit
        if (dto.getDailyMileageLimitKm() != null) {
            if (dto.getDailyMileageLimitKm() < 50 || dto.getDailyMileageLimitKm() > 1000) {
                throw new RuntimeException("Daily mileage limit must be between 50 and 1,000 km.");
            }
        }

        // Photo count limit on JSON path (5–10 photos, Turo standard)
        if (dto.getImageUrls() != null) {
            if (dto.getImageUrls().size() < 5) {
                throw new RuntimeException("Minimum 5 photos required.");
            }
            if (dto.getImageUrls().size() > 10) {
                throw new RuntimeException("Maximum 10 photos allowed.");
            }
        }

        // Duplicate license plate check (only if changing plate)
        if (dto.getLicensePlate() != null && !dto.getLicensePlate().isBlank()) {
            String newPlate = dto.getLicensePlate().trim().toUpperCase();
            String existingPlate = car.getLicensePlate();
            if (!newPlate.equalsIgnoreCase(existingPlate)) {
                boolean duplicatePlate = repo.existsByLicensePlateIgnoreCase(newPlate);
                if (duplicatePlate) {
                    throw new RuntimeException("A car with license plate " + newPlate + " is already registered.");
                }
            }
        }

        // Sanitize description
        if (dto.getDescription() != null) {
            dto.setDescription(sanitizeText(dto.getDescription()));
        }

        // Update fields
        if (dto.getBrand() != null && !dto.getBrand().isBlank()) {
            car.setBrand(dto.getBrand().trim());
        }
        if (dto.getModel() != null && !dto.getModel().isBlank()) {
            car.setModel(dto.getModel().trim());
        }
        if (dto.getYear() != null) {
            car.setYear(dto.getYear());
        }
        // BigDecimal price validation (minimum 10 RSD, maximum 50,000 RSD)
        if (dto.getPricePerDay() != null && dto.getPricePerDay().compareTo(BigDecimal.TEN) >= 0) {
            car.setPricePerDay(dto.getPricePerDay());
        }
        if (dto.getLocation() != null && !dto.getLocation().isBlank()) {
            car.setLocation(dto.getLocation().trim().toLowerCase());
        }
        if (dto.getLicensePlate() != null && !dto.getLicensePlate().isBlank()) {
            car.setLicensePlate(dto.getLicensePlate().trim().toUpperCase());
        }
        if (dto.getDescription() != null) {
            car.setDescription(dto.getDescription().trim());
        }
        if (dto.getSeats() != null) {
            car.setSeats(dto.getSeats());
        }
        if (dto.getFuelType() != null) {
            car.setFuelType(dto.getFuelType());
        }
        if (dto.getFuelConsumption() != null) {
            car.setFuelConsumption(dto.getFuelConsumption());
        }
        if (dto.getTransmissionType() != null) {
            car.setTransmissionType(dto.getTransmissionType());
        }
        if (dto.getFeatures() != null) {
            car.getFeatures().clear();
            car.getFeatures().addAll(dto.getFeatures());
        }
        if (dto.getAddOns() != null) {
            car.getAddOns().clear();
            car.getAddOns().addAll(dto.getAddOns());
        }
        if (dto.getCancellationPolicy() != null) {
            car.setCancellationPolicy(dto.getCancellationPolicy());
        }
        if (dto.getMinRentalDays() != null) {
            car.setMinRentalDays(dto.getMinRentalDays());
        }
        if (dto.getMaxRentalDays() != null) {
            car.setMaxRentalDays(dto.getMaxRentalDays());
        }
        if (dto.getImageUrl() != null) {
            car.setImageUrl(dto.getImageUrl());
        }
        if (dto.getImageUrls() != null) {
            // Use setImageUrls to properly manage CarImage entities
            car.setImageUrls(new ArrayList<>(dto.getImageUrls()));
        }

        // Map Turo-standard fields on update
        if (dto.getDailyMileageLimitKm() != null) {
            car.setDailyMileageLimitKm(dto.getDailyMileageLimitKm());
        }
        if (dto.getCurrentMileageKm() != null) {
            car.setCurrentMileageKm(dto.getCurrentMileageKm());
        }
        if (dto.getInstantBookEnabled() != null) {
            CarBookingSettings settings = car.getEffectiveBookingSettings();
            settings.setInstantBookEnabled(dto.getInstantBookEnabled());
            car.setBookingSettings(settings);
        }

        Car savedCar = repo.save(car);
        // Return DTO with exact location for owner
        return new CarResponseDTO(savedCar, true, requester.getId());
    }

    @Transactional
    public CarResponseDTO toggleAvailability(Long carId, boolean available, User requester) {
        Car car = repo.findById(carId)
                .orElseThrow(() -> new RuntimeException("Car not found with ID: " + carId));

        // Verify ownership
        if (!car.getOwner().getId().equals(requester.getId())) {
            throw new RuntimeException("You do not have permission to modify this car");
        }

        // CRITICAL: Prevent activation if car is not approved
        if (available && car.getApprovalStatus() != ApprovalStatus.APPROVED) {
            throw new RuntimeException("Cannot activate car that is not approved by admin. Current status: " + car.getApprovalStatus());
        }

        car.setAvailable(available);
        Car savedCar = repo.save(car);
        // Return DTO with exact location for owner
        return new CarResponseDTO(savedCar, true, requester.getId());
    }

    /**
     * Delete car method - DEPRECATED
     * @deprecated Car deletion is disabled. Use toggleAvailability() instead to deactivate cars.
     * This method will be removed in a future release.
     * @throws RuntimeException always, as deletion is no longer supported
     */
    @Deprecated
    @Transactional
    public void deleteCar(Long carId, User requester) {
        // Safety check: prevent deletion
        throw new RuntimeException("Car deletion is disabled. Use toggleAvailability() to deactivate cars instead.");
    }

    /**
     * Search cars with dynamic filtering, sorting, and pagination.
     *
     * P0 FIX: Now applies ALL criteria server-side using JPA Specification
     * instead of calling findApprovedAvailableCars() which ignored filters.
     *
     * P2 FIX: Supports 'rating' sort via post-query sort using review data,
     * since average rating is a computed field not stored on the Car entity.
     *
     * @param criteria Search criteria with optional filters
     * @return Page of car response DTOs
     */
    @Transactional(readOnly = true)
    public Page<CarResponseDTO> searchCars(CarSearchCriteria criteria) {
        // Normalize and validate criteria
        criteria.normalize();

        // Check if rating sort is requested (needs special handling)
        boolean isRatingSort = criteria.getSort() != null
                && criteria.getSort().startsWith("rating");

        // Build dynamic specification from criteria
        Specification<Car> spec = buildSearchSpecification(criteria);

        Pageable pageable;
        if (isRatingSort) {
            // Rating sort: push ORDER BY into the database via a correlated subquery
            // so pagination is fully accurate — no in-memory cap needed.
            boolean ascending = criteria.getSort().contains("asc");
            spec = spec.and(ratingOrderSpec(ascending));
            int page = criteria.getPage() != null ? criteria.getPage() : 0;
            int size = criteria.getSize() != null ? criteria.getSize() : 20;
            pageable = PageRequest.of(page, size); // unsorted — ORDER BY is set by spec
        } else {
            pageable = buildPageable(criteria);
        }

        // Execute search with all filters applied server-side
        Page<Car> carPage = repo.findAll(spec, pageable);

        // Map to DTOs with privacy-aware location
        Long currentUserId = currentUser.idOrNull();

        if (isRatingSort) {
            // Populate ownerRating on the page-sized DTO list (single batch query)
            List<Car> pageCars = carPage.getContent();
            java.util.Set<Long> ownerIds = pageCars.stream()
                    .map(Car::getOwner)
                    .filter(java.util.Objects::nonNull)
                    .map(User::getId)
                    .collect(Collectors.toSet());

            java.util.Map<Long, Double> ratingMap = new java.util.HashMap<>();
            if (!ownerIds.isEmpty()) {
                reviewRepo.findAverageRatingsForReviewees(ownerIds, ReviewDirection.FROM_USER)
                        .forEach(row -> ratingMap.put((Long) row[0], (Double) row[1]));
            }

            List<CarResponseDTO> dtos = pageCars.stream()
                    .map(car -> {
                        CarResponseDTO dto = mapToResponseWithPrivacy(car, currentUserId);
                        double rating = (car.getOwner() != null)
                                ? ratingMap.getOrDefault(car.getOwner().getId(), 0.0)
                                : 0.0;
                        dto.setOwnerRating(rating);
                        return dto;
                    })
                    .collect(Collectors.toList());

            return new org.springframework.data.domain.PageImpl<>(
                    dtos, carPage.getPageable(), carPage.getTotalElements());
        }

        return carPage.map(car -> mapToResponseWithPrivacy(car, currentUserId));
    }

    /**
     * Specification that adds a database-level ORDER BY on owner average rating
     * via a correlated subquery.  This lets the DB handle sorting + LIMIT/OFFSET
     * so pagination totals are always accurate and there is no row-count cap.
     *
     * Only adds ORDER BY to the data query (skips count queries).
     */
    private Specification<Car> ratingOrderSpec(boolean ascending) {
        return (root, query, cb) -> {
            // Skip ORDER BY for count queries (result type Long)
            if (Long.class.equals(query.getResultType()) || long.class.equals(query.getResultType())) {
                return cb.conjunction();
            }

            // Correlated subquery: AVG(r.rating) for the car's owner
            jakarta.persistence.criteria.Subquery<Double> ratingSubquery = query.subquery(Double.class);
            jakarta.persistence.criteria.Root<Review> reviewRoot = ratingSubquery.from(Review.class);
            ratingSubquery.select(cb.coalesce(cb.avg(reviewRoot.get("rating")), cb.literal(0.0)))
                    .where(
                            cb.equal(reviewRoot.get("reviewee"), root.get("owner")),
                            cb.equal(reviewRoot.get("direction"), ReviewDirection.FROM_USER)
                    );

            query.orderBy(
                    ascending ? cb.asc(ratingSubquery) : cb.desc(ratingSubquery),
                    cb.desc(root.get("id"))  // deterministic tie-breaker for stable pagination
            );
            return cb.conjunction(); // no additional WHERE — only ORDER BY contribution
        };
    }

    /**
     * Build JPA Specification from search criteria for server-side filtering.
     *
     * Base conditions (always applied):
     * - available = true
     * - approvalStatus = APPROVED
     *
     * Optional filters applied when present:
     * - minPrice / maxPrice
     * - make (brand, case-insensitive contains)
     * - model (case-insensitive contains)
     * - minYear / maxYear
     * - location (case-insensitive contains)
     * - minSeats
     * - transmission
     * - features (car must have ALL requested features)
     * - vehicleType (comma-separated list matched against brand+model, e.g. "SUV")
     */
    private Specification<Car> buildSearchSpecification(CarSearchCriteria criteria) {
        Specification<Car> spec = Specification.where(null);

        // CRITICAL: Only show APPROVED and available cars in public search
        spec = spec.and((root, query, cb) ->
                cb.equal(root.get("available"), true));
        spec = spec.and((root, query, cb) ->
                cb.equal(root.get("approvalStatus"), ApprovalStatus.APPROVED));

        // Price range
        if (criteria.getMinPrice() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.greaterThanOrEqualTo(root.get("pricePerDay"), java.math.BigDecimal.valueOf(criteria.getMinPrice())));
        }
        if (criteria.getMaxPrice() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.lessThanOrEqualTo(root.get("pricePerDay"), java.math.BigDecimal.valueOf(criteria.getMaxPrice())));
        }

        // Brand (make) filter - case-insensitive contains
        if (criteria.getMake() != null && !criteria.getMake().isBlank()) {
            spec = spec.and((root, query, cb) ->
                    cb.like(cb.lower(root.get("brand")), "%" + criteria.getMake().toLowerCase().trim() + "%"));
        }

        // Model filter - case-insensitive contains
        if (criteria.getModel() != null && !criteria.getModel().isBlank()) {
            spec = spec.and((root, query, cb) ->
                    cb.like(cb.lower(root.get("model")), "%" + criteria.getModel().toLowerCase().trim() + "%"));
        }

        // Year range
        if (criteria.getMinYear() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.greaterThanOrEqualTo(root.get("year"), criteria.getMinYear()));
        }
        if (criteria.getMaxYear() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.lessThanOrEqualTo(root.get("year"), criteria.getMaxYear()));
        }

        // Location filter - case-insensitive contains on city or legacy location field
        if (criteria.getLocation() != null && !criteria.getLocation().isBlank()) {
            String loc = criteria.getLocation().toLowerCase().trim();
            spec = spec.and((root, query, cb) ->
                    cb.or(
                            cb.like(cb.lower(root.get("location")), "%" + loc + "%"),
                            cb.like(cb.lower(root.get("locationGeoPoint").get("city")), "%" + loc + "%")
                    ));
        }

        // Minimum seats
        if (criteria.getMinSeats() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.greaterThanOrEqualTo(root.get("seats"), criteria.getMinSeats()));
        }

        // Transmission type
        if (criteria.getTransmission() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("transmissionType"), criteria.getTransmission()));
        }

        // Features filter: car must have ALL requested features
        if (criteria.getFeatures() != null && !criteria.getFeatures().isEmpty()) {
            for (Feature feature : criteria.getFeatures()) {
                spec = spec.and((root, query, cb) ->
                        cb.isMember(feature, root.get("features")));
            }
        }

        // Vehicle type filter: match against brand+model+description
        // Supports comma-separated multi-select from frontend (e.g. "SUV,Sedan")
        if (criteria.getVehicleType() != null && !criteria.getVehicleType().isBlank()) {
            String[] tokens = criteria.getVehicleType().split(",");
            // Build OR predicate: car matches ANY of the supplied vehicle types
            spec = spec.and((root, query, cb) -> {
                jakarta.persistence.criteria.Predicate[] alternatives = java.util.Arrays.stream(tokens)
                        .map(String::trim)
                        .filter(t -> !t.isEmpty())
                        .map(String::toLowerCase)
                        .map(vt -> cb.or(
                                cb.like(cb.lower(root.get("brand")), "%" + vt + "%"),
                                cb.like(cb.lower(root.get("model")), "%" + vt + "%"),
                                cb.like(cb.lower(root.get("description")), "%" + vt + "%")
                        ))
                        .toArray(jakarta.persistence.criteria.Predicate[]::new);
                return cb.or(alternatives);
            });
        }

        // Fuel type filter: exact match on Car.fuelType enum
        if (criteria.getFuelType() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("fuelType"), criteria.getFuelType()));
        }

        return spec;
    }

    /**
     * Build Pageable object with sorting from criteria
     */
    private Pageable buildPageable(CarSearchCriteria criteria) {
        int page = criteria.getPage() != null ? criteria.getPage() : 0;
        int size = criteria.getSize() != null ? criteria.getSize() : 20;

        // Parse sort parameter (e.g., "price,asc" or "year,desc")
        Sort sort = Sort.unsorted();
        if (criteria.getSort() != null && !criteria.getSort().isBlank()) {
            String[] sortParts = criteria.getSort().split(",");
            if (sortParts.length == 2) {
                String field = sortParts[0].trim();
                String direction = sortParts[1].trim();

                // Whitelist allowed sort fields to prevent arbitrary sorting
                if (isValidSortField(field)) {
                    Sort.Direction sortDirection = direction.equalsIgnoreCase("desc")
                            ? Sort.Direction.DESC
                            : Sort.Direction.ASC;
                    sort = Sort.by(sortDirection, field);
                }
            }
        } else {
            // Default sorting: newest first (by id DESC as proxy for creation order)
            sort = Sort.by(Sort.Direction.DESC, "id");
        }

        return PageRequest.of(page, size, sort);
    }

    /**
     * Validate sort field to prevent arbitrary field sorting.
     * P2 FIX: Added 'rating' to whitelist for sort-by-rating feature.
     * Note: 'rating' sort is handled as post-query sort since it's a computed field.
     */
    private boolean isValidSortField(String field) {
        return field.equals("pricePerDay") ||
                field.equals("year") ||
                field.equals("brand") ||
                field.equals("model") ||
                field.equals("seats") ||
                field.equals("rating") ||
                field.equals("id");
    }

    /**
     * Map Car to CarResponseDTO with privacy-aware location handling.
     *
     * Privacy Logic:
     * - Owner sees exact location (isOwner check in DTO constructor)
     * - Active booker sees exact location (passed via hasActiveBooking parameter)
     * - Others see fuzzy coordinates (±500m) and city-only address
     *
     * @param car The car entity
     * @param currentUserId The current user's ID (null if anonymous)
     * @return CarResponseDTO with appropriate location privacy
     */
    private CarResponseDTO mapToResponseWithPrivacy(Car car, Long currentUserId) {
        // For list views, we don't check active bookings (performance)
        // Active booking check is done in getCarById for detail views
        return new CarResponseDTO(car, false, currentUserId);
    }

    private CarResponseDTO mapToResponse(Car car) {
        return new CarResponseDTO(car);
    }

    /**
     * Get all distinct car makes from the database.
     * Used for filter dropdowns.
     *
     * P0-5 FIX: Uses optimized DISTINCT query instead of loading all cars.
     * PERFORMANCE: Results cached for 24h (carMakes cache in RedisCacheConfig).
     */
    @Cacheable(value = "carMakes", key = "'all-makes'", unless = "#result.isEmpty()")
    public List<String> getAllMakes() {
        return repo.findDistinctBrands();
    }

    // ========== SECURITY HELPERS ==========

    /**
     * Sanitize text input to prevent stored XSS.
     * Strips all HTML tags and retains only plain text content.
     * Defense-in-depth measure — even though frontend also sanitizes.
     *
     * @param input Raw text input
     * @return Sanitized plain text
     */
    private String sanitizeText(String input) {
        if (input == null) return null;
        // Iteratively strip HTML tags and decode entities until stable.
        // This prevents encoded payloads like "&lt;script&gt;" from surviving
        // as real tags after a single decode pass.
        String result = input;
        String previous;
        int maxPasses = 5; // safety limit to prevent infinite loops
        int pass = 0;
        do {
            previous = result;
            result = result
                    .replaceAll("<[^>]*>", "")           // Remove HTML tags
                    .replaceAll("&lt;", "<")              // Decode entities
                    .replaceAll("&gt;", ">")
                    .replaceAll("&amp;", "&")
                    .replaceAll("&quot;", "\"")
                    .replaceAll("&#39;", "'");
            pass++;
        } while (!result.equals(previous) && pass < maxPasses);
        // Final tag strip after all entities are decoded
        result = result
                .replaceAll("<[^>]*>", "")
                .replaceAll("(?i)javascript:", "")  // Remove javascript: protocol
                .replaceAll("(?i)on\\w+\\s*=", "")   // Remove event handlers like onerror=
                .trim();
        return result;
    }
}

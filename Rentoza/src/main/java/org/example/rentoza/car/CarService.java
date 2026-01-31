package org.example.rentoza.car;

import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.dto.CarRequestDTO;
import org.example.rentoza.car.dto.CarResponseDTO;
import org.example.rentoza.car.dto.CarSearchCriteria;
import org.example.rentoza.car.storage.CarImageStorageService;
import org.example.rentoza.common.GeoPoint;
import org.example.rentoza.exception.ResourceNotFoundException;
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
        // Validate required fields
        if (dto.getBrand() == null || dto.getBrand().isBlank()) {
            throw new RuntimeException("Brand is required");
        }
        if (dto.getModel() == null || dto.getModel().isBlank()) {
            throw new RuntimeException("Model is required");
        }
        // BigDecimal price validation (minimum 10 RSD)
        if (dto.getPricePerDay() == null || dto.getPricePerDay().compareTo(BigDecimal.TEN) < 0) {
            throw new RuntimeException("Price per day must be at least 10 RSD");
        }
        // Geospatial coordinates are REQUIRED (Phase 2.4)
        if (dto.getLocationLatitude() == null || dto.getLocationLongitude() == null) {
            throw new RuntimeException("Location coordinates (latitude/longitude) are required");
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
        return repo.findByAvailableTrue()
                .stream()
                .map(car -> mapToResponseWithPrivacy(car, currentUserId))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<CarResponseDTO> getCarsByLocation(String location) {
        // Public listing - only show available cars to users
        // Privacy: Use fuzzy locations for non-owners
        Long currentUserId = currentUser.idOrNull();
        return repo.findByLocationIgnoreCaseAndAvailableTrue(location)
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

        // Privacy check: Is current user the owner or has an active booking?
        Long currentUserId = currentUser.idOrNull();
        boolean hasActiveBooking = false;
        
        if (currentUserId != null) {
            // Check for active booking (APPROVED, IN_TRIP, etc.)
            hasActiveBooking = bookingRepo.findByCarIdAndRenterEmailIgnoreCaseAndStatusIn(
                car.getId(),
                currentUser.email(),
                List.of(BookingStatus.ACTIVE, BookingStatus.IN_TRIP)
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
        // BigDecimal price validation (minimum 10 RSD)
        if (dto.getPricePerDay() != null && dto.getPricePerDay().compareTo(BigDecimal.TEN) >= 0) {
            car.setPricePerDay(dto.getPricePerDay());
        }
        if (dto.getLocation() != null && !dto.getLocation().isBlank()) {
            car.setLocation(dto.getLocation().trim().toLowerCase());
        }
        if (dto.getLicensePlate() != null && !dto.getLicensePlate().isBlank()) {
            car.setLicensePlate(dto.getLicensePlate().trim());
        }
        if (dto.getDescription() != null) {
            car.setDescription(dto.getDescription());
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
     * Search cars with dynamic filtering, sorting, and pagination
     * @param criteria Search criteria with optional filters
     * @return Page of car response DTOs
     */
    @Transactional(readOnly = true)
    public Page<CarResponseDTO> searchCars(CarSearchCriteria criteria) {
        // Normalize and validate criteria
        criteria.normalize();

        // Build pageable with sorting
        Pageable pageable = buildPageable(criteria);
        
        // CRITICAL: Only show APPROVED cars in public search
        // Use native query to work around PostgreSQL ENUM operator issue
        // Native SQL: approval_status::text = 'APPROVED' handles the ENUM properly
        Page<Car> carPage = repo.findApprovedAvailableCars(pageable);

        // Map to DTOs with privacy-aware location
        Long currentUserId = currentUser.idOrNull();
        return carPage.map(car -> mapToResponseWithPrivacy(car, currentUserId));
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
     * Validate sort field to prevent arbitrary field sorting
     */
    private boolean isValidSortField(String field) {
        return field.equals("pricePerDay") ||
                field.equals("year") ||
                field.equals("brand") ||
                field.equals("model") ||
                field.equals("seats") ||
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
     */
    public List<String> getAllMakes() {
        return repo.findDistinctBrands();
    }
}

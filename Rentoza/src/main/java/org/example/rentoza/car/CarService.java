package org.example.rentoza.car;

import org.example.rentoza.car.dto.CarRequestDTO;
import org.example.rentoza.car.dto.CarResponseDTO;
import org.example.rentoza.car.dto.CarSearchCriteria;
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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class CarService {

    private static final Logger log = LoggerFactory.getLogger(CarService.class);

    private final CarRepository repo;

    public CarService(CarRepository repo) {
        this.repo = repo;
    }

    public Car addCar(CarRequestDTO dto, User owner) {
        // Validate required fields
        if (dto.getBrand() == null || dto.getBrand().isBlank()) {
            throw new RuntimeException("Brand is required");
        }
        if (dto.getModel() == null || dto.getModel().isBlank()) {
            throw new RuntimeException("Model is required");
        }
        if (dto.getPricePerDay() == null || dto.getPricePerDay() <= 0) {
            throw new RuntimeException("Invalid price per day");
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
        car.setAvailable(true);

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

        // Map features (initialize empty list, then add if provided)
        if (dto.getFeatures() != null && !dto.getFeatures().isEmpty()) {
            car.setFeatures(new ArrayList<>(dto.getFeatures()));
        }

        // Map add-ons (initialize empty list, then add if provided)
        if (dto.getAddOns() != null && !dto.getAddOns().isEmpty()) {
            car.setAddOns(new ArrayList<>(dto.getAddOns()));
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
        return repo.findByAvailableTrue()
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<CarResponseDTO> getCarsByLocation(String location) {
        // Public listing - only show available cars to users
        return repo.findByLocationIgnoreCaseAndAvailableTrue(location)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<CarResponseDTO> getCarsByOwner(String email) {
        return repo.findByOwnerEmailIgnoreCase(email)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public CarResponseDTO getCarById(Long id) {
        Car car = repo.findById(id)
                .orElseThrow(() -> new RuntimeException("Car not found with ID: " + id));

        return mapToResponse(car);
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
        if (dto.getPricePerDay() != null && dto.getPricePerDay() > 0) {
            car.setPricePerDay(dto.getPricePerDay());
        }
        if (dto.getLocation() != null && !dto.getLocation().isBlank()) {
            car.setLocation(dto.getLocation().trim().toLowerCase());
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
            car.getImageUrls().clear();
            car.getImageUrls().addAll(dto.getImageUrls());
        }

        Car savedCar = repo.save(car);
        // Return DTO to avoid lazy initialization issues
        return mapToResponse(savedCar);
    }

    @Transactional
    public CarResponseDTO toggleAvailability(Long carId, boolean available, User requester) {
        Car car = repo.findById(carId)
                .orElseThrow(() -> new RuntimeException("Car not found with ID: " + carId));

        // Verify ownership
        if (!car.getOwner().getId().equals(requester.getId())) {
            throw new RuntimeException("You do not have permission to modify this car");
        }

        car.setAvailable(available);
        Car savedCar = repo.save(car);
        // Return DTO to avoid lazy initialization issues
        return mapToResponse(savedCar);
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

        // Build specification from criteria
        Specification<Car> spec = CarSpecification.fromCriteriaWithAnyFeature(criteria);

        // Build pageable with sorting
        Pageable pageable = buildPageable(criteria);

        // Execute query
        Page<Car> carPage = repo.findAll(spec, pageable);

        // Map to DTOs
        return carPage.map(this::mapToResponse);
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

    private CarResponseDTO mapToResponse(Car car) {
        return new CarResponseDTO(car);
    }

    /**
     * Get all distinct car makes from the database
     * Used for filter dropdowns
     */
    public List<String> getAllMakes() {
        return repo.findAll().stream()
                .map(Car::getBrand)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }
}

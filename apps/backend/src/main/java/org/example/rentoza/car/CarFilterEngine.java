package org.example.rentoza.car;

import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Centralized car filter engine providing both JPA Specification (DB-level)
 * and in-memory Predicate evaluation from a single set of filter rules.
 *
 * This consolidates the previously duplicated filter logic between
 * {@link CarService#buildSearchSpecification} and
 * {@link AvailabilityService#matchesFilters}, ensuring both paths
 * derive from one source of truth.
 *
 * @see CarFilterCriteria
 */
public final class CarFilterEngine {

    private CarFilterEngine() {
    }

    // ========== IN-MEMORY PREDICATE (used by AvailabilityService) ==========

    /**
     * Test whether a car matches all active filter criteria in memory.
     *
     * @param car      Car entity to test
     * @param criteria Filter criteria (any implementation)
     * @return true if the car matches every active filter
     */
    public static boolean matchesCar(Car car, CarFilterCriteria criteria) {
        // Price filters
        if (criteria.getMinPrice() != null
                && car.getPricePerDay().compareTo(BigDecimal.valueOf(criteria.getMinPrice())) < 0) {
            return false;
        }
        if (criteria.getMaxPrice() != null
                && car.getPricePerDay().compareTo(BigDecimal.valueOf(criteria.getMaxPrice())) > 0) {
            return false;
        }

        // Make/Brand filter (case-insensitive contains)
        if (criteria.getMake() != null && !criteria.getMake().isBlank()) {
            String carBrand = car.getBrand() != null ? car.getBrand().toLowerCase() : "";
            if (!carBrand.contains(criteria.getMake().toLowerCase().trim())) {
                return false;
            }
        }

        // Model filter (case-insensitive contains)
        if (criteria.getModel() != null && !criteria.getModel().isBlank()) {
            String carModel = car.getModel() != null ? car.getModel().toLowerCase() : "";
            if (!carModel.contains(criteria.getModel().toLowerCase().trim())) {
                return false;
            }
        }

        // Year filters
        if (criteria.getMinYear() != null && car.getYear() < criteria.getMinYear()) {
            return false;
        }
        if (criteria.getMaxYear() != null && car.getYear() > criteria.getMaxYear()) {
            return false;
        }

        // Seats filter
        if (criteria.getMinSeats() != null) {
            Integer carSeats = car.getSeats();
            if (carSeats == null || carSeats < criteria.getMinSeats()) {
                return false;
            }
        }

        // Transmission filter
        if (criteria.getTransmission() != null) {
            if (car.getTransmissionType() != criteria.getTransmission()) {
                return false;
            }
        }

        // Features filter (car must have ALL requested features)
        if (criteria.getFeatures() != null && !criteria.getFeatures().isEmpty()) {
            Set<Feature> carFeatures = car.getFeatures();
            if (carFeatures == null || carFeatures.isEmpty()) {
                return false;
            }
            for (Feature required : criteria.getFeatures()) {
                if (!carFeatures.contains(required)) {
                    return false;
                }
            }
        }

        // Vehicle type filter (comma-separated, OR across brand/model/description)
        if (criteria.getVehicleType() != null && !criteria.getVehicleType().isBlank()) {
            String carBrand = car.getBrand() != null ? car.getBrand().toLowerCase() : "";
            String carModel = car.getModel() != null ? car.getModel().toLowerCase() : "";
            String carDesc = car.getDescription() != null ? car.getDescription().toLowerCase() : "";
            String[] tokens = criteria.getVehicleType().split(",");
            boolean anyMatch = false;
            for (String token : tokens) {
                String vt = token.trim().toLowerCase();
                if (!vt.isEmpty() && (carBrand.contains(vt) || carModel.contains(vt) || carDesc.contains(vt))) {
                    anyMatch = true;
                    break;
                }
            }
            if (!anyMatch) {
                return false;
            }
        }

        // Fuel type filter
        FuelType requestedFuelType = criteria.getResolvedFuelType();
        if (requestedFuelType != null && car.getFuelType() != requestedFuelType) {
            return false;
        }

        // Free-text query (q): OR across brand, model, location, description
        if (criteria.getQ() != null && !criteria.getQ().isBlank()) {
            String q = criteria.getQ().toLowerCase().trim();
            String carBrand = car.getBrand() != null ? car.getBrand().toLowerCase() : "";
            String carModel = car.getModel() != null ? car.getModel().toLowerCase() : "";
            String carLocation = car.getLocation() != null ? car.getLocation().toLowerCase() : "";
            String carDesc = car.getDescription() != null ? car.getDescription().toLowerCase() : "";
            if (!carBrand.contains(q) && !carModel.contains(q)
                    && !carLocation.contains(q) && !carDesc.contains(q)) {
                return false;
            }
        }

        return true;
    }

    // ========== JPA SPECIFICATION (used by CarService) ==========

    /**
     * Build a JPA Specification for the optional filter criteria.
     * Does NOT include base conditions (available=true, approvalStatus=APPROVED);
     * those are added by the caller.
     *
     * @param criteria Filter criteria (any implementation)
     * @return Specification with all active filter predicates
     */
    public static Specification<Car> buildSpecification(CarFilterCriteria criteria) {
        Specification<Car> spec = Specification.where(null);

        // Price range
        if (criteria.getMinPrice() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.greaterThanOrEqualTo(root.get("pricePerDay"),
                            BigDecimal.valueOf(criteria.getMinPrice())));
        }
        if (criteria.getMaxPrice() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.lessThanOrEqualTo(root.get("pricePerDay"),
                            BigDecimal.valueOf(criteria.getMaxPrice())));
        }

        // Brand (make) filter — case-insensitive contains
        if (criteria.getMake() != null && !criteria.getMake().isBlank()) {
            spec = spec.and((root, query, cb) ->
                    cb.like(cb.lower(root.get("brand")),
                            "%" + criteria.getMake().toLowerCase().trim() + "%"));
        }

        // Model filter — case-insensitive contains
        if (criteria.getModel() != null && !criteria.getModel().isBlank()) {
            spec = spec.and((root, query, cb) ->
                    cb.like(cb.lower(root.get("model")),
                            "%" + criteria.getModel().toLowerCase().trim() + "%"));
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
        if (criteria.getVehicleType() != null && !criteria.getVehicleType().isBlank()) {
            String[] tokens = criteria.getVehicleType().split(",");
            spec = spec.and((root, query, cb) -> {
                jakarta.persistence.criteria.Predicate[] alternatives = Arrays.stream(tokens)
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

        // Fuel type filter
        FuelType resolvedFuel = criteria.getResolvedFuelType();
        if (resolvedFuel != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("fuelType"), resolvedFuel));
        }

        // Free-text query (q): OR across brand, model, location, description
        if (criteria.getQ() != null && !criteria.getQ().isBlank()) {
            String qLower = criteria.getQ().toLowerCase().trim();
            spec = spec.and((root, query, cb) ->
                    cb.or(
                            cb.like(cb.lower(root.get("brand")), "%" + qLower + "%"),
                            cb.like(cb.lower(root.get("model")), "%" + qLower + "%"),
                            cb.like(cb.lower(root.get("location")), "%" + qLower + "%"),
                            cb.like(cb.lower(root.get("description")), "%" + qLower + "%")
                    ));
        }

        return spec;
    }
}

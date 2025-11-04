package org.example.rentoza.car;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.example.rentoza.car.dto.CarSearchCriteria;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * JPA Specifications for dynamic car filtering
 * Builds predicates based on non-null criteria fields
 */
public class CarSpecification {

    /**
     * Build a specification from search criteria
     * All filters are combined with AND logic
     */
    public static Specification<Car> fromCriteria(CarSearchCriteria criteria) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Always exclude unavailable cars
            predicates.add(criteriaBuilder.isTrue(root.get("available")));

            // Price filtering
            if (criteria.getMinPrice() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(
                        root.get("pricePerDay"), criteria.getMinPrice()));
            }
            if (criteria.getMaxPrice() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(
                        root.get("pricePerDay"), criteria.getMaxPrice()));
            }

            // Brand filtering (case-insensitive)
            if (criteria.getMake() != null && !criteria.getMake().isBlank()) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("brand")),
                        "%" + criteria.getMake().toLowerCase() + "%"));
            }

            // Model filtering (case-insensitive)
            if (criteria.getModel() != null && !criteria.getModel().isBlank()) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("model")),
                        "%" + criteria.getModel().toLowerCase() + "%"));
            }

            // Year filtering
            if (criteria.getMinYear() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(
                        root.get("year"), criteria.getMinYear()));
            }
            if (criteria.getMaxYear() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(
                        root.get("year"), criteria.getMaxYear()));
            }

            // Location filtering (case-insensitive)
            if (criteria.getLocation() != null && !criteria.getLocation().isBlank()) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("location")),
                        "%" + criteria.getLocation().toLowerCase() + "%"));
            }

            // Seats filtering
            if (criteria.getMinSeats() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(
                        root.get("seats"), criteria.getMinSeats()));
            }

            // Transmission filtering
            if (criteria.getTransmission() != null) {
                predicates.add(criteriaBuilder.equal(
                        root.get("transmissionType"), criteria.getTransmission()));
            }

            // Features filtering - car must have ALL specified features
            if (criteria.getFeatures() != null && !criteria.getFeatures().isEmpty()) {
                Join<Car, Feature> featuresJoin = root.join("features", JoinType.LEFT);
                predicates.add(featuresJoin.in(criteria.getFeatures()));

                // Group by car id and ensure count matches number of requested features
                // This ensures the car has ALL features, not just ANY
                query.groupBy(root.get("id"));
                query.having(criteriaBuilder.greaterThanOrEqualTo(
                        criteriaBuilder.countDistinct(featuresJoin),
                        (long) criteria.getFeatures().size()
                ));
            }

            // Combine all predicates with AND
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Alternative specification for OR-based feature filtering
     * Car must have AT LEAST ONE of the specified features
     */
    public static Specification<Car> fromCriteriaWithAnyFeature(CarSearchCriteria criteria) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Always exclude unavailable cars
            predicates.add(criteriaBuilder.isTrue(root.get("available")));

            // Apply all filters same as above...
            if (criteria.getMinPrice() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(
                        root.get("pricePerDay"), criteria.getMinPrice()));
            }
            if (criteria.getMaxPrice() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(
                        root.get("pricePerDay"), criteria.getMaxPrice()));
            }
            if (criteria.getMake() != null && !criteria.getMake().isBlank()) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("brand")),
                        "%" + criteria.getMake().toLowerCase() + "%"));
            }
            if (criteria.getModel() != null && !criteria.getModel().isBlank()) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("model")),
                        "%" + criteria.getModel().toLowerCase() + "%"));
            }
            if (criteria.getMinYear() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(
                        root.get("year"), criteria.getMinYear()));
            }
            if (criteria.getMaxYear() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(
                        root.get("year"), criteria.getMaxYear()));
            }
            if (criteria.getLocation() != null && !criteria.getLocation().isBlank()) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("location")),
                        "%" + criteria.getLocation().toLowerCase() + "%"));
            }
            if (criteria.getMinSeats() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(
                        root.get("seats"), criteria.getMinSeats()));
            }
            if (criteria.getTransmission() != null) {
                predicates.add(criteriaBuilder.equal(
                        root.get("transmissionType"), criteria.getTransmission()));
            }

            // Features filtering - car must have AT LEAST ONE feature
            if (criteria.getFeatures() != null && !criteria.getFeatures().isEmpty()) {
                Join<Car, Feature> featuresJoin = root.join("features", JoinType.LEFT);
                predicates.add(featuresJoin.in(criteria.getFeatures()));

                // Add DISTINCT to avoid duplicates
                query.distinct(true);
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}

package org.example.rentoza.car;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.example.rentoza.car.dto.CarSearchCriteria;
import org.example.rentoza.common.StringNormalizationUtil;
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

            // Brand filtering (case-insensitive with accent normalization)
            if (criteria.getMake() != null && !criteria.getMake().isBlank()) {
                Expression<String> normalizedBrand = createNormalizedExpression(
                        criteriaBuilder, root.get("brand"));
                String normalizedSearch = StringNormalizationUtil.normalizeSearchString(criteria.getMake());
                predicates.add(criteriaBuilder.like(normalizedBrand, "%" + normalizedSearch + "%"));
            }

            // Model filtering (case-insensitive with accent normalization)
            if (criteria.getModel() != null && !criteria.getModel().isBlank()) {
                Expression<String> normalizedModel = createNormalizedExpression(
                        criteriaBuilder, root.get("model"));
                String normalizedSearch = StringNormalizationUtil.normalizeSearchString(criteria.getModel());
                predicates.add(criteriaBuilder.like(normalizedModel, "%" + normalizedSearch + "%"));
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

            // Location filtering (case-insensitive with accent normalization)
            if (criteria.getLocation() != null && !criteria.getLocation().isBlank()) {
                Expression<String> normalizedLocation = createNormalizedExpression(
                        criteriaBuilder, root.get("location"));
                String normalizedSearch = StringNormalizationUtil.normalizeSearchString(criteria.getLocation());
                predicates.add(criteriaBuilder.like(normalizedLocation, "%" + normalizedSearch + "%"));
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
                Expression<String> normalizedBrand = createNormalizedExpression(
                        criteriaBuilder, root.get("brand"));
                String normalizedSearch = StringNormalizationUtil.normalizeSearchString(criteria.getMake());
                predicates.add(criteriaBuilder.like(normalizedBrand, "%" + normalizedSearch + "%"));
            }
            if (criteria.getModel() != null && !criteria.getModel().isBlank()) {
                Expression<String> normalizedModel = createNormalizedExpression(
                        criteriaBuilder, root.get("model"));
                String normalizedSearch = StringNormalizationUtil.normalizeSearchString(criteria.getModel());
                predicates.add(criteriaBuilder.like(normalizedModel, "%" + normalizedSearch + "%"));
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
                Expression<String> normalizedLocation = createNormalizedExpression(
                        criteriaBuilder, root.get("location"));
                String normalizedSearch = StringNormalizationUtil.normalizeSearchString(criteria.getLocation());
                predicates.add(criteriaBuilder.like(normalizedLocation, "%" + normalizedSearch + "%"));
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

    /**
     * Creates a normalized string expression for accent-insensitive search
     * Applies Serbian character normalization: š→s, ć→c, č→c, ž→z, đ→dj
     */
    private static Expression<String> createNormalizedExpression(
            jakarta.persistence.criteria.CriteriaBuilder cb,
            jakarta.persistence.criteria.Expression<String> field) {
        
        Expression<String> normalized = cb.lower(field);
        
        // Replace Serbian Latin characters with their base forms
        normalized = cb.function("REPLACE", String.class, normalized, cb.literal("š"), cb.literal("s"));
        normalized = cb.function("REPLACE", String.class, normalized, cb.literal("ć"), cb.literal("c"));
        normalized = cb.function("REPLACE", String.class, normalized, cb.literal("č"), cb.literal("c"));
        normalized = cb.function("REPLACE", String.class, normalized, cb.literal("ž"), cb.literal("z"));
        normalized = cb.function("REPLACE", String.class, normalized, cb.literal("đ"), cb.literal("dj"));
        
        return normalized;
    }
}

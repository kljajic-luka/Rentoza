package org.example.rentoza.car;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MarketplaceComplianceService {

    private static final Set<DocumentType> REQUIRED_DOCUMENT_TYPES = EnumSet.of(
            DocumentType.REGISTRATION,
            DocumentType.TECHNICAL_INSPECTION,
            DocumentType.LIABILITY_INSURANCE
    );

    private final CarDocumentRepository documentRepository;

    @Transactional(readOnly = true)
    public boolean isMarketplaceVisible(Car car) {
        return assess(car, documentRepository.findByCarId(car.getId())).marketplaceVisible();
    }

    @Transactional(readOnly = true)
    public List<Car> filterMarketplaceVisible(List<Car> cars) {
        if (cars == null || cars.isEmpty()) {
            return List.of();
        }

        Map<Long, List<CarDocument>> documentsByCarId = documentRepository.findByCarIdIn(
                        cars.stream().map(Car::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(doc -> doc.getCar().getId()));

        return cars.stream()
                .filter(car -> assess(car, documentsByCarId.getOrDefault(car.getId(), List.of())).marketplaceVisible())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> buildComplianceIssues(Car car) {
        return assess(car, documentRepository.findByCarId(car.getId())).issues();
    }

    @Transactional(readOnly = true)
    public boolean isApprovalCompliant(Car car) {
        return buildComplianceIssues(car).isEmpty();
    }

    @Transactional(readOnly = true)
    public boolean isEligibleForActivation(Car car) {
        return car.getListingStatus() == ListingStatus.APPROVED
                && isApprovalCompliant(car);
    }

    private ComplianceAssessment assess(Car car, List<CarDocument> documents) {
        List<String> issues = new ArrayList<>();
        LocalDate today = LocalDate.now();

        if (car.getOwner() == null || !car.getOwner().isVerifiedOwner()) {
            issues.add("Owner identity not verified");
        }

        if (car.getRegistrationExpiryDate() == null) {
            issues.add("Registration expiry date not set");
        } else if (!car.getRegistrationExpiryDate().isAfter(today)) {
            issues.add("Registration expired on " + car.getRegistrationExpiryDate());
        }

        if (car.getInsuranceExpiryDate() == null) {
            issues.add("Insurance expiry date not set");
        } else if (!car.getInsuranceExpiryDate().isAfter(today)) {
            issues.add("Insurance expired on " + car.getInsuranceExpiryDate());
        }

        if (car.getTechnicalInspectionExpiryDate() == null) {
            issues.add("Technical inspection expiry date not set");
        } else if (!car.getTechnicalInspectionExpiryDate().isAfter(today)) {
            issues.add("Technical inspection expired on " + car.getTechnicalInspectionExpiryDate());
        }

        Map<DocumentType, CarDocument> requiredDocuments = latestRequiredDocuments(documents);
        for (DocumentType type : REQUIRED_DOCUMENT_TYPES) {
            CarDocument document = requiredDocuments.get(type);
            if (document == null) {
                issues.add(type.name() + " document missing");
                continue;
            }

            if (document.getStatus() != DocumentVerificationStatus.VERIFIED) {
                issues.add(type.name() + " document not verified (status: " + document.getStatus() + ")");
            }

            if (document.getExpiryDate() != null && !document.getExpiryDate().isAfter(today)) {
                issues.add(type.name() + " document expired on " + document.getExpiryDate());
            }
        }

        if (car.getDocumentsVerifiedAt() == null || car.getDocumentsVerifiedBy() == null) {
            issues.add("Documents not yet verified by admin");
        }

        boolean marketplaceVisible = car.getListingStatus() == ListingStatus.APPROVED
                && car.isAvailable()
                && issues.isEmpty();

        return new ComplianceAssessment(List.copyOf(issues), marketplaceVisible);
    }

    private Map<DocumentType, CarDocument> latestRequiredDocuments(List<CarDocument> documents) {
        Map<DocumentType, CarDocument> result = new HashMap<>();
        for (CarDocument document : documents) {
            if (!REQUIRED_DOCUMENT_TYPES.contains(document.getType())) {
                continue;
            }

            result.merge(document.getType(), document, (left, right) -> {
                Comparator<CarDocument> byUploadDate = Comparator.comparing(
                        CarDocument::getUploadDate,
                        Comparator.nullsFirst(Comparator.naturalOrder()));
                return byUploadDate.compare(left, right) >= 0 ? left : right;
            });
        }
        return result;
    }

    private record ComplianceAssessment(List<String> issues, boolean marketplaceVisible) {}
}
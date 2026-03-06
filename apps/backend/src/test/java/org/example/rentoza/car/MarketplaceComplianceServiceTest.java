package org.example.rentoza.car;

import org.example.rentoza.user.OwnerType;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MarketplaceComplianceService")
class MarketplaceComplianceServiceTest {

    @Mock
    private CarDocumentRepository documentRepository;

    private MarketplaceComplianceService complianceService;
    private Car car;

    @BeforeEach
    void setUp() {
        complianceService = new MarketplaceComplianceService(documentRepository);

        User owner = new User();
        owner.setId(10L);
        owner.setOwnerType(OwnerType.INDIVIDUAL);
        owner.setJmbg("1234567890123");
        owner.setIsIdentityVerified(true);

        User admin = new User();
        admin.setId(20L);

        car = new Car();
        car.setId(1L);
        car.setOwner(owner);
        car.setListingStatus(ListingStatus.APPROVED);
        car.setAvailable(true);
        car.setRegistrationExpiryDate(LocalDate.now().plusMonths(6));
        car.setInsuranceExpiryDate(LocalDate.now().plusMonths(6));
        car.setTechnicalInspectionExpiryDate(LocalDate.now().plusMonths(3));
        car.setDocumentsVerifiedAt(LocalDateTime.now().minusDays(1));
        car.setDocumentsVerifiedBy(admin);
    }

    @Test
    @DisplayName("Marketplace visibility fails when required document rows are missing")
    void marketplaceVisibilityFailsWhenRequiredDocumentRowsMissing() {
        when(documentRepository.findByCarId(1L)).thenReturn(List.of());

        assertThat(complianceService.isMarketplaceVisible(car)).isFalse();
        assertThat(complianceService.buildComplianceIssues(car))
                .contains("REGISTRATION document missing")
                .contains("TECHNICAL_INSPECTION document missing")
                .contains("LIABILITY_INSURANCE document missing");
    }

    @Test
    @DisplayName("Marketplace visibility requires owner verified as an actual owner record")
    void marketplaceVisibilityRequiresVerifiedOwnerSemantics() {
        car.getOwner().setJmbg(null);
        when(documentRepository.findByCarId(1L)).thenReturn(List.of(
                verifiedDocument(DocumentType.REGISTRATION),
                verifiedDocument(DocumentType.TECHNICAL_INSPECTION),
                verifiedDocument(DocumentType.LIABILITY_INSURANCE)
        ));

        assertThat(complianceService.isMarketplaceVisible(car)).isFalse();
        assertThat(complianceService.buildComplianceIssues(car)).contains("Owner identity not verified");
    }

    @Test
    @DisplayName("Marketplace visibility passes only when all required documents are present and verified")
    void marketplaceVisibilityPassesWithFullyCompliantState() {
        when(documentRepository.findByCarId(1L)).thenReturn(List.of(
                verifiedDocument(DocumentType.REGISTRATION),
                verifiedDocument(DocumentType.TECHNICAL_INSPECTION),
                verifiedDocument(DocumentType.LIABILITY_INSURANCE)
        ));

        assertThat(complianceService.buildComplianceIssues(car)).isEmpty();
        assertThat(complianceService.isMarketplaceVisible(car)).isTrue();
    }

    private CarDocument verifiedDocument(DocumentType type) {
        CarDocument document = new CarDocument();
        document.setCar(car);
        document.setType(type);
        document.setStatus(DocumentVerificationStatus.VERIFIED);
        document.setUploadDate(LocalDateTime.now().minusDays(2));
        document.setExpiryDate(LocalDate.now().plusMonths(6));
        return document;
    }
}

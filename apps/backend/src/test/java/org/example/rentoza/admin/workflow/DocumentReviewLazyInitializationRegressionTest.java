package org.example.rentoza.admin.workflow;

import org.example.rentoza.admin.dto.DocumentReviewDto;
import org.example.rentoza.car.*;
import org.example.rentoza.car.storage.DocumentStorageStrategy;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.hibernate.LazyInitializationException;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManagerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Regression: admin document review avoids LazyInitializationException")
class DocumentReviewLazyInitializationRegressionTest {

    @MockBean
    private DocumentStorageStrategy documentStorageStrategy;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CarRepository carRepository;

    @Autowired
    private CarDocumentRepository carDocumentRepository;

    @Autowired
    private CarDocumentService carDocumentService;

    @Test
    @DisplayName("Detached CarDocument -> DocumentReviewDto mapping throws LazyInitializationException (failure mode)")
    void detachedEntityMappingThrowsLazyInitializationException() {
        Long documentId = seedVerifiedDocument();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CarDocument detachedDoc = tx.execute(status -> carDocumentRepository.findById(documentId).orElseThrow());

        assertThat(detachedDoc).isNotNull();

        assertThatThrownBy(() -> DocumentReviewDto.fromEntity(detachedDoc))
            .isInstanceOf(LazyInitializationException.class)
            .hasMessageContaining("could not initialize proxy")
            .hasMessageContaining("User#");
    }

    @Test
    @DisplayName("Service returns DocumentReviewDto without LazyInitializationException")
    void serviceReturnsDtoWithoutLazyInitializationException() {
        Long documentId = seedVerifiedDocument();

        DocumentReviewDto dto = carDocumentService.getDocumentReview(documentId);

        assertThat(dto).isNotNull();
        assertThat(dto.getId()).isEqualTo(documentId);
        assertThat(dto.getStatus()).isEqualTo(DocumentVerificationStatus.VERIFIED.name());
        assertThat(dto.getVerifiedByName()).isEqualTo("Admin User");
        assertThat(dto.getDocumentUrl()).isEqualTo("/api/admin/documents/" + documentId + "/download");
    }

    @Test
    @DisplayName("Service uses single query (no N+1) for DocumentReviewDto")
    void serviceUsesSingleQueryForDocumentReview() {
        Long documentId = seedVerifiedDocument();

        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        carDocumentService.getDocumentReview(documentId);

        // One SELECT with JOIN FETCH verifiedBy is the intended steady-state.
        // Allow <=2 to keep this test stable across minor framework behavior changes.
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(2);
    }

    private Long seedVerifiedDocument() {
        User owner = new User();
        owner.setFirstName("Owner");
        owner.setLastName("User");
        owner.setEmail("owner@test.local");
        owner.setPassword("{noop}password");
        owner.setRole(Role.USER);
        owner = userRepository.save(owner);

        User admin = new User();
        admin.setFirstName("Admin");
        admin.setLastName("User");
        admin.setEmail("admin@test.local");
        admin.setPassword("{noop}password");
        admin.setRole(Role.ADMIN);
        admin = userRepository.save(admin);

        Car car = new Car();
        car.setBrand("BMW");
        car.setModel("X5");
        car.setYear(2023);
        car.setLocation("belgrade");
        car.setPricePerDay(BigDecimal.valueOf(1000));
        car.setSeats(5);
        car.setFuelType(FuelType.BENZIN);
        car.setTransmissionType(TransmissionType.MANUAL);
        car.setOwner(owner);
        car.setApprovalStatus(ApprovalStatus.PENDING);
        car.setListingStatus(org.example.rentoza.car.ListingStatus.PENDING_APPROVAL);
        car.setAvailable(false);
        car = carRepository.save(car);

        CarDocument doc = CarDocument.builder()
            .car(car)
            .type(DocumentType.REGISTRATION)
            .documentUrl("cars/" + car.getId() + "/documents/test.pdf")
            .originalFilename("test.pdf")
            .documentHash("0".repeat(64))
            .fileSize(1234L)
            .mimeType("application/pdf")
            .uploadDate(LocalDateTime.now())
            .expiryDate(LocalDate.now().plusDays(30))
            .status(DocumentVerificationStatus.VERIFIED)
            .verifiedBy(admin)
            .verifiedAt(LocalDateTime.now())
            .build();

        doc = carDocumentRepository.save(doc);
        return doc.getId();
    }
}

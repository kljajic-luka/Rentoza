package org.example.rentoza.admin.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.admin.dto.AdminCarDto;
import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.car.ApprovalStatus;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarDocumentService;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.car.ListingStatus;
import org.example.rentoza.car.MarketplaceComplianceService;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AdminCarService.
 * 
 * Tests the enterprise improvements:
 * - MDC logging context (verified via audit service calls)
 * - Micrometer metrics (counters and timers)
 * - Validation and null checks
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminCarService Tests")
class AdminCarServiceTest {

    @Mock
    private CarRepository carRepo;

    @Mock
    private AdminAuditService auditService;

    @Mock
    private CarDocumentService documentService;

    @Mock
    private MarketplaceComplianceService marketplaceComplianceService;

    private MeterRegistry meterRegistry;

    private AdminCarService adminCarService;

    @Captor
    private ArgumentCaptor<AdminAction> actionCaptor;

    private User testAdmin;
    private Car testCar;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        adminCarService = new AdminCarService(carRepo, documentService, marketplaceComplianceService, auditService, meterRegistry);

        testAdmin = new User();
        testAdmin.setId(1L);
        testAdmin.setEmail("admin@test.com");

        testCar = new Car();
        testCar.setId(100L);
        testCar.setBrand("BMW");
        testCar.setModel("X5");
        testCar.setYear(2023);
        testCar.setApprovalStatus(ApprovalStatus.PENDING);
        testCar.setListingStatus(ListingStatus.PENDING_APPROVAL);
        testCar.setAvailable(false);

        // Owner with verified identity (required by compliance gate)
        User testOwner = new User();
        testOwner.setId(50L);
        testOwner.setIsIdentityVerified(true);
        testCar.setOwner(testOwner);

        // Set up compliant document state (required for approval gate)
        testCar.setRegistrationExpiryDate(LocalDate.now().plusMonths(6));
        testCar.setInsuranceExpiryDate(LocalDate.now().plusMonths(6));
        testCar.setTechnicalInspectionExpiryDate(LocalDate.now().plusMonths(3));
        testCar.setDocumentsVerifiedAt(LocalDateTime.now().minusDays(1));
        testCar.setDocumentsVerifiedBy(testAdmin);

        lenient().when(marketplaceComplianceService.buildComplianceIssues(any(Car.class))).thenAnswer(invocation -> {
            Car car = invocation.getArgument(0);
            java.util.List<String> issues = new java.util.ArrayList<>();

            if (car.getRegistrationExpiryDate() == null) {
                issues.add("Registration expiry date not set");
            } else if (!car.getRegistrationExpiryDate().isAfter(LocalDate.now())) {
                issues.add("Registration expired on " + car.getRegistrationExpiryDate());
            }

            if (car.getInsuranceExpiryDate() == null) {
                issues.add("Insurance expiry date not set");
            } else if (!car.getInsuranceExpiryDate().isAfter(LocalDate.now())) {
                issues.add("Insurance expired on " + car.getInsuranceExpiryDate());
            }

            if (car.getTechnicalInspectionExpiryDate() == null) {
                issues.add("Technical inspection expiry date not set");
            } else if (!car.getTechnicalInspectionExpiryDate().isAfter(LocalDate.now())) {
                issues.add("Technical inspection expired on " + car.getTechnicalInspectionExpiryDate());
            }

            if (car.getDocumentsVerifiedAt() == null || car.getDocumentsVerifiedBy() == null) {
                issues.add("Documents not yet verified by admin");
            }

            return issues;
        });
    }

    @Nested
    @DisplayName("approveCar()")
    class ApproveCarTests {

        @Test
        @DisplayName("Should approve car and update status to APPROVED")
        void shouldApproveCarSuccessfully() {
            when(carRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(testCar));
            when(carRepo.save(any(Car.class))).thenAnswer(inv -> inv.getArgument(0));

            AdminCarDto result = adminCarService.approveCar(100L, testAdmin);

            assertThat(result.getApprovalStatus()).isEqualTo("APPROVED");
            assertThat(testCar.getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED);
            assertThat(testCar.getListingStatus()).isEqualTo(ListingStatus.APPROVED);
            assertThat(testCar.isAvailable()).isTrue();
            assertThat(testCar.getApprovedBy()).isEqualTo(testAdmin);
            assertThat(testCar.getApprovedAt()).isNotNull();
            assertThat(testCar.getRejectionReason()).isNull();
        }

        @Test
        @DisplayName("Should log audit action on approval")
        void shouldLogAuditActionOnApproval() {
            when(carRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(testCar));
            when(carRepo.save(any(Car.class))).thenAnswer(inv -> inv.getArgument(0));

            adminCarService.approveCar(100L, testAdmin);

            verify(auditService).logAction(
                    eq(testAdmin),
                    actionCaptor.capture(),
                    eq(ResourceType.CAR),
                    eq(100L),
                    any(),
                    any(),
                    contains("compliance verified")
            );

            assertThat(actionCaptor.getValue()).isEqualTo(AdminAction.CAR_APPROVED);
        }

        @Test
        @DisplayName("Should increment success counter metric")
        void shouldIncrementSuccessCounterMetric() {
            when(carRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(testCar));
            when(carRepo.save(any(Car.class))).thenAnswer(inv -> inv.getArgument(0));

            adminCarService.approveCar(100L, testAdmin);

            Counter counter = meterRegistry.find("car.approvals")
                    .tag("status", "success")
                    .counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Should record approval duration timer")
        void shouldRecordApprovalDurationTimer() {
            when(carRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(testCar));
            when(carRepo.save(any(Car.class))).thenAnswer(inv -> inv.getArgument(0));

            adminCarService.approveCar(100L, testAdmin);

            Timer timer = meterRegistry.find("car.approval.duration")
                    .tag("action", "approve")
                    .timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should throw IllegalStateException when approving non-PENDING_APPROVAL car")
        void shouldThrowWhenApprovingNonPendingCar() {
            testCar.setApprovalStatus(ApprovalStatus.APPROVED);
            testCar.setListingStatus(ListingStatus.APPROVED);
            when(carRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(testCar));

            assertThatThrownBy(() -> adminCarService.approveCar(100L, testAdmin))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot approve car in state APPROVED");
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when car not found")
        void shouldThrowWhenCarNotFound() {
            when(carRepo.findByIdForUpdate(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> adminCarService.approveCar(999L, testAdmin))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Car not found: 999");
        }

        @Test
        @DisplayName("Should increment failed counter on exception")
        void shouldIncrementFailedCounterOnException() {
            when(carRepo.findByIdForUpdate(100L)).thenReturn(Optional.empty());

            try {
                adminCarService.approveCar(100L, testAdmin);
            } catch (Exception ignored) {
            }

            Counter counter = meterRegistry.find("car.approvals")
                    .tag("status", "failed")
                    .counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Should block approval when registration is expired")
        void shouldBlockApprovalWhenRegistrationExpired() {
            testCar.setRegistrationExpiryDate(LocalDate.now().minusDays(1));
            when(carRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(testCar));

            assertThatThrownBy(() -> adminCarService.approveCar(100L, testAdmin))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("compliance requirements not met")
                    .hasMessageContaining("Registration expired");

            verify(carRepo, never()).save(any());
        }

        @Test
        @DisplayName("Should block approval when insurance is expired")
        void shouldBlockApprovalWhenInsuranceExpired() {
            testCar.setInsuranceExpiryDate(LocalDate.now().minusDays(1));
            when(carRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(testCar));

            assertThatThrownBy(() -> adminCarService.approveCar(100L, testAdmin))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Insurance expired");

            verify(carRepo, never()).save(any());
        }

        @Test
        @DisplayName("Should block approval when technical inspection is expired")
        void shouldBlockApprovalWhenTechInspectionExpired() {
            testCar.setTechnicalInspectionExpiryDate(LocalDate.now().minusDays(1));
            when(carRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(testCar));

            assertThatThrownBy(() -> adminCarService.approveCar(100L, testAdmin))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Technical inspection expired");

            verify(carRepo, never()).save(any());
        }

        @Test
        @DisplayName("Should block approval when documents not verified")
        void shouldBlockApprovalWhenDocumentsNotVerified() {
            testCar.setDocumentsVerifiedAt(null);
            testCar.setDocumentsVerifiedBy(null);
            when(carRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(testCar));

            assertThatThrownBy(() -> adminCarService.approveCar(100L, testAdmin))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Documents not yet verified");

            verify(carRepo, never()).save(any());
        }

        @Test
        @DisplayName("Should block approval when registration date is missing")
        void shouldBlockApprovalWhenRegistrationDateMissing() {
            testCar.setRegistrationExpiryDate(null);
            when(carRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(testCar));

            assertThatThrownBy(() -> adminCarService.approveCar(100L, testAdmin))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Registration expiry date not set");

            verify(carRepo, never()).save(any());
        }

        @Test
        @DisplayName("Should report all compliance issues together")
        void shouldReportAllComplianceIssuesTogether() {
            testCar.setRegistrationExpiryDate(null);
            testCar.setInsuranceExpiryDate(null);
            testCar.setTechnicalInspectionExpiryDate(null);
            testCar.setDocumentsVerifiedAt(null);
            testCar.setDocumentsVerifiedBy(null);
            when(carRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(testCar));

            assertThatThrownBy(() -> adminCarService.approveCar(100L, testAdmin))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Registration expiry date not set")
                    .hasMessageContaining("Insurance expiry date not set")
                    .hasMessageContaining("Technical inspection expiry date not set")
                    .hasMessageContaining("Documents not yet verified");
        }

        @Test
        @DisplayName("Should increment compliance_blocked counter when blocked")
        void shouldIncrementComplianceBlockedCounter() {
            testCar.setRegistrationExpiryDate(null);
            when(carRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(testCar));

            try {
                adminCarService.approveCar(100L, testAdmin);
            } catch (Exception ignored) {
            }

            Counter counter = meterRegistry.find("car.approvals")
                    .tag("status", "compliance_blocked")
                    .counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("rejectCar()")
    class RejectCarTests {

        @Test
        @DisplayName("Should reject car with reason")
        void shouldRejectCarWithReason() {
            when(carRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(testCar));
            when(carRepo.save(any(Car.class))).thenAnswer(inv -> inv.getArgument(0));

            AdminCarDto result = adminCarService.rejectCar(100L, "Vehicle photos are too blurry", testAdmin);

            assertThat(result.getApprovalStatus()).isEqualTo("REJECTED");
            assertThat(testCar.getApprovalStatus()).isEqualTo(ApprovalStatus.REJECTED);
            assertThat(testCar.getListingStatus()).isEqualTo(ListingStatus.REJECTED);
            assertThat(testCar.isAvailable()).isFalse();
            assertThat(testCar.getRejectionReason()).isEqualTo("Vehicle photos are too blurry");
        }

        @Test
        @DisplayName("Should throw IllegalStateException when rejecting non-PENDING_APPROVAL car")
        void shouldThrowWhenRejectingNonPendingCar() {
            testCar.setApprovalStatus(ApprovalStatus.APPROVED);
            testCar.setListingStatus(ListingStatus.APPROVED);
            when(carRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(testCar));

            assertThatThrownBy(() -> adminCarService.rejectCar(100L, "Some reason", testAdmin))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot reject car in state APPROVED");
        }

        @Test
        @DisplayName("Should throw exception when reason is null")
        void shouldThrowWhenReasonIsNull() {
            when(carRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(testCar));

            assertThatThrownBy(() -> adminCarService.rejectCar(100L, null, testAdmin))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Rejection reason is required");
        }

        @Test
        @DisplayName("Should throw exception when reason is blank")
        void shouldThrowWhenReasonIsBlank() {
            when(carRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(testCar));

            assertThatThrownBy(() -> adminCarService.rejectCar(100L, "   ", testAdmin))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Rejection reason is required");
        }

        @Test
        @DisplayName("Should log audit action with rejection reason")
        void shouldLogAuditActionWithReason() {
            when(carRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(testCar));
            when(carRepo.save(any(Car.class))).thenAnswer(inv -> inv.getArgument(0));

            adminCarService.rejectCar(100L, "Incomplete listing", testAdmin);

            verify(auditService).logAction(
                    eq(testAdmin),
                    eq(AdminAction.CAR_REJECTED),
                    eq(ResourceType.CAR),
                    eq(100L),
                    any(),
                    any(),
                    eq("Incomplete listing")
            );
        }
    }

    @Nested
    @DisplayName("suspendCar()")
    class SuspendCarTests {

        @Test
        @DisplayName("Should suspend car with reason")
        void shouldSuspendCarWithReason() {
            testCar.setApprovalStatus(ApprovalStatus.APPROVED);
            testCar.setListingStatus(ListingStatus.APPROVED);
            testCar.setAvailable(true);

            when(carRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(testCar));
            when(carRepo.save(any(Car.class))).thenAnswer(inv -> inv.getArgument(0));

            AdminCarDto result = adminCarService.suspendCar(100L, "Policy violation detected", testAdmin);

            assertThat(result.getApprovalStatus()).isEqualTo("SUSPENDED");
            assertThat(testCar.getApprovalStatus()).isEqualTo(ApprovalStatus.SUSPENDED);
            assertThat(testCar.getListingStatus()).isEqualTo(ListingStatus.SUSPENDED);
            assertThat(testCar.isAvailable()).isFalse();
            assertThat(testCar.getRejectionReason()).isEqualTo("Policy violation detected");
        }

        @Test
        @DisplayName("Should throw exception when suspension reason is null")
        void shouldThrowWhenSuspensionReasonIsNull() {
            testCar.setApprovalStatus(ApprovalStatus.APPROVED);
            testCar.setListingStatus(ListingStatus.APPROVED);
            when(carRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(testCar));

            assertThatThrownBy(() -> adminCarService.suspendCar(100L, null, testAdmin))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Suspension reason is required");
        }
    }

    @Nested
    @DisplayName("reactivateCar()")
    class ReactivateCarTests {

        @Test
        @DisplayName("Should reactivate suspended car")
        void shouldReactivateSuspendedCar() {
            testCar.setApprovalStatus(ApprovalStatus.SUSPENDED);
            testCar.setListingStatus(ListingStatus.SUSPENDED);
            testCar.setAvailable(false);
            testCar.setRejectionReason("Previous violation");

            when(carRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(testCar));
            when(carRepo.save(any(Car.class))).thenAnswer(inv -> inv.getArgument(0));

            AdminCarDto result = adminCarService.reactivateCar(100L, testAdmin);

            assertThat(result.getApprovalStatus()).isEqualTo("APPROVED");
            assertThat(testCar.getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED);
            assertThat(testCar.getListingStatus()).isEqualTo(ListingStatus.APPROVED);
            assertThat(testCar.isAvailable()).isTrue();
            assertThat(testCar.getRejectionReason()).isNull();
        }

        @Test
        @DisplayName("Should block reactivation when compliance has drifted")
        void shouldBlockReactivationWhenComplianceHasDrifted() {
            testCar.setApprovalStatus(ApprovalStatus.SUSPENDED);
            testCar.setListingStatus(ListingStatus.SUSPENDED);
            testCar.setRegistrationExpiryDate(LocalDate.now().minusDays(1));

            when(carRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(testCar));

            assertThatThrownBy(() -> adminCarService.reactivateCar(100L, testAdmin))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot reactivate car")
                    .hasMessageContaining("Registration expired");

            verify(carRepo, never()).save(any());
        }
    }
}

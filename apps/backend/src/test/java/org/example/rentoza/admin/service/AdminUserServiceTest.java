package org.example.rentoza.admin.service;

import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.admin.repository.AdminUserRepository;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.cancellation.CancellationRecord;
import org.example.rentoza.booking.cancellation.CancellationSettlementService;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.review.ReviewRepository;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.example.rentoza.user.trust.AccountAccessState;
import org.example.rentoza.user.trust.AccountTrustSnapshot;
import org.example.rentoza.user.trust.AccountTrustStateService;
import org.example.rentoza.user.trust.OwnerVerificationState;
import org.example.rentoza.user.trust.RegistrationCompletionState;
import org.example.rentoza.user.trust.RenterVerificationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminUserService}.
 *
 * <p>Covers user deletion cascades, ban moderation, and risk score calculation
 * with audit trail verification via {@link org.mockito.ArgumentCaptor}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminUserService Tests")
class AdminUserServiceTest {

    @Mock
    private AdminUserRepository userRepo;

    @Mock
    private BookingRepository bookingRepo;

    @Mock
    private CarRepository carRepo;

    @Mock
    private ReviewRepository reviewRepo;

    @Mock
    private DamageClaimRepository damageClaimRepo;

    @Mock
    private CancellationSettlementService cancellationSettlementService;

    @Mock
    private AdminAuditService auditService;

    @Mock
    private AccountTrustStateService accountTrustStateService;

    @Captor
    private ArgumentCaptor<AdminAction> actionCaptor;

    @Captor
    private ArgumentCaptor<ResourceType> resourceTypeCaptor;

    @Captor
    private ArgumentCaptor<Long> resourceIdCaptor;

    private AdminUserService adminUserService;

    private User testAdmin;
    private User testUser;

    @BeforeEach
    void setUp() {
        adminUserService = new AdminUserService(
            userRepo,
            bookingRepo,
            carRepo,
            reviewRepo,
            damageClaimRepo,
            cancellationSettlementService,
            auditService,
            accountTrustStateService
        );

        testAdmin = new User();
        testAdmin.setId(1L);
        testAdmin.setEmail("admin@test.com");
        testAdmin.setRole(Role.ADMIN);

        testUser = new User();
        testUser.setId(2L);
        testUser.setEmail("user@test.com");
        testUser.setRole(Role.USER);
        testUser.setFirstName("Test");
        testUser.setLastName("User");
        testUser.setCreatedAt(Instant.now().minus(60, ChronoUnit.DAYS));

        org.mockito.Mockito.lenient().when(accountTrustStateService.snapshot(any(User.class)))
            .thenReturn(new AccountTrustSnapshot(
                AccountAccessState.ACTIVE,
                org.example.rentoza.user.RegistrationStatus.ACTIVE,
                RegistrationCompletionState.COMPLETE,
                testUser.getDriverLicenseStatus(),
                RenterVerificationState.NOT_STARTED,
                OwnerVerificationState.NOT_APPLICABLE,
                testUser.getRiskLevel(),
                true,
                false,
                false,
                java.util.List.of()
            ));
    }

    // ==================== deleteUser ====================

    @Test
    @DisplayName("listUsers maps summary status from centralized trust model")
    void listUsers_UsesCentralizedTrustStatus() {
        testUser.setEnabled(true);
        testUser.setBanned(false);
        testUser.setLocked(false);
        testUser.setRegistrationStatus(org.example.rentoza.user.RegistrationStatus.SUSPENDED);

        when(userRepo.findAllUsers(any())).thenReturn(new PageImpl<>(List.of(testUser), PageRequest.of(0, 20), 1));
        when(accountTrustStateService.snapshot(testUser)).thenReturn(new AccountTrustSnapshot(
                AccountAccessState.SUSPENDED,
                org.example.rentoza.user.RegistrationStatus.SUSPENDED,
                RegistrationCompletionState.COMPLETE,
                testUser.getDriverLicenseStatus(),
                RenterVerificationState.NOT_STARTED,
                OwnerVerificationState.NOT_APPLICABLE,
                testUser.getRiskLevel(),
                false,
                false,
                false,
                List.of()
        ));

        Page<org.example.rentoza.admin.dto.AdminUserDto> result = adminUserService.listUsers(PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getAccountAccessState()).isEqualTo("SUSPENDED");
        assertThat(result.getContent().get(0).getStatusLabel()).isEqualTo("SUSPENDED");
    }

    @Nested
    @DisplayName("deleteUser")
    class DeleteUser {

        @Test
        @DisplayName("Happy path: cascades bookings, cars, reviews; audits BEFORE delete")
        void deleteUser_happyPath_cascadesAndAuditsBeforeDelete() {
            // Arrange
            when(userRepo.findById(2L)).thenReturn(Optional.of(testUser));
            when(auditService.toJson(any())).thenReturn("{}");

            Booking activeBooking = new Booking();
            activeBooking.setId(10L);
            activeBooking.setStatus(BookingStatus.ACTIVE);
            when(bookingRepo.findByRenterIdAndStatusIn(eq(2L), any()))
                    .thenReturn(List.of(activeBooking));
                when(cancellationSettlementService.beginFullRefundSettlement(any(), any(), any(), anyString(), anyString()))
                    .thenAnswer(invocation -> {
                    Booking booking = invocation.getArgument(0);
                    booking.setStatus(BookingStatus.CANCELLATION_PENDING_SETTLEMENT);
                    CancellationRecord record = new CancellationRecord();
                    record.setId(99L);
                    record.setBooking(booking);
                    return record;
                    });

            Car ownedCar = new Car();
            ownedCar.setId(20L);
            ownedCar.setAvailable(true);
            when(carRepo.findByOwnerId(2L)).thenReturn(List.of(ownedCar));

            when(reviewRepo.anonymizeReviewsByReviewerId(2L)).thenReturn(3);
            when(reviewRepo.anonymizeReviewsByRevieweeId(2L)).thenReturn(2);

            // Act
            adminUserService.deleteUser(2L, "Policy violation", testAdmin);

            // Assert - audit is logged BEFORE delete (H-7 fix)
            InOrder inOrder = inOrder(auditService, userRepo);
            inOrder.verify(auditService).logAction(
                    eq(testAdmin),
                    eq(AdminAction.USER_DELETED),
                    eq(ResourceType.USER),
                    eq(2L),
                    anyString(),
                    isNull(),
                    eq("Policy violation")
            );
            inOrder.verify(userRepo).delete(testUser);

            // Assert - cascade: bookings cancelled
            assertThat(activeBooking.getStatus()).isEqualTo(BookingStatus.CANCELLATION_PENDING_SETTLEMENT);
            verify(cancellationSettlementService).beginFullRefundSettlement(eq(activeBooking), any(), any(), anyString(), anyString());

            // Assert - cascade: cars deactivated
            assertThat(ownedCar.isAvailable()).isFalse();
            verify(carRepo).save(ownedCar);

            // Assert - cascade: reviews anonymized
            verify(reviewRepo).anonymizeReviewsByReviewerId(2L);
            verify(reviewRepo).anonymizeReviewsByRevieweeId(2L);
        }

        @Test
        @DisplayName("Throws when target is ADMIN")
        void deleteUser_adminTarget_throwsIllegalArgument() {
            // Arrange
            User targetAdmin = new User();
            targetAdmin.setId(3L);
            targetAdmin.setEmail("other-admin@test.com");
            targetAdmin.setRole(Role.ADMIN);

            when(userRepo.findById(3L)).thenReturn(Optional.of(targetAdmin));

            // Act & Assert
            assertThatThrownBy(() ->
                    adminUserService.deleteUser(3L, "No reason", testAdmin)
            )
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Cannot delete other admins");

            verify(userRepo, never()).delete(any());
            verify(auditService, never()).logAction(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Throws when admin tries to delete themselves")
        void deleteUser_selfDeletion_throwsIllegalArgument() {
            // Arrange
            when(userRepo.findById(1L)).thenReturn(Optional.of(testAdmin));

            // Act & Assert
            assertThatThrownBy(() ->
                    adminUserService.deleteUser(1L, "Self delete", testAdmin)
            )
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Cannot delete yourself");

            verify(userRepo, never()).delete(any());
            verify(auditService, never()).logAction(any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ==================== banUser ====================

    @Nested
    @DisplayName("banUser")
    class BanUser {

        @Test
        @DisplayName("Happy path: sets banned=true, reason, bannedAt and audits action")
        void banUser_happyPath_setsFieldsAndAudits() {
            // Arrange
            when(userRepo.findById(2L)).thenReturn(Optional.of(testUser));
            when(auditService.toJson(any())).thenReturn("{}");

            // Act
            adminUserService.banUser(2L, "Fraudulent activity", testAdmin);

            // Assert - user fields updated
            assertThat(testUser.isBanned()).isTrue();
            assertThat(testUser.getBanReason()).isEqualTo("Fraudulent activity");
            assertThat(testUser.getBannedAt()).isNotNull();
            verify(userRepo).save(testUser);

            // Assert - audit logged with correct action
            verify(auditService).logAction(
                    eq(testAdmin),
                    actionCaptor.capture(),
                    resourceTypeCaptor.capture(),
                    resourceIdCaptor.capture(),
                    anyString(),
                    anyString(),
                    eq("Fraudulent activity")
            );

            assertThat(actionCaptor.getValue()).isEqualTo(AdminAction.USER_BANNED);
            assertThat(resourceTypeCaptor.getValue()).isEqualTo(ResourceType.USER);
            assertThat(resourceIdCaptor.getValue()).isEqualTo(2L);
        }

        @Test
        @DisplayName("Throws when target is ADMIN")
        void banUser_adminTarget_throwsIllegalArgument() {
            // Arrange
            User targetAdmin = new User();
            targetAdmin.setId(3L);
            targetAdmin.setEmail("other-admin@test.com");
            targetAdmin.setRole(Role.ADMIN);

            when(userRepo.findById(3L)).thenReturn(Optional.of(targetAdmin));

            // Act & Assert
            assertThatThrownBy(() ->
                    adminUserService.banUser(3L, "Reason", testAdmin)
            )
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Cannot ban other admins");

            verify(userRepo, never()).save(any());
            verify(auditService, never()).logAction(any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ==================== calculateRiskScore ====================

    @Nested
    @DisplayName("calculateRiskScore")
    class CalculateRiskScore {

        @Test
        @DisplayName("New account (<30 days) includes +10 points")
        void calculateRiskScore_newAccount_includes10Points() {
            // Arrange
            User newUser = new User();
            newUser.setId(10L);
            newUser.setCreatedAt(Instant.now().minus(10, ChronoUnit.DAYS));
            newUser.setPhone("0641234567");

            when(bookingRepo.findByRenterId(10L)).thenReturn(Collections.emptyList());
            when(damageClaimRepo.countByGuestId(10L)).thenReturn(0L);

            // Act
            Integer score = adminUserService.calculateRiskScore(newUser);

            // Assert - new account contributes +10
            assertThat(score).isEqualTo(10);
        }

        @Test
        @DisplayName("Banned account includes +40 points")
        void calculateRiskScore_bannedAccount_includes40Points() {
            // Arrange
            User bannedUser = new User();
            bannedUser.setId(11L);
            bannedUser.setBanned(true);
            bannedUser.setPhone("0641234567");
            bannedUser.setCreatedAt(Instant.now().minus(90, ChronoUnit.DAYS));

            when(bookingRepo.findByRenterId(11L)).thenReturn(Collections.emptyList());
            when(damageClaimRepo.countByGuestId(11L)).thenReturn(0L);

            // Act
            Integer score = adminUserService.calculateRiskScore(bannedUser);

            // Assert - banned contributes +40
            assertThat(score).isEqualTo(40);
        }

        @Test
        @DisplayName("High cancellation rate (>30%) includes +15 points")
        void calculateRiskScore_highCancellationRate_includes15Points() {
            // Arrange
            User riskUser = new User();
            riskUser.setId(12L);
            riskUser.setPhone("0641234567");
            riskUser.setCreatedAt(Instant.now().minus(90, ChronoUnit.DAYS));

            // 3 bookings total, 2 cancelled => 66% cancellation rate (>30%)
            Booking completed = new Booking();
            completed.setStatus(BookingStatus.COMPLETED);
            Booking cancelled1 = new Booking();
            cancelled1.setStatus(BookingStatus.CANCELLED);
            Booking cancelled2 = new Booking();
            cancelled2.setStatus(BookingStatus.CANCELLED);

            when(bookingRepo.findByRenterId(12L))
                    .thenReturn(List.of(completed, cancelled1, cancelled2));
            when(damageClaimRepo.countByGuestId(12L)).thenReturn(0L);

            // Act
            Integer score = adminUserService.calculateRiskScore(riskUser);

            // Assert - high cancellation contributes +15
            assertThat(score).isEqualTo(15);
        }

        @Test
        @DisplayName("Score is capped at 100 when all risk factors are present")
        void calculateRiskScore_allFactors_cappedAt100() {
            // Arrange
            User extremeRiskUser = new User();
            extremeRiskUser.setId(13L);
            extremeRiskUser.setBanned(true);           // +40
            extremeRiskUser.setLocked(true);            // +20
            extremeRiskUser.setPhone(null);             // +5 (no phone)
            extremeRiskUser.setCreatedAt(Instant.now().minus(5, ChronoUnit.DAYS)); // +10 (new account)

            // High cancellation rate: 4 cancelled out of 5 bookings => 80% (>30%) => +15
            Booking b1 = new Booking(); b1.setStatus(BookingStatus.COMPLETED);
            Booking b2 = new Booking(); b2.setStatus(BookingStatus.CANCELLED);
            Booking b3 = new Booking(); b3.setStatus(BookingStatus.CANCELLED);
            Booking b4 = new Booking(); b4.setStatus(BookingStatus.CANCELLED);
            Booking b5 = new Booking(); b5.setStatus(BookingStatus.CANCELLED);

            when(bookingRepo.findByRenterId(13L))
                    .thenReturn(List.of(b1, b2, b3, b4, b5));
            // 5 disputes => +50 but capped at +30
            when(damageClaimRepo.countByGuestId(13L)).thenReturn(5L);

            // Total without cap: 40 + 20 + 5 + 10 + 15 + 30 = 120
            // After cap: 100

            // Act
            Integer score = adminUserService.calculateRiskScore(extremeRiskUser);

            // Assert
            assertThat(score).isEqualTo(100);
        }
    }
}

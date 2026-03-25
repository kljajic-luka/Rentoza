package org.example.rentoza.booking.dispute;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.dispute.dto.DamageClaimDTO;
import org.example.rentoza.car.Car;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DamageClaimServiceTest {

    @Mock private DamageClaimRepository claimRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;

    private DamageClaimService damageClaimService;
    private MeterRegistry meterRegistry;
    private ObjectMapper objectMapper;

    private User host;
    private User guest;
    private User admin;
    private Car car;
    private Booking booking;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        objectMapper = new ObjectMapper();

        damageClaimService = new DamageClaimService(
                claimRepository,
                bookingRepository,
                userRepository,
                notificationService,
                objectMapper,
                meterRegistry
        );

        ReflectionTestUtils.setField(damageClaimService, "responseHours", 72);

        host = new User();
        host.setId(1L);
        host.setEmail("host@test.com");
        host.setFirstName("Test");
        host.setLastName("Host");

        guest = new User();
        guest.setId(2L);
        guest.setEmail("guest@test.com");
        guest.setFirstName("Test");
        guest.setLastName("Guest");

        admin = new User();
        admin.setId(3L);
        admin.setEmail("admin@test.com");
        admin.setRole(org.example.rentoza.user.Role.ADMIN);

        car = new Car();
        car.setId(100L);
        car.setOwner(host);

        booking = new Booking();
        booking.setId(1000L);
        booking.setCar(car);
        booking.setRenter(guest);
        booking.setStatus(BookingStatus.COMPLETED);
        booking.setCheckoutCompletedAt(Instant.now().minus(Duration.ofHours(24)));
    }

    @Nested
    @DisplayName("Claim Creation")
    class ClaimCreationTests {

        @Test
        @DisplayName("Should create claim within 48-hour window")
        void shouldCreateClaimWithin48HourWindow() {
            when(bookingRepository.findByIdWithRelations(1000L)).thenReturn(Optional.of(booking));
            when(claimRepository.hasActiveClaim(eq(1000L), any(), any())).thenReturn(false);
            when(claimRepository.save(any(DamageClaim.class))).thenAnswer(inv -> {
                DamageClaim claim = inv.getArgument(0);
                claim.setId(1L);
                return claim;
            });

            DamageClaimDTO result = damageClaimService.createClaim(
                    1000L, "Scratch on rear bumper", BigDecimal.valueOf(15000),
                    List.of(1L, 2L), host.getId());

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(DamageClaimStatus.PENDING);

            ArgumentCaptor<DamageClaim> captor = ArgumentCaptor.forClass(DamageClaim.class);
            verify(claimRepository).save(captor.capture());
            DamageClaim saved = captor.getValue();
            assertThat(saved.getDescription()).isEqualTo("Scratch on rear bumper");
            assertThat(saved.getClaimedAmount()).isEqualByComparingTo(BigDecimal.valueOf(15000));
            assertThat(saved.getInitiator()).isEqualTo(ClaimInitiator.OWNER);
        }

        @Test
        @DisplayName("Should reject claim after 48-hour window")
        void shouldRejectClaimAfter48HourWindow() {
            booking.setCheckoutCompletedAt(Instant.now().minus(Duration.ofHours(72)));
            when(bookingRepository.findByIdWithRelations(1000L)).thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> damageClaimService.createClaim(
                    1000L, "Scratch", BigDecimal.valueOf(15000), List.of(1L), host.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("istekao");
        }

        @ParameterizedTest(name = "Hours since checkout: {0}")
        @ValueSource(ints = {1, 12, 24, 36, 47})
        @DisplayName("Should accept claims within various times under 48h")
        void shouldAcceptClaimsWithinVariousTimesUnder48Hours(int hoursSinceCheckout) {
            booking.setCheckoutCompletedAt(Instant.now().minus(Duration.ofHours(hoursSinceCheckout)));
            when(bookingRepository.findByIdWithRelations(1000L)).thenReturn(Optional.of(booking));
            when(claimRepository.hasActiveClaim(eq(1000L), any(), any())).thenReturn(false);
            when(claimRepository.save(any(DamageClaim.class))).thenAnswer(inv -> {
                DamageClaim claim = inv.getArgument(0);
                claim.setId(1L);
                return claim;
            });

            DamageClaimDTO result = damageClaimService.createClaim(
                    1000L, "Damage", BigDecimal.valueOf(10000), List.of(1L), host.getId());
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Should allow claim during checkout flow (no checkout time yet)")
        void shouldAllowClaimDuringCheckoutFlow() {
            booking.setCheckoutCompletedAt(null);
            when(bookingRepository.findByIdWithRelations(1000L)).thenReturn(Optional.of(booking));
            when(claimRepository.hasActiveClaim(eq(1000L), any(), any())).thenReturn(false);
            when(claimRepository.save(any(DamageClaim.class))).thenAnswer(inv -> {
                DamageClaim claim = inv.getArgument(0);
                claim.setId(1L);
                return claim;
            });

            DamageClaimDTO result = damageClaimService.createClaim(
                    1000L, "Damage during checkout", BigDecimal.valueOf(10000),
                    List.of(1L), host.getId());
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Should reject duplicate claim for same booking")
        void shouldRejectDuplicateClaimForSameBooking() {
            when(bookingRepository.findByIdWithRelations(1000L)).thenReturn(Optional.of(booking));
            when(claimRepository.hasActiveClaim(eq(1000L), any(), any())).thenReturn(true);

            assertThatThrownBy(() -> damageClaimService.createClaim(
                    1000L, "Another", BigDecimal.valueOf(5000), List.of(1L), host.getId()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Should reject claim from non-owner")
        void shouldRejectClaimFromNonOwner() {
            when(bookingRepository.findByIdWithRelations(1000L)).thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> damageClaimService.createClaim(
                    1000L, "Fake", BigDecimal.valueOf(5000), List.of(1L), guest.getId()))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("Should throw when booking not found")
        void shouldThrowWhenBookingNotFound() {
            when(bookingRepository.findByIdWithRelations(9999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> damageClaimService.createClaim(
                    9999L, "Desc", BigDecimal.valueOf(5000), List.of(1L), host.getId()))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Guest Accept and Dispute (with checkout-specific transitions)")
    class GuestResponseTests {

        private DamageClaim pendingClaim;
        private DamageClaim checkoutPendingClaim;

        @BeforeEach
        void setUpClaims() {
            pendingClaim = DamageClaim.builder()
                    .id(1L).booking(booking).host(host).guest(guest)
                    .description("Host claim").claimedAmount(BigDecimal.valueOf(15000))
                    .status(DamageClaimStatus.PENDING)
                    .responseDeadline(Instant.now().plus(Duration.ofHours(72)))
                    .initiator(ClaimInitiator.OWNER).build();

            checkoutPendingClaim = DamageClaim.builder()
                    .id(2L).booking(booking).host(host).guest(guest)
                    .description("Checkout damage").claimedAmount(BigDecimal.valueOf(20000))
                    .status(DamageClaimStatus.CHECKOUT_PENDING)
                    .responseDeadline(Instant.now().plus(Duration.ofHours(168)))
                    .initiator(ClaimInitiator.OWNER).build();
        }

        @Test
        @DisplayName("Legacy PENDING accept -> ACCEPTED_BY_GUEST")
        void shouldAcceptLegacyPendingClaim() {
            when(claimRepository.findById(1L)).thenReturn(Optional.of(pendingClaim));
            when(userRepository.findById(guest.getId())).thenReturn(Optional.of(guest));
            when(claimRepository.save(any(DamageClaim.class))).thenAnswer(inv -> inv.getArgument(0));

            DamageClaimDTO result = damageClaimService.acceptClaim(1L, "I accept", guest.getId());
            assertThat(result.getStatus()).isEqualTo(DamageClaimStatus.ACCEPTED_BY_GUEST);
        }

        @Test
        @DisplayName("[P1] Legacy high-value claim (adminReviewRequired=true) blocks guest acceptance")
        void shouldBlockGuestAcceptOnHighValueLegacyClaim() {
            pendingClaim.setAdminReviewRequired(true);
            when(claimRepository.findById(1L)).thenReturn(Optional.of(pendingClaim));
            when(userRepository.findById(guest.getId())).thenReturn(Optional.of(guest));

            assertThatThrownBy(() -> damageClaimService.acceptClaim(1L, "I accept", guest.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("50.000 RSD")
                    .hasMessageContaining("administratora");
        }

        @Test
        @DisplayName("[P1] Checkout high-value claim (adminReviewRequired=true) blocks guest acceptance")
        void shouldBlockGuestAcceptOnHighValueCheckoutClaim() {
            checkoutPendingClaim.setAdminReviewRequired(true);
            when(claimRepository.findById(2L)).thenReturn(Optional.of(checkoutPendingClaim));
            when(userRepository.findById(guest.getId())).thenReturn(Optional.of(guest));

            assertThatThrownBy(() -> damageClaimService.acceptClaim(2L, "I accept", guest.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("50.000 RSD");
        }

        @Test
        @DisplayName("[P1] Claim with adminReviewRequired=false allows guest acceptance")
        void shouldAllowGuestAcceptWhenAdminReviewNotRequired() {
            pendingClaim.setAdminReviewRequired(false);
            when(claimRepository.findById(1L)).thenReturn(Optional.of(pendingClaim));
            when(userRepository.findById(guest.getId())).thenReturn(Optional.of(guest));
            when(claimRepository.save(any(DamageClaim.class))).thenAnswer(inv -> inv.getArgument(0));

            DamageClaimDTO result = damageClaimService.acceptClaim(1L, "I accept", guest.getId());
            assertThat(result.getStatus()).isEqualTo(DamageClaimStatus.ACCEPTED_BY_GUEST);
        }

        @Test
        @DisplayName("CHECKOUT_PENDING accept -> CHECKOUT_GUEST_ACCEPTED")
        void shouldRouteCheckoutAcceptToCheckoutStatus() {
            when(claimRepository.findById(2L)).thenReturn(Optional.of(checkoutPendingClaim));
            when(userRepository.findById(guest.getId())).thenReturn(Optional.of(guest));
            when(claimRepository.save(any(DamageClaim.class))).thenAnswer(inv -> inv.getArgument(0));

            DamageClaimDTO result = damageClaimService.acceptClaim(2L, "I accept", guest.getId());
            assertThat(result.getStatus()).isEqualTo(DamageClaimStatus.CHECKOUT_GUEST_ACCEPTED);

            ArgumentCaptor<DamageClaim> captor = ArgumentCaptor.forClass(DamageClaim.class);
            verify(claimRepository).save(captor.capture());
            assertThat(captor.getValue().getApprovedAmount())
                    .isEqualByComparingTo(BigDecimal.valueOf(20000));
        }

        @Test
        @DisplayName("Legacy PENDING dispute -> DISPUTED")
        void shouldDisputeLegacyPendingClaim() {
            when(claimRepository.findById(1L)).thenReturn(Optional.of(pendingClaim));
            when(userRepository.findById(guest.getId())).thenReturn(Optional.of(guest));
            when(claimRepository.save(any(DamageClaim.class))).thenAnswer(inv -> inv.getArgument(0));

            DamageClaimDTO result = damageClaimService.disputeClaim(
                    1L, "Pre-existing damage", guest.getId());
            assertThat(result.getStatus()).isEqualTo(DamageClaimStatus.DISPUTED);
        }

        @Test
        @DisplayName("CHECKOUT_PENDING dispute -> CHECKOUT_GUEST_DISPUTED")
        void shouldRouteCheckoutDisputeToCheckoutStatus() {
            when(claimRepository.findById(2L)).thenReturn(Optional.of(checkoutPendingClaim));
            when(userRepository.findById(guest.getId())).thenReturn(Optional.of(guest));
            when(claimRepository.save(any(DamageClaim.class))).thenAnswer(inv -> inv.getArgument(0));

            DamageClaimDTO result = damageClaimService.disputeClaim(
                    2L, "Checkout damage was pre-existing", guest.getId());
            assertThat(result.getStatus()).isEqualTo(DamageClaimStatus.CHECKOUT_GUEST_DISPUTED);
        }

        @Test
        @DisplayName("Should reject dispute after deadline")
        void shouldRejectDisputeAfterDeadline() {
            pendingClaim.setResponseDeadline(Instant.now().minus(Duration.ofHours(1)));
            when(claimRepository.findById(1L)).thenReturn(Optional.of(pendingClaim));
            when(userRepository.findById(guest.getId())).thenReturn(Optional.of(guest));

            assertThatThrownBy(() -> damageClaimService.disputeClaim(1L, "Too late", guest.getId()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Should reject dispute from host")
        void shouldRejectDisputeFromHost() {
            when(claimRepository.findById(1L)).thenReturn(Optional.of(pendingClaim));
            when(userRepository.findById(host.getId())).thenReturn(Optional.of(host));

            assertThatThrownBy(() -> damageClaimService.disputeClaim(1L, "Invalid", host.getId()))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("Should reject accept on non-pending claim")
        void shouldRejectAcceptOnAlreadyResolvedClaim() {
            pendingClaim.setStatus(DamageClaimStatus.ACCEPTED_BY_GUEST);
            when(claimRepository.findById(1L)).thenReturn(Optional.of(pendingClaim));
            when(userRepository.findById(guest.getId())).thenReturn(Optional.of(guest));

            assertThatThrownBy(() -> damageClaimService.acceptClaim(1L, "Again", guest.getId()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Admin Resolution")
    class AdminResolutionTests {

        private DamageClaim disputedClaim;

        @BeforeEach
        void setUpDisputedClaim() {
            disputedClaim = DamageClaim.builder()
                    .id(1L).booking(booking).host(host).guest(guest)
                    .description("Host claim").claimedAmount(BigDecimal.valueOf(15000))
                    .status(DamageClaimStatus.DISPUTED)
                    .guestResponse("I dispute this")
                    .guestRespondedAt(Instant.now().minus(Duration.ofHours(24)))
                    .initiator(ClaimInitiator.OWNER).build();
        }

        @Test
        @DisplayName("Should allow admin to approve claim")
        void shouldAllowAdminToApprove() {
            when(claimRepository.findById(1L)).thenReturn(Optional.of(disputedClaim));
            when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
            when(claimRepository.save(any(DamageClaim.class))).thenAnswer(inv -> inv.getArgument(0));

            DamageClaimDTO result = damageClaimService.adminApprove(
                    1L, BigDecimal.valueOf(10000), "Partial approval", admin.getId());

            assertThat(result.getStatus()).isEqualTo(DamageClaimStatus.ADMIN_APPROVED);

            ArgumentCaptor<DamageClaim> captor = ArgumentCaptor.forClass(DamageClaim.class);
            verify(claimRepository).save(captor.capture());
            assertThat(captor.getValue().getApprovedAmount())
                    .isEqualByComparingTo(BigDecimal.valueOf(10000));
            assertThat(captor.getValue().getReviewedBy()).isEqualTo(admin);
            assertThat(captor.getValue().getReviewedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should allow admin to reject claim")
        void shouldAllowAdminToReject() {
            when(claimRepository.findById(1L)).thenReturn(Optional.of(disputedClaim));
            when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
            when(claimRepository.save(any(DamageClaim.class))).thenAnswer(inv -> inv.getArgument(0));

            DamageClaimDTO result = damageClaimService.adminReject(
                    1L, "Pre-existing per check-in photos", admin.getId());

            assertThat(result.getStatus()).isEqualTo(DamageClaimStatus.ADMIN_REJECTED);
        }

        @Test
        @DisplayName("Should reject approval of non-disputed claim")
        void shouldRejectApprovalOfNonDisputedClaim() {
            disputedClaim.setStatus(DamageClaimStatus.PENDING);
            when(claimRepository.findById(1L)).thenReturn(Optional.of(disputedClaim));

            assertThatThrownBy(() -> damageClaimService.adminApprove(
                    1L, BigDecimal.valueOf(15000), "Approved", admin.getId()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Guest-Initiated Claims")
    class GuestInitiatedClaimTests {

        @Test
        @DisplayName("Should allow guest to create claim")
        void shouldAllowGuestToCreateClaim() {
            when(bookingRepository.findByIdWithRelations(1000L)).thenReturn(Optional.of(booking));
            when(userRepository.findById(guest.getId())).thenReturn(Optional.of(guest));
            when(claimRepository.hasActiveClaim(eq(1000L), any(), any())).thenReturn(false);
            when(claimRepository.save(any(DamageClaim.class))).thenAnswer(inv -> {
                DamageClaim claim = inv.getArgument(0);
                claim.setId(1L);
                return claim;
            });

            DamageClaimDTO result = damageClaimService.createGuestClaim(
                    1000L, "Pre-existing scratch",
                    BigDecimal.valueOf(5000), DisputeType.PRE_EXISTING_DAMAGE,
                    List.of(1L, 2L), guest.getId());

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(DamageClaimStatus.PENDING);

            ArgumentCaptor<DamageClaim> captor = ArgumentCaptor.forClass(DamageClaim.class);
            verify(claimRepository).save(captor.capture());
            assertThat(captor.getValue().getInitiator()).isEqualTo(ClaimInitiator.USER);
        }

        @Test
        @DisplayName("Should reject guest claim from non-renter")
        void shouldRejectGuestClaimFromNonRenter() {
            User other = new User();
            other.setId(999L);
            when(bookingRepository.findByIdWithRelations(1000L)).thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> damageClaimService.createGuestClaim(
                    1000L, "Fake", BigDecimal.valueOf(5000),
                    DisputeType.CHECKOUT_DAMAGE, List.of(1L), other.getId()))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("Should reject guest claim above security deposit")
        void shouldRejectGuestClaimAboveSecurityDeposit() {
            booking.setSecurityDeposit(BigDecimal.valueOf(10000));

            when(bookingRepository.findByIdWithRelations(1000L)).thenReturn(Optional.of(booking));
            when(userRepository.findById(guest.getId())).thenReturn(Optional.of(guest));
            when(claimRepository.hasActiveClaim(eq(1000L), any(), any())).thenReturn(false);

            assertThatThrownBy(() -> damageClaimService.createGuestClaim(
                    1000L, "Pre-existing scratch",
                    BigDecimal.valueOf(15000), DisputeType.PRE_EXISTING_DAMAGE,
                    List.of(1L, 2L), guest.getId()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ne može biti veći od depozita");
        }
    }

    @Nested
    @DisplayName("Metrics")
    class MetricsTests {

        @Test
        @DisplayName("Should increment claim created counter")
        void shouldIncrementClaimCreatedCounter() {
            when(bookingRepository.findByIdWithRelations(1000L)).thenReturn(Optional.of(booking));
            when(claimRepository.existsByBookingId(1000L)).thenReturn(false);
            when(claimRepository.save(any(DamageClaim.class))).thenAnswer(inv -> {
                DamageClaim claim = inv.getArgument(0);
                claim.setId(1L);
                return claim;
            });

            double initialCount = meterRegistry.counter("damage_claim.created").count();

            damageClaimService.createClaim(
                    1000L, "Damage", BigDecimal.valueOf(10000),
                    List.of(1L), host.getId());

            double newCount = meterRegistry.counter("damage_claim.created").count();
            assertThat(newCount).isEqualTo(initialCount + 1);
        }
    }

    @Nested
    @DisplayName("Multi-Claim Support (Goal 6)")
    class MultiClaimTests {

        @Test
        @DisplayName("getClaimsByBooking returns all claims for a booking")
        void shouldReturnAllClaimsForBooking() {
            DamageClaim claim1 = DamageClaim.builder()
                    .id(1L).booking(booking).host(host).guest(guest)
                    .description("Host claim").claimedAmount(BigDecimal.valueOf(15000))
                    .status(DamageClaimStatus.PENDING)
                    .initiator(ClaimInitiator.OWNER)
                    .createdAt(Instant.now().minus(Duration.ofHours(48)))
                    .build();
            DamageClaim claim2 = DamageClaim.builder()
                    .id(2L).booking(booking).host(host).guest(guest)
                    .description("Guest counter-claim").claimedAmount(BigDecimal.valueOf(5000))
                    .status(DamageClaimStatus.PENDING)
                    .initiator(ClaimInitiator.USER)
                    .createdAt(Instant.now())
                    .build();

            // findAllByBookingId returns newest first
            when(claimRepository.findAllByBookingId(1000L)).thenReturn(List.of(claim2, claim1));

            List<DamageClaimDTO> results = damageClaimService.getClaimsByBooking(1000L, host.getId());
            assertThat(results).hasSize(2);
            assertThat(results.get(0).getId()).isEqualTo(2L); // newest first
            assertThat(results.get(1).getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("getClaimByBooking returns newest claim")
        void shouldReturnNewestClaimForBooking() {
            DamageClaim older = DamageClaim.builder()
                    .id(1L).booking(booking).host(host).guest(guest)
                    .description("Older claim").claimedAmount(BigDecimal.valueOf(15000))
                    .status(DamageClaimStatus.PENDING)
                    .initiator(ClaimInitiator.OWNER)
                    .createdAt(Instant.now().minus(Duration.ofHours(48)))
                    .build();
            DamageClaim newer = DamageClaim.builder()
                    .id(2L).booking(booking).host(host).guest(guest)
                    .description("Newer claim").claimedAmount(BigDecimal.valueOf(5000))
                    .status(DamageClaimStatus.PENDING)
                    .initiator(ClaimInitiator.USER)
                    .createdAt(Instant.now())
                    .build();

            when(claimRepository.findAllByBookingId(1000L)).thenReturn(List.of(newer, older));

            DamageClaimDTO result = damageClaimService.getClaimByBooking(1000L, host.getId());
            assertThat(result.getId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("getClaimsByBooking throws when no claims exist")
        void shouldThrowWhenNoClaimsExist() {
            when(claimRepository.findAllByBookingId(9999L)).thenReturn(List.of());

            assertThatThrownBy(() -> damageClaimService.getClaimsByBooking(9999L, host.getId()))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}

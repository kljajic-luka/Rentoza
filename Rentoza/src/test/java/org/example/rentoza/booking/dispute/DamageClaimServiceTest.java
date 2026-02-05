//package org.example.rentoza.booking.dispute;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import io.micrometer.core.instrument.MeterRegistry;
//import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
//import org.example.rentoza.booking.Booking;
//import org.example.rentoza.booking.BookingRepository;
//import org.example.rentoza.booking.BookingStatus;
//import org.example.rentoza.booking.dispute.dto.DamageClaimDTO;
//import org.example.rentoza.car.Car;
//import org.example.rentoza.exception.ResourceNotFoundException;
//import org.example.rentoza.notification.NotificationService;
//import org.example.rentoza.user.User;
//import org.example.rentoza.user.UserRepository;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Nested;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.junit.jupiter.params.ParameterizedTest;
//import org.junit.jupiter.params.provider.ValueSource;
//import org.mockito.ArgumentCaptor;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.springframework.security.access.AccessDeniedException;
//import org.springframework.test.util.ReflectionTestUtils;
//
//import java.math.BigDecimal;
//import java.time.Duration;
//import java.time.Instant;
//import java.time.LocalDateTime;
//import java.util.Collections;
//import java.util.List;
//import java.util.Optional;
//
//import static org.assertj.core.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.*;
//
///**
// * Unit tests for DamageClaimService.
// *
// * <h2>Critical Business Logic Tested</h2>
// * <ul>
// *   <li>Claim creation within 48-hour window</li>
// *   <li>Guest dispute capability</li>
// *   <li>Admin resolution</li>
// *   <li>Access control (host-only claim creation)</li>
// *   <li>Duplicate claim prevention</li>
// *   <li>Response deadline enforcement</li>
// * </ul>
// */
//@ExtendWith(MockitoExtension.class)
//class DamageClaimServiceTest {
//
//    @Mock private DamageClaimRepository claimRepository;
//    @Mock private BookingRepository bookingRepository;
//    @Mock private UserRepository userRepository;
//    @Mock private NotificationService notificationService;
//
//    private DamageClaimService damageClaimService;
//    private MeterRegistry meterRegistry;
//    private ObjectMapper objectMapper;
//
//    private User host;
//    private User guest;
//    private User admin;
//    private Car car;
//    private Booking booking;
//
//    @BeforeEach
//    void setUp() {
//        meterRegistry = new SimpleMeterRegistry();
//        objectMapper = new ObjectMapper();
//
//        damageClaimService = new DamageClaimService(
//                claimRepository,
//                bookingRepository,
//                userRepository,
//                notificationService,
//                objectMapper,
//                meterRegistry
//        );
//
//        // Set response hours
//        ReflectionTestUtils.setField(damageClaimService, "responseHours", 72);
//
//        // Create test users
//        host = new User();
//        host.setId(1L);
//        host.setEmail("host@test.com");
//        host.setFirstName("Test");
//        host.setLastName("Host");
//
//        guest = new User();
//        guest.setId(2L);
//        guest.setEmail("guest@test.com");
//        guest.setFirstName("Test");
//        guest.setLastName("Guest");
//
//        admin = new User();
//        admin.setId(3L);
//        admin.setEmail("admin@test.com");
//        admin.setRole(org.example.rentoza.user.Role.ADMIN);
//
//        // Create test car
//        car = new Car();
//        car.setId(100L);
//        car.setOwner(host);
//
//        // Create test booking
//        booking = new Booking();
//        booking.setId(1000L);
//        booking.setCar(car);
//        booking.setRenter(guest);
//        booking.setStatus(BookingStatus.COMPLETED);
//        booking.setCheckoutCompletedAt(Instant.now().minus(Duration.ofHours(24))); // 24h ago
//    }
//
//    // ========================================================================
//    // CLAIM CREATION TESTS
//    // ========================================================================
//
//    @Nested
//    @DisplayName("Claim Creation")
//    class ClaimCreationTests {
//
//        @Test
//        @DisplayName("Should create claim within 48-hour window")
//        void shouldCreateClaimWithin48HourWindow() {
//            // Given: Checkout was 24 hours ago (within 48h window)
//            when(bookingRepository.findByIdWithRelations(1000L)).thenReturn(Optional.of(booking));
//            when(claimRepository.existsByBookingId(1000L)).thenReturn(false);
//            when(claimRepository.save(any(DamageClaim.class))).thenAnswer(inv -> {
//                DamageClaim claim = inv.getArgument(0);
//                claim.setId(1L);
//                return claim;
//            });
//
//            // When
//            DamageClaimDTO result = damageClaimService.createClaim(
//                    1000L,
//                    "Scratch on rear bumper",
//                    BigDecimal.valueOf(15000),
//                    List.of(1L, 2L),
//                    host.getId()
//            );
//
//            // Then
//            assertThat(result).isNotNull();
//            assertThat(result.status()).isEqualTo(DamageClaimStatus.PENDING);
//
//            ArgumentCaptor<DamageClaim> claimCaptor = ArgumentCaptor.forClass(DamageClaim.class);
//            verify(claimRepository).save(claimCaptor.capture());
//
//            DamageClaim savedClaim = claimCaptor.getValue();
//            assertThat(savedClaim.getDescription()).isEqualTo("Scratch on rear bumper");
//            assertThat(savedClaim.getClaimedAmount()).isEqualByComparingTo(BigDecimal.valueOf(15000));
//            assertThat(savedClaim.getInitiator()).isEqualTo(ClaimInitiator.OWNER);
//        }
//
//        @Test
//        @DisplayName("Should reject claim after 48-hour window")
//        void shouldRejectClaimAfter48HourWindow() {
//            // Given: Checkout was 72 hours ago (past 48h window)
//            booking.setCheckoutCompletedAt(Instant.now().minus(Duration.ofHours(72)));
//            when(bookingRepository.findByIdWithRelations(1000L)).thenReturn(Optional.of(booking));
//
//            // When/Then
//            assertThatThrownBy(() ->
//                    damageClaimService.createClaim(
//                            1000L,
//                            "Scratch on rear bumper",
//                            BigDecimal.valueOf(15000),
//                            List.of(1L),
//                            host.getId()
//                    ))
//                    .isInstanceOf(IllegalStateException.class)
//                    .hasMessageContaining("istekao");
//        }
//
//        @ParameterizedTest(name = "Hours since checkout: {0}")
//        @ValueSource(ints = {1, 12, 24, 36, 47})
//        @DisplayName("Should accept claims within various times under 48h")
//        void shouldAcceptClaimsWithinVariousTimesUnder48Hours(int hoursSinceCheckout) {
//            // Given
//            booking.setCheckoutCompletedAt(Instant.now().minus(Duration.ofHours(hoursSinceCheckout)));
//            when(bookingRepository.findByIdWithRelations(1000L)).thenReturn(Optional.of(booking));
//            when(claimRepository.existsByBookingId(1000L)).thenReturn(false);
//            when(claimRepository.save(any(DamageClaim.class))).thenAnswer(inv -> {
//                DamageClaim claim = inv.getArgument(0);
//                claim.setId(1L);
//                return claim;
//            });
//
//            // When
//            DamageClaimDTO result = damageClaimService.createClaim(
//                    1000L,
//                    "Damage description",
//                    BigDecimal.valueOf(10000),
//                    List.of(1L),
//                    host.getId()
//            );
//
//            // Then
//            assertThat(result).isNotNull();
//        }
//
//        @Test
//        @DisplayName("Should allow claim during checkout flow (no checkout time yet)")
//        void shouldAllowClaimDuringCheckoutFlow() {
//            // Given: Checkout not yet completed (claim during checkout)
//            booking.setCheckoutCompletedAt(null);
//            when(bookingRepository.findByIdWithRelations(1000L)).thenReturn(Optional.of(booking));
//            when(claimRepository.existsByBookingId(1000L)).thenReturn(false);
//            when(claimRepository.save(any(DamageClaim.class))).thenAnswer(inv -> {
//                DamageClaim claim = inv.getArgument(0);
//                claim.setId(1L);
//                return claim;
//            });
//
//            // When
//            DamageClaimDTO result = damageClaimService.createClaim(
//                    1000L,
//                    "Damage during checkout",
//                    BigDecimal.valueOf(10000),
//                    List.of(1L),
//                    host.getId()
//            );
//
//            // Then: Allowed (checkout in progress)
//            assertThat(result).isNotNull();
//        }
//
//        @Test
//        @DisplayName("Should reject duplicate claim for same booking")
//        void shouldRejectDuplicateClaimForSameBooking() {
//            // Given: Claim already exists
//            when(bookingRepository.findByIdWithRelations(1000L)).thenReturn(Optional.of(booking));
//            when(claimRepository.existsByBookingId(1000L)).thenReturn(true);
//
//            // When/Then
//            assertThatThrownBy(() ->
//                    damageClaimService.createClaim(
//                            1000L,
//                            "Another claim",
//                            BigDecimal.valueOf(5000),
//                            List.of(1L),
//                            host.getId()
//                    ))
//                    .isInstanceOf(IllegalStateException.class)
//                    .hasMessageContaining("već postoji");
//        }
//
//        @Test
//        @DisplayName("Should reject claim from non-owner")
//        void shouldRejectClaimFromNonOwner() {
//            // Given: Guest trying to create claim (not allowed for initial claim)
//            when(bookingRepository.findByIdWithRelations(1000L)).thenReturn(Optional.of(booking));
//
//            // When/Then
//            assertThatThrownBy(() ->
//                    damageClaimService.createClaim(
//                            1000L,
//                            "Fake claim",
//                            BigDecimal.valueOf(5000),
//                            List.of(1L),
//                            guest.getId() // Guest, not host
//                    ))
//                    .isInstanceOf(AccessDeniedException.class)
//                    .hasMessageContaining("vlasnik");
//        }
//
//        @Test
//        @DisplayName("Should throw when booking not found")
//        void shouldThrowWhenBookingNotFound() {
//            // Given
//            when(bookingRepository.findByIdWithRelations(9999L)).thenReturn(Optional.empty());
//
//            // When/Then
//            assertThatThrownBy(() ->
//                    damageClaimService.createClaim(
//                            9999L,
//                            "Description",
//                            BigDecimal.valueOf(5000),
//                            List.of(1L),
//                            host.getId()
//                    ))
//                    .isInstanceOf(ResourceNotFoundException.class);
//        }
//    }
//
//    // ========================================================================
//    // GUEST DISPUTE TESTS
//    // ========================================================================
//
//    @Nested
//    @DisplayName("Guest Dispute")
//    class GuestDisputeTests {
//
//        private DamageClaim existingClaim;
//
//        @BeforeEach
//        void setUpClaim() {
//            existingClaim = DamageClaim.builder()
//                    .id(1L)
//                    .booking(booking)
//                    .host(host)
//                    .guest(guest)
//                    .description("Host's claim")
//                    .claimedAmount(BigDecimal.valueOf(15000))
//                    .status(DamageClaimStatus.PENDING)
//                    .responseDeadline(Instant.now().plus(Duration.ofHours(72)))
//                    .initiator(ClaimInitiator.OWNER)
//                    .build();
//        }
//
//        @Test
//        @DisplayName("Should allow guest to dispute claim within deadline")
//        void shouldAllowGuestToDisputeClaimWithinDeadline() {
//            // Given: Claim still within response deadline
//            when(claimRepository.findById(1L)).thenReturn(Optional.of(existingClaim));
//            when(userRepository.findById(guest.getId())).thenReturn(Optional.of(guest));
//            when(claimRepository.save(any(DamageClaim.class))).thenAnswer(inv -> inv.getArgument(0));
//
//            // When
//            DamageClaimDTO result = damageClaimService.disputeClaim(
//                    1L,
//                    guest.getId(),
//                    "I dispute this - damage was pre-existing"
//            );
//
//            // Then
//            assertThat(result.status()).isEqualTo(DamageClaimStatus.DISPUTED);
//
//            ArgumentCaptor<DamageClaim> claimCaptor = ArgumentCaptor.forClass(DamageClaim.class);
//            verify(claimRepository).save(claimCaptor.capture());
//
//            DamageClaim disputed = claimCaptor.getValue();
//            assertThat(disputed.getGuestResponse()).isEqualTo("I dispute this - damage was pre-existing");
//            assertThat(disputed.getGuestRespondedAt()).isNotNull();
//        }
//
//        @Test
//        @DisplayName("Should reject dispute after deadline")
//        void shouldRejectDisputeAfterDeadline() {
//            // Given: Deadline has passed
//            existingClaim.setResponseDeadline(Instant.now().minus(Duration.ofHours(1)));
//            when(claimRepository.findById(1L)).thenReturn(Optional.of(existingClaim));
//            when(userRepository.findById(guest.getId())).thenReturn(Optional.of(guest));
//
//            // When/Then
//            assertThatThrownBy(() ->
//                    damageClaimService.disputeClaim(1L, guest.getId(), "Too late"))
//                    .isInstanceOf(IllegalStateException.class)
//                    .hasMessageContaining("Rok za odgovor je istekao");
//        }
//
//        @Test
//        @DisplayName("Should reject dispute from host (only guest can dispute)")
//        void shouldRejectDisputeFromHost() {
//            // Given
//            when(claimRepository.findById(1L)).thenReturn(Optional.of(existingClaim));
//            when(userRepository.findById(host.getId())).thenReturn(Optional.of(host));
//
//            // When/Then
//            assertThatThrownBy(() ->
//                    damageClaimService.disputeClaim(1L, host.getId(), "Invalid dispute"))
//                    .isInstanceOf(AccessDeniedException.class);
//        }
//
//        @Test
//        @DisplayName("Should reject dispute on non-pending claim")
//        void shouldRejectDisputeOnNonPendingClaim() {
//            // Given: Claim already resolved
//            existingClaim.setStatus(DamageClaimStatus.RESOLVED);
//            when(claimRepository.findById(1L)).thenReturn(Optional.of(existingClaim));
//            when(userRepository.findById(guest.getId())).thenReturn(Optional.of(guest));
//
//            // When/Then
//            assertThatThrownBy(() ->
//                    damageClaimService.disputeClaim(1L, guest.getId(), "Late dispute"))
//                    .isInstanceOf(IllegalStateException.class);
//        }
//    }
//
//    // ========================================================================
//    // ADMIN RESOLUTION TESTS
//    // ========================================================================
//
//    @Nested
//    @DisplayName("Admin Resolution")
//    class AdminResolutionTests {
//
//        private DamageClaim disputedClaim;
//
//        @BeforeEach
//        void setUpDisputedClaim() {
//            disputedClaim = DamageClaim.builder()
//                    .id(1L)
//                    .booking(booking)
//                    .host(host)
//                    .guest(guest)
//                    .description("Host's claim")
//                    .claimedAmount(BigDecimal.valueOf(15000))
//                    .status(DamageClaimStatus.DISPUTED)
//                    .guestResponse("I dispute this")
//                    .guestRespondedAt(Instant.now().minus(Duration.ofHours(24)))
//                    .initiator(ClaimInitiator.OWNER)
//                    .build();
//        }
//
//        @Test
//        @DisplayName("Should allow admin to resolve claim with approved amount")
//        void shouldAllowAdminToResolveClaimWithApprovedAmount() {
//            // Given
//            when(claimRepository.findById(1L)).thenReturn(Optional.of(disputedClaim));
//            when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
//            when(claimRepository.save(any(DamageClaim.class))).thenAnswer(inv -> inv.getArgument(0));
//
//            // When
//            DamageClaimDTO result = damageClaimService.resolveClaim(
//                    1L,
//                    admin.getId(),
//                    BigDecimal.valueOf(10000), // Partial approval
//                    "Photos show pre-existing minor damage, reducing to 10,000 RSD"
//            );
//
//            // Then
//            assertThat(result.status()).isEqualTo(DamageClaimStatus.RESOLVED);
//
//            ArgumentCaptor<DamageClaim> claimCaptor = ArgumentCaptor.forClass(DamageClaim.class);
//            verify(claimRepository).save(claimCaptor.capture());
//
//            DamageClaim resolved = claimCaptor.getValue();
//            assertThat(resolved.getApprovedAmount()).isEqualByComparingTo(BigDecimal.valueOf(10000));
//            assertThat(resolved.getResolvedBy()).isEqualTo(admin);
//            assertThat(resolved.getResolvedAt()).isNotNull();
//        }
//
//        @Test
//        @DisplayName("Should allow admin to reject claim entirely")
//        void shouldAllowAdminToRejectClaimEntirely() {
//            // Given
//            when(claimRepository.findById(1L)).thenReturn(Optional.of(disputedClaim));
//            when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
//            when(claimRepository.save(any(DamageClaim.class))).thenAnswer(inv -> inv.getArgument(0));
//
//            // When
//            DamageClaimDTO result = damageClaimService.resolveClaim(
//                    1L,
//                    admin.getId(),
//                    BigDecimal.ZERO, // Full rejection
//                    "Damage was pre-existing based on check-in photos"
//            );
//
//            // Then
//            assertThat(result.status()).isEqualTo(DamageClaimStatus.RESOLVED);
//
//            ArgumentCaptor<DamageClaim> claimCaptor = ArgumentCaptor.forClass(DamageClaim.class);
//            verify(claimRepository).save(claimCaptor.capture());
//
//            DamageClaim resolved = claimCaptor.getValue();
//            assertThat(resolved.getApprovedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
//        }
//
//        @Test
//        @DisplayName("Should reject resolution from non-admin")
//        void shouldRejectResolutionFromNonAdmin() {
//            // Given: Host trying to resolve (not allowed)
//            host.setRole(org.example.rentoza.user.Role.USER);
//            when(claimRepository.findById(1L)).thenReturn(Optional.of(disputedClaim));
//            when(userRepository.findById(host.getId())).thenReturn(Optional.of(host));
//
//            // When/Then
//            assertThatThrownBy(() ->
//                    damageClaimService.resolveClaim(1L, host.getId(), BigDecimal.valueOf(15000), "Approved"))
//                    .isInstanceOf(AccessDeniedException.class);
//        }
//
//        @Test
//        @DisplayName("Should not allow resolution of already resolved claim")
//        void shouldNotAllowResolutionOfAlreadyResolvedClaim() {
//            // Given: Already resolved
//            disputedClaim.setStatus(DamageClaimStatus.RESOLVED);
//            when(claimRepository.findById(1L)).thenReturn(Optional.of(disputedClaim));
//            when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
//
//            // When/Then
//            assertThatThrownBy(() ->
//                    damageClaimService.resolveClaim(1L, admin.getId(), BigDecimal.valueOf(15000), "Re-resolve"))
//                    .isInstanceOf(IllegalStateException.class);
//        }
//    }
//
//    // ========================================================================
//    // GUEST-INITIATED CLAIM TESTS (Phase 4 Feature)
//    // ========================================================================
//
//    @Nested
//    @DisplayName("Guest-Initiated Claims")
//    class GuestInitiatedClaimTests {
//
//        @Test
//        @DisplayName("Should allow guest to create claim (Phase 4)")
//        void shouldAllowGuestToCreateClaim() {
//            // Given: Guest wants to report pre-existing damage
//            when(bookingRepository.findByIdWithRelations(1000L)).thenReturn(Optional.of(booking));
//            when(claimRepository.existsByBookingId(1000L)).thenReturn(false);
//            when(claimRepository.save(any(DamageClaim.class))).thenAnswer(inv -> {
//                DamageClaim claim = inv.getArgument(0);
//                claim.setId(1L);
//                return claim;
//            });
//
//            // When
//            DamageClaimDTO result = damageClaimService.createGuestClaim(
//                    1000L,
//                    "Pre-existing scratch found during check-in",
//                    List.of(1L, 2L),
//                    guest.getId()
//            );
//
//            // Then
//            assertThat(result).isNotNull();
//            assertThat(result.status()).isEqualTo(DamageClaimStatus.PENDING);
//
//            ArgumentCaptor<DamageClaim> claimCaptor = ArgumentCaptor.forClass(DamageClaim.class);
//            verify(claimRepository).save(claimCaptor.capture());
//
//            DamageClaim savedClaim = claimCaptor.getValue();
//            assertThat(savedClaim.getInitiator()).isEqualTo(ClaimInitiator.USER);
//            assertThat(savedClaim.getReportedBy()).isEqualTo(guest);
//        }
//
//        @Test
//        @DisplayName("Should reject guest claim from non-renter")
//        void shouldRejectGuestClaimFromNonRenter() {
//            // Given: Someone other than the renter
//            User otherUser = new User();
//            otherUser.setId(999L);
//            when(bookingRepository.findByIdWithRelations(1000L)).thenReturn(Optional.of(booking));
//
//            // When/Then
//            assertThatThrownBy(() ->
//                    damageClaimService.createGuestClaim(
//                            1000L,
//                            "Fake claim",
//                            List.of(1L),
//                            otherUser.getId()
//                    ))
//                    .isInstanceOf(AccessDeniedException.class);
//        }
//    }
//
//    // ========================================================================
//    // METRICS TESTS
//    // ========================================================================
//
//    @Nested
//    @DisplayName("Metrics")
//    class MetricsTests {
//
//        @Test
//        @DisplayName("Should increment claim created counter")
//        void shouldIncrementClaimCreatedCounter() {
//            // Given
//            when(bookingRepository.findByIdWithRelations(1000L)).thenReturn(Optional.of(booking));
//            when(claimRepository.existsByBookingId(1000L)).thenReturn(false);
//            when(claimRepository.save(any(DamageClaim.class))).thenAnswer(inv -> {
//                DamageClaim claim = inv.getArgument(0);
//                claim.setId(1L);
//                return claim;
//            });
//
//            double initialCount = meterRegistry.counter("damage_claim.created").count();
//
//            // When
//            damageClaimService.createClaim(
//                    1000L,
//                    "Damage",
//                    BigDecimal.valueOf(10000),
//                    List.of(1L),
//                    host.getId()
//            );
//
//            // Then
//            double newCount = meterRegistry.counter("damage_claim.created").count();
//            assertThat(newCount).isEqualTo(initialCount + 1);
//        }
//    }
//}

package org.example.rentoza.user.trust;

import org.example.rentoza.user.DriverLicenseStatus;
import org.example.rentoza.user.RegistrationStatus;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AccountTrustStateService")
class AccountTrustStateServiceTest {

    private AccountTrustStateService service;

    @BeforeEach
    void setUp() {
        service = new AccountTrustStateService();
    }

    @Test
    @DisplayName("maps incomplete renter")
    void mapsIncompleteRenter() {
        User user = baseRenter();
        user.setRegistrationStatus(RegistrationStatus.INCOMPLETE);

        AccountTrustSnapshot snapshot = service.snapshot(user);

        assertThat(snapshot.accountAccessState()).isEqualTo(AccountAccessState.ACTIVE);
        assertThat(snapshot.registrationCompletionState()).isEqualTo(RegistrationCompletionState.INCOMPLETE);
        assertThat(snapshot.needsProfileCompletion()).isTrue();
        assertThat(snapshot.canBookAsRenter()).isFalse();
    }

    @Test
    @DisplayName("maps active renter with verification not started")
    void mapsActiveRenterNotStarted() {
        User user = baseRenter();
        user.setDriverLicenseStatus(DriverLicenseStatus.NOT_STARTED);

        AccountTrustSnapshot snapshot = service.snapshot(user);

        assertThat(snapshot.renterVerificationState()).isEqualTo(RenterVerificationState.NOT_STARTED);
        assertThat(snapshot.canAuthenticate()).isTrue();
        assertThat(snapshot.canBookAsRenter()).isFalse();
    }

    @Test
    @DisplayName("maps pending review renter")
    void mapsPendingReviewRenter() {
        User user = baseRenter();
        user.setDriverLicenseStatus(DriverLicenseStatus.PENDING_REVIEW);

        AccountTrustSnapshot snapshot = service.snapshot(user);

        assertThat(snapshot.renterVerificationState()).isEqualTo(RenterVerificationState.PENDING_REVIEW);
        assertThat(snapshot.canBookAsRenter()).isFalse();
    }

    @Test
    @DisplayName("maps approved renter as bookable")
    void mapsApprovedRenter() {
        User user = baseRenter();
        user.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);
        user.setDriverLicenseExpiryDate(LocalDate.now().plusYears(2));
        user.setPhoneVerifiedAt(java.time.LocalDateTime.now());

        AccountTrustSnapshot snapshot = service.snapshot(user);

        assertThat(snapshot.renterVerificationState()).isEqualTo(RenterVerificationState.APPROVED);
        assertThat(snapshot.canBookAsRenter()).isTrue();
    }

    @Test
    @DisplayName("maps expired renter as non-bookable")
    void mapsExpiredRenter() {
        User user = baseRenter();
        user.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);
        user.setDriverLicenseExpiryDate(LocalDate.now().minusDays(1));

        AccountTrustSnapshot snapshot = service.snapshot(user);

        assertThat(snapshot.renterVerificationState()).isEqualTo(RenterVerificationState.EXPIRED);
        assertThat(snapshot.canBookAsRenter()).isFalse();
    }

    @Test
    @DisplayName("maps suspended renter as non-bookable")
    void mapsSuspendedRenter() {
        User user = baseRenter();
        user.setDriverLicenseStatus(DriverLicenseStatus.SUSPENDED);

        AccountTrustSnapshot snapshot = service.snapshot(user);

        assertThat(snapshot.renterVerificationState()).isEqualTo(RenterVerificationState.SUSPENDED);
        assertThat(snapshot.canBookAsRenter()).isFalse();
    }

    @Test
    @DisplayName("maps banned renter as non-authenticatable")
    void mapsBannedRenter() {
        User user = baseRenter();
        user.setBanned(true);
        user.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);
        user.setDriverLicenseExpiryDate(LocalDate.now().plusYears(1));

        AccountTrustSnapshot snapshot = service.snapshot(user);

        assertThat(snapshot.accountAccessState()).isEqualTo(AccountAccessState.BANNED);
        assertThat(snapshot.canAuthenticate()).isFalse();
        assertThat(snapshot.canBookAsRenter()).isFalse();
    }

    @Test
    @DisplayName("maps verified owner independently from renter verification")
    void mapsVerifiedOwnerNoRenterApproval() {
        User owner = baseRenter();
        owner.setRole(Role.OWNER);
        owner.setIsIdentityVerified(true);
        owner.setDriverLicenseStatus(DriverLicenseStatus.NOT_STARTED);

        AccountTrustSnapshot snapshot = service.snapshot(owner);

        assertThat(snapshot.ownerVerificationState()).isEqualTo(OwnerVerificationState.VERIFIED);
        assertThat(snapshot.renterVerificationState()).isEqualTo(RenterVerificationState.NOT_STARTED);
        assertThat(snapshot.canBookAsRenter()).isFalse();
    }

    @Test
    @DisplayName("profile completion missing fields for renter exclude driver license metadata")
    void renterMissingFieldsExcludeDriverMetadata() {
        User renter = baseRenter();
        renter.setPhone(null);
        renter.setDateOfBirth(null);
        renter.setDriverLicenseCountry(null);
        renter.setDriverLicenseExpiryDate(null);
        renter.setDriverLicenseNumber(null);

        AccountTrustSnapshot snapshot = service.snapshot(renter);

        assertThat(snapshot.missingProfileFields())
                .containsExactlyInAnyOrder("phone", "dateOfBirth");
    }

    private User baseRenter() {
        User user = new User();
        user.setRole(Role.USER);
        user.setRegistrationStatus(RegistrationStatus.ACTIVE);
        user.setEnabled(true);
        user.setLocked(false);
        user.setBanned(false);
        user.setPhone("+38160123456");
        user.setDateOfBirth(LocalDate.of(1996, 1, 10));
        user.setDriverLicenseStatus(DriverLicenseStatus.NOT_STARTED);
        return user;
    }
}
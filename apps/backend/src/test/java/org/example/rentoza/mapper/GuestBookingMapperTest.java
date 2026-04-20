package org.example.rentoza.mapper;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.dto.GuestBookingPreviewDTO;
import org.example.rentoza.dto.GuestTrustSignalDTO;
import org.example.rentoza.review.Review;
import org.example.rentoza.user.DriverLicenseStatus;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GuestBookingMapper")
class GuestBookingMapperTest {

    @Test
    @DisplayName("maps only provenance-backed renter trust signals")
    void toDTO_MapsProvenanceBackedTrustSignalsOnly() {
        Booking booking = new Booking();
        booking.setStartTime(LocalDateTime.of(2026, 3, 10, 10, 0));
        booking.setEndTime(LocalDateTime.of(2026, 3, 12, 10, 0));
        booking.setInsuranceType("STANDARD");

        User renter = new User();
        renter.setFirstName("Mila");
        renter.setLastName("Jovanovic");
        renter.setCreatedAt(java.time.Instant.parse("2024-01-15T09:00:00Z"));
        renter.setEnabled(true);
        renter.setPhone("+38160123456");
        renter.setIsIdentityVerified(true);
        renter.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);
        renter.setDriverLicenseExpiryDate(LocalDate.now().plusDays(10));
        renter.setDateOfBirth(LocalDate.of(1995, 5, 20));
        renter.setDobVerified(true);
        booking.setRenter(renter);

        GuestBookingPreviewDTO dto = GuestBookingMapper.toDTO(booking, List.<Review>of(), 4.8, 12, 0);

        assertThat(dto.getTrustSignals())
                .extracting(GuestTrustSignalDTO::getCode)
                .containsExactly("DRIVER_APPROVED", "AGE_VERIFIED", "LICENSE_EXPIRING_SOON");

        assertThat(dto.getTrustSignals())
                .extracting(GuestTrustSignalDTO::getLabel)
                .doesNotContain("Email verifikovan", "Telefon verifikovan", "Identitet verifikovan");

        assertThat(dto.getDrivingEligibilityStatus()).isEqualTo("APPROVED");
        assertThat(dto.isAgeVerified()).isTrue();
        assertThat(dto.getBadges()).contains("Iskusan gost", "Top ocenjen", "Pouzdan");
    }

    @Test
    @DisplayName("maps pending driver review without inventing verification badges")
    void toDTO_MapsPendingReviewSignalWithoutWeakProxyBadges() {
        Booking booking = new Booking();
        booking.setStartTime(LocalDateTime.of(2026, 3, 10, 10, 0));
        booking.setEndTime(LocalDateTime.of(2026, 3, 12, 10, 0));
        booking.setInsuranceType("STANDARD");

        User renter = new User();
        renter.setFirstName("Ana");
        renter.setLastName("Markovic");
        renter.setCreatedAt(java.time.Instant.parse("2024-01-15T09:00:00Z"));
        renter.setEnabled(true);
        renter.setPhone("+38160123456");
        renter.setIsIdentityVerified(true);
        renter.setDriverLicenseStatus(DriverLicenseStatus.PENDING_REVIEW);
        booking.setRenter(renter);

        GuestBookingPreviewDTO dto = GuestBookingMapper.toDTO(booking, List.<Review>of(), null, 0, 0);

        assertThat(dto.getTrustSignals())
                .extracting(GuestTrustSignalDTO::getCode)
                .containsExactly("DRIVER_PENDING_REVIEW");

        assertThat(dto.isAgeVerified()).isFalse();
        assertThat(dto.getBadges()).isEmpty();
    }
}
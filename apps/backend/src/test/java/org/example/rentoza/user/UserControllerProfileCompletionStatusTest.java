package org.example.rentoza.user;

import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.security.JwtUserPrincipal;
import org.example.rentoza.user.trust.AccountTrustStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserController profile completion status")
class UserControllerProfileCompletionStatusTest {

    @Mock private UserService userService;
    @Mock private ProfileService profileService;
    @Mock private ProfileCompletionService profileCompletionService;
    @Mock private CurrentUser currentUser;

    private UserController controller;

    @BeforeEach
    void setUp() {
        controller = new UserController(
                userService,
                profileService,
                profileCompletionService,
                new AccountTrustStateService(),
                currentUser
        );
    }

    @Test
    @DisplayName("renter missing fields no longer include driver license metadata")
    void profileCompletionStatusForRenterDoesNotRequireDriverMetadata() {
        User renter = new User();
        renter.setId(11L);
        renter.setRole(Role.USER);
        renter.setRegistrationStatus(RegistrationStatus.INCOMPLETE);
        renter.setEnabled(true);
        renter.setBanned(false);
        renter.setLocked(false);
        renter.setPhone(null);
        renter.setDateOfBirth(LocalDate.of(1998, 5, 1));
        renter.setDriverLicenseNumber(null);
        renter.setDriverLicenseCountry(null);
        renter.setDriverLicenseExpiryDate(null);

        when(userService.getUserById(11L)).thenReturn(Optional.of(renter));

        ResponseEntity<?> response = controller.getProfileCompletionStatus(
                JwtUserPrincipal.create(11L, "renter@test.com", List.of("USER"))
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        List<String> missingFields = (List<String>) body.get("missingFields");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(missingFields).containsExactly("phone");
        assertThat(missingFields)
                .doesNotContain("driverLicenseNumber", "driverLicenseExpiryDate", "driverLicenseCountry");
    }
}
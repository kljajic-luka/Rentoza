package org.example.rentoza.owner;

import org.example.rentoza.owner.dto.OwnerPayoutsDTO;
import org.example.rentoza.payment.PayoutLifecycleStatus;
import org.example.rentoza.security.JwtUserPrincipal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Controller-level tests for GET /api/owner/payouts.
 *
 * Validates:
 * - Controller passes authenticated principal's ID to service
 * - Response shape matches OwnerPayoutsDTO contract
 * - HTTP 200 on success
 * - Empty payouts list for new owners
 */
@ExtendWith(MockitoExtension.class)
class OwnerControllerPayoutsTest {

    @Mock
    private OwnerService ownerService;

    private OwnerController controller;

    @BeforeEach
    void setUp() {
        controller = new OwnerController(ownerService);
    }

    private JwtUserPrincipal ownerPrincipal(Long id) {
        return JwtUserPrincipal.create(id, "owner@test.com", List.of("OWNER"));
    }

    @Test
    @DisplayName("returns 200 with payouts for authenticated owner")
    void returnsPayoutsForAuthenticatedOwner() {
        Long ownerId = 42L;
        OwnerPayoutsDTO.BookingPayoutStatusDTO payoutDto = OwnerPayoutsDTO.BookingPayoutStatusDTO.builder()
                .bookingId(100L)
                .carBrand("BMW")
                .carModel("X5")
                .guestName("Marko Petrovic")
                .payoutStatus(PayoutLifecycleStatus.COMPLETED)
                .tripAmount(new BigDecimal("10000"))
                .platformFee(new BigDecimal("1500"))
                .hostPayoutAmount(new BigDecimal("8500"))
                .attemptCount(0)
                .maxAttempts(3)
                .build();

        OwnerPayoutsDTO expected = new OwnerPayoutsDTO(List.of(payoutDto));
        when(ownerService.getOwnerPayouts(ownerId)).thenReturn(expected);

        ResponseEntity<OwnerPayoutsDTO> response = controller.getOwnerPayouts(ownerPrincipal(ownerId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getPayouts()).hasSize(1);

        OwnerPayoutsDTO.BookingPayoutStatusDTO dto = response.getBody().getPayouts().get(0);
        assertThat(dto.getBookingId()).isEqualTo(100L);
        assertThat(dto.getCarBrand()).isEqualTo("BMW");
        assertThat(dto.getPayoutStatus()).isEqualTo(PayoutLifecycleStatus.COMPLETED);
        assertThat(dto.getHostPayoutAmount()).isEqualByComparingTo("8500");

        verify(ownerService).getOwnerPayouts(ownerId);
    }

    @Test
    @DisplayName("returns 200 with empty list for owner with no payouts")
    void returnsEmptyForNewOwner() {
        Long ownerId = 99L;
        when(ownerService.getOwnerPayouts(ownerId)).thenReturn(new OwnerPayoutsDTO(List.of()));

        ResponseEntity<OwnerPayoutsDTO> response = controller.getOwnerPayouts(ownerPrincipal(ownerId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getPayouts()).isEmpty();
    }

    @Test
    @DisplayName("passes principal ID (not email) to service layer")
    void passesPrincipalIdToService() {
        Long ownerId = 7L;
        when(ownerService.getOwnerPayouts(ownerId)).thenReturn(new OwnerPayoutsDTO(List.of()));

        controller.getOwnerPayouts(ownerPrincipal(ownerId));

        verify(ownerService).getOwnerPayouts(7L);
        verifyNoMoreInteractions(ownerService);
    }

    @Test
    @DisplayName("response contains all financial fields from DTO")
    void responseContainsAllFinancialFields() {
        Long ownerId = 1L;
        OwnerPayoutsDTO.BookingPayoutStatusDTO payoutDto = OwnerPayoutsDTO.BookingPayoutStatusDTO.builder()
                .bookingId(200L)
                .carBrand("Audi")
                .carModel("A4")
                .guestName("Jelena Markovic")
                .tripStartTime("2025-07-01T10:00")
                .tripEndTime("2025-07-03T10:00")
                .payoutStatus(PayoutLifecycleStatus.FAILED)
                .eligibleAt("2025-07-05T12:00:00Z")
                .tripAmount(new BigDecimal("12000"))
                .platformFee(new BigDecimal("1800"))
                .hostPayoutAmount(new BigDecimal("10200"))
                .attemptCount(2)
                .maxAttempts(3)
                .nextRetryAt("2025-07-10T14:00:00Z")
                .lastError("Bank timeout")
                .build();

        when(ownerService.getOwnerPayouts(ownerId)).thenReturn(new OwnerPayoutsDTO(List.of(payoutDto)));

        ResponseEntity<OwnerPayoutsDTO> response = controller.getOwnerPayouts(ownerPrincipal(ownerId));

        OwnerPayoutsDTO.BookingPayoutStatusDTO dto = response.getBody().getPayouts().get(0);
        assertThat(dto.getTripStartTime()).isEqualTo("2025-07-01T10:00");
        assertThat(dto.getTripEndTime()).isEqualTo("2025-07-03T10:00");
        assertThat(dto.getEligibleAt()).isEqualTo("2025-07-05T12:00:00Z");
        assertThat(dto.getNextRetryAt()).isEqualTo("2025-07-10T14:00:00Z");
        assertThat(dto.getLastError()).isEqualTo("Bank timeout");
        assertThat(dto.getAttemptCount()).isEqualTo(2);
        assertThat(dto.getMaxAttempts()).isEqualTo(3);
    }
}

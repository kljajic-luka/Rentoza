package org.example.rentoza.booking;

import org.example.rentoza.booking.dto.BookingDetailsDTO;
import org.example.rentoza.payment.ChargeLifecycleStatus;
import org.example.rentoza.payment.DepositLifecycleStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for BookingDetailsDTO payment lifecycle fields.
 *
 * Validates that the builder correctly carries charge/deposit lifecycle
 * status, security deposit, and legacy payment status through to the DTO.
 */
class BookingDetailsDTOPaymentFieldsTest {

    @Nested
    @DisplayName("Payment lifecycle fields in builder")
    class PaymentLifecycleBuilder {

        @Test
        @DisplayName("all payment lifecycle fields round-trip through builder")
        void allFieldsRoundTrip() {
            BookingDetailsDTO dto = BookingDetailsDTO.builder()
                    .id(1L)
                    .chargeLifecycleStatus(ChargeLifecycleStatus.CAPTURED)
                    .depositLifecycleStatus(DepositLifecycleStatus.AUTHORIZED)
                    .securityDeposit(new BigDecimal("30000"))
                    .paymentStatus("AUTHORIZED")
                    .build();

            assertThat(dto.getChargeLifecycleStatus()).isEqualTo(ChargeLifecycleStatus.CAPTURED);
            assertThat(dto.getDepositLifecycleStatus()).isEqualTo(DepositLifecycleStatus.AUTHORIZED);
            assertThat(dto.getSecurityDeposit()).isEqualByComparingTo("30000");
            assertThat(dto.getPaymentStatus()).isEqualTo("AUTHORIZED");
        }

        @Test
        @DisplayName("null payment fields are accepted (pre-payment bookings)")
        void nullFieldsAccepted() {
            BookingDetailsDTO dto = BookingDetailsDTO.builder()
                    .id(2L)
                    .chargeLifecycleStatus(null)
                    .depositLifecycleStatus(null)
                    .securityDeposit(null)
                    .paymentStatus(null)
                    .build();

            assertThat(dto.getChargeLifecycleStatus()).isNull();
            assertThat(dto.getDepositLifecycleStatus()).isNull();
            assertThat(dto.getSecurityDeposit()).isNull();
            assertThat(dto.getPaymentStatus()).isNull();
        }

        @ParameterizedTest
        @EnumSource(ChargeLifecycleStatus.class)
        @DisplayName("all ChargeLifecycleStatus values serialize through builder")
        void allChargeStatusesSerialize(ChargeLifecycleStatus status) {
            BookingDetailsDTO dto = BookingDetailsDTO.builder()
                    .chargeLifecycleStatus(status)
                    .build();

            assertThat(dto.getChargeLifecycleStatus()).isEqualTo(status);
        }

        @ParameterizedTest
        @EnumSource(DepositLifecycleStatus.class)
        @DisplayName("all DepositLifecycleStatus values serialize through builder")
        void allDepositStatusesSerialize(DepositLifecycleStatus status) {
            BookingDetailsDTO dto = BookingDetailsDTO.builder()
                    .depositLifecycleStatus(status)
                    .build();

            assertThat(dto.getDepositLifecycleStatus()).isEqualTo(status);
        }
    }

    @Nested
    @DisplayName("Default values")
    class DefaultValues {

        @Test
        @DisplayName("pickupLocationEstimated defaults to false")
        void defaultPickupEstimated() {
            BookingDetailsDTO dto = BookingDetailsDTO.builder().build();
            assertThat(dto.isPickupLocationEstimated()).isFalse();
        }
    }
}

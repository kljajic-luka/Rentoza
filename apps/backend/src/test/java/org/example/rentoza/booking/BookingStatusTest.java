package org.example.rentoza.booking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BookingStatus overlap sets")
class BookingStatusTest {

    @Test
    @DisplayName("Blocking statuses include checkout flow")
    void blockingStatusesIncludeCheckoutFlow() {
        assertThat(BookingStatus.BLOCKING_STATUSES).contains(
                BookingStatus.CHECKOUT_OPEN,
                BookingStatus.CHECKOUT_GUEST_COMPLETE,
            BookingStatus.CHECKOUT_HOST_COMPLETE,
            BookingStatus.CHECKOUT_SETTLEMENT_PENDING
        );
        assertThat(BookingStatus.BLOCKING_STATUSES).doesNotContain(BookingStatus.COMPLETED);
    }

    @Test
    @DisplayName("Approval blocking statuses exclude pending requests but include checkout flow")
    void approvalBlockingStatusesExcludePendingRequests() {
        assertThat(BookingStatus.APPROVAL_BLOCKING_STATUSES)
                .doesNotContain(BookingStatus.PENDING_APPROVAL)
                .contains(
                        BookingStatus.ACTIVE,
                        BookingStatus.CHECKOUT_OPEN,
                        BookingStatus.CHECKOUT_GUEST_COMPLETE,
                        BookingStatus.CHECKOUT_HOST_COMPLETE,
                        BookingStatus.CHECKOUT_SETTLEMENT_PENDING
                );
    }

    @Test
    @DisplayName("Refund failed is terminal but cancellation pending settlement is not")
    void terminalStatusesReflectSettlementSemantics() {
        assertThat(BookingStatus.REFUND_FAILED.isTerminal()).isTrue();
        assertThat(BookingStatus.CANCELLATION_PENDING_SETTLEMENT.isTerminal()).isFalse();
    }
}
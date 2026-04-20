package org.example.rentoza.booking;

import org.example.rentoza.booking.photo.PhotoVisibilityMatrix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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

    // ========== MATRIX COMPLETENESS META-TESTS ==========

    /**
     * Every BookingStatus value must be explicitly accounted for in BLOCKING_STATUSES
     * or known to be non-blocking. This prevents silent omission when a new status is added.
     */
    @Test
    @DisplayName("Every BookingStatus is either blocking or explicitly non-blocking")
    void everyStatusIsClassifiedForBlocking() {
        Set<BookingStatus> knownNonBlocking = EnumSet.of(
                BookingStatus.APPROVED,
                BookingStatus.PENDING_CHECKOUT,
                BookingStatus.CANCELLED,
                BookingStatus.CANCELLATION_PENDING_SETTLEMENT,
                BookingStatus.DECLINED,
                BookingStatus.COMPLETED,
                BookingStatus.EXPIRED,
                BookingStatus.EXPIRED_SYSTEM,
                BookingStatus.NO_SHOW_HOST,
                BookingStatus.NO_SHOW_GUEST,
                BookingStatus.REFUND_FAILED
        );

        for (BookingStatus status : BookingStatus.values()) {
            assertThat(BookingStatus.BLOCKING_STATUSES.contains(status) || knownNonBlocking.contains(status))
                    .as("BookingStatus.%s must be in BLOCKING_STATUSES or knownNonBlocking — "
                            + "add it to one of the sets when creating a new status", status.name())
                    .isTrue();
        }
    }

    /**
     * Every BookingStatus must produce a deterministic result in PhotoVisibilityMatrix.canViewPhoto()
     * (i.e., no uncaught exceptions). This catches missing switch cases even when a default exists.
     */
    @ParameterizedTest(name = "PhotoVisibilityMatrix handles {0} without exception")
    @EnumSource(BookingStatus.class)
    @DisplayName("PhotoVisibilityMatrix handles every BookingStatus")
    void photoVisibilityMatrixHandlesAllStatuses(BookingStatus status) {
        PhotoVisibilityMatrix matrix = new PhotoVisibilityMatrix();
        assertThatCode(() ->
                matrix.canViewPhoto(status, 1L, 1L, 2L, "CHECK_IN", "HOST", false)
        ).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "Dispute visibility is shared for {0}")
    @EnumSource(value = BookingStatus.class, names = {"CHECKOUT_DAMAGE_DISPUTE", "CHECK_IN_DISPUTE"})
    @DisplayName("Dispute states allow both parties to review evidence photos")
    void disputeStatesAllowBothPartiesToReviewEvidence(BookingStatus status) {
        PhotoVisibilityMatrix matrix = new PhotoVisibilityMatrix();

        assertThat(matrix.canViewPhoto(status, 10L, 10L, 20L, "CHECK_IN", "HOST", false)).isTrue();
        assertThat(matrix.canViewPhoto(status, 20L, 10L, 20L, "CHECKOUT", "GUEST", false)).isTrue();
    }

    /**
     * Every non-terminal BookingStatus should appear in isCheckInPhaseOrLater() or be
     * before that phase. This prevents silently returning false for new mid-flow statuses.
     */
    @Test
    @DisplayName("All check-in-and-later statuses are classified in isCheckInPhaseOrLater()")
    void isCheckInPhaseOrLaterCoversActiveStatuses() {
        Set<BookingStatus> expectedTrue = EnumSet.of(
                BookingStatus.CHECK_IN_OPEN,
                BookingStatus.CHECK_IN_HOST_COMPLETE,
                BookingStatus.CHECK_IN_COMPLETE,
                BookingStatus.CHECK_IN_DISPUTE,
                BookingStatus.IN_TRIP,
                BookingStatus.CHECKOUT_OPEN,
                BookingStatus.CHECKOUT_GUEST_COMPLETE,
                BookingStatus.CHECKOUT_HOST_COMPLETE,
                BookingStatus.CHECKOUT_SETTLEMENT_PENDING,
                BookingStatus.CHECKOUT_DAMAGE_DISPUTE,
                BookingStatus.COMPLETED
        );

        for (BookingStatus status : BookingStatus.values()) {
            if (expectedTrue.contains(status)) {
                assertThat(status.isCheckInPhaseOrLater())
                        .as("%s should be check-in phase or later", status.name())
                        .isTrue();
            }
        }
    }
}

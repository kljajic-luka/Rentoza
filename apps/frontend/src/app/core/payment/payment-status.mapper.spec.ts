import {
  getChargeStatusLabel,
  getChargeStatusIcon,
  getChargeStatusColor,
  getDepositStatusLabel,
  getDepositStatusIcon,
  getDepositStatusColor,
  getPayoutStatusLabel,
  getPayoutStatusIcon,
  getPayoutStatusColor,
  getGuestPaymentSummary,
  getGuestDepositSummary,
  isChargeStatusActionRequired,
  ChargeLifecycleStatus,
  DepositLifecycleStatus,
  PayoutLifecycleStatus,
} from './payment-status.mapper';

describe('Payment Status Mapper', () => {
  // ─── Charge Status ──────────────────────────────────────────────

  describe('getChargeStatusLabel', () => {
    const allChargeStatuses: ChargeLifecycleStatus[] = [
      'PENDING',
      'AUTHORIZED',
      'REAUTH_REQUIRED',
      'CAPTURED',
      'RELEASED',
      'REFUNDED',
      'CAPTURE_FAILED',
      'RELEASE_FAILED',
      'MANUAL_REVIEW',
    ];

    it('should return a label for all charge statuses', () => {
      for (const status of allChargeStatuses) {
        const label = getChargeStatusLabel(status);
        expect(label).toBeTruthy();
        expect(label).not.toBe(status); // Should be a human-readable label, not the raw status
      }
    });

    it('should return specific labels for known statuses', () => {
      expect(getChargeStatusLabel('AUTHORIZED')).toBe('Autorizovano');
      expect(getChargeStatusLabel('CAPTURED')).toBe('Naplaceno');
      expect(getChargeStatusLabel('REFUNDED')).toBe('Refundirano');
    });
  });

  describe('getChargeStatusIcon', () => {
    it('should return an icon for AUTHORIZED', () => {
      expect(getChargeStatusIcon('AUTHORIZED')).toBe('lock');
    });

    it('should return an icon for CAPTURED', () => {
      expect(getChargeStatusIcon('CAPTURED')).toBe('check_circle');
    });

    it('should return fallback for unknown status', () => {
      expect(getChargeStatusIcon('UNKNOWN' as ChargeLifecycleStatus)).toBe('help_outline');
    });
  });

  describe('getChargeStatusColor', () => {
    it('should return success for CAPTURED', () => {
      expect(getChargeStatusColor('CAPTURED')).toBe('success');
    });

    it('should return error for CAPTURE_FAILED', () => {
      expect(getChargeStatusColor('CAPTURE_FAILED')).toBe('error');
    });

    it('should return warn for REAUTH_REQUIRED', () => {
      expect(getChargeStatusColor('REAUTH_REQUIRED')).toBe('warn');
    });

    it('should return info for AUTHORIZED', () => {
      expect(getChargeStatusColor('AUTHORIZED')).toBe('info');
    });
  });

  // ─── Deposit Status ──────────────────────────────────────────────

  describe('getDepositStatusLabel', () => {
    const allDepositStatuses: DepositLifecycleStatus[] = [
      'PENDING',
      'AUTHORIZED',
      'RELEASED',
      'PARTIAL_CAPTURED',
      'CAPTURED',
      'EXPIRED',
      'MANUAL_REVIEW',
    ];

    it('should return a label for all deposit statuses', () => {
      for (const status of allDepositStatuses) {
        const label = getDepositStatusLabel(status);
        expect(label).toBeTruthy();
      }
    });

    it('should return specific label for AUTHORIZED', () => {
      expect(getDepositStatusLabel('AUTHORIZED')).toBe('Zadrzano na kartici');
    });

    it('should return specific label for RELEASED', () => {
      expect(getDepositStatusLabel('RELEASED')).toBe('Vraceno');
    });
  });

  describe('getDepositStatusIcon', () => {
    it('should return lock for AUTHORIZED', () => {
      expect(getDepositStatusIcon('AUTHORIZED')).toBe('lock');
    });

    it('should return check_circle for RELEASED', () => {
      expect(getDepositStatusIcon('RELEASED')).toBe('check_circle');
    });
  });

  // ─── Payout Status ──────────────────────────────────────────────

  describe('getPayoutStatusLabel', () => {
    const allPayoutStatuses: PayoutLifecycleStatus[] = [
      'PENDING',
      'ELIGIBLE',
      'PROCESSING',
      'COMPLETED',
      'ON_HOLD',
      'FAILED',
      'MANUAL_REVIEW',
    ];

    it('should return a label for all payout statuses', () => {
      for (const status of allPayoutStatuses) {
        const label = getPayoutStatusLabel(status);
        expect(label).toBeTruthy();
      }
    });

    it('should return specific label for COMPLETED', () => {
      expect(getPayoutStatusLabel('COMPLETED')).toBe('Isplaceno');
    });

    it('should return specific label for ELIGIBLE', () => {
      expect(getPayoutStatusLabel('ELIGIBLE')).toBe('Spremno za isplatu');
    });
  });

  describe('getPayoutStatusIcon', () => {
    it('should return check_circle for COMPLETED', () => {
      expect(getPayoutStatusIcon('COMPLETED')).toBe('check_circle');
    });

    it('should return sync for PROCESSING', () => {
      expect(getPayoutStatusIcon('PROCESSING')).toBe('sync');
    });
  });

  describe('getPayoutStatusColor', () => {
    it('should return success for COMPLETED', () => {
      expect(getPayoutStatusColor('COMPLETED')).toBe('success');
    });

    it('should return error for FAILED', () => {
      expect(getPayoutStatusColor('FAILED')).toBe('error');
    });

    it('should return warn for ON_HOLD', () => {
      expect(getPayoutStatusColor('ON_HOLD')).toBe('warn');
    });
  });

  // ─── Guest Summary ──────────────────────────────────────────────

  describe('getGuestPaymentSummary', () => {
    it('should return friendly message for AUTHORIZED', () => {
      const summary = getGuestPaymentSummary('AUTHORIZED');
      expect(summary).toContain('kartici');
    });

    it('should return friendly message for CAPTURED', () => {
      const summary = getGuestPaymentSummary('CAPTURED');
      expect(summary).toContain('zavrseno');
    });

    it('should return support message for MANUAL_REVIEW', () => {
      const summary = getGuestPaymentSummary('MANUAL_REVIEW');
      expect(summary).toContain('podrsku');
    });

    it('should return action message for REAUTH_REQUIRED', () => {
      const summary = getGuestPaymentSummary('REAUTH_REQUIRED');
      expect(summary).toContain('verifikacija');
    });
  });

  describe('getGuestDepositSummary', () => {
    it('should include amount in AUTHORIZED message', () => {
      const summary = getGuestDepositSummary('AUTHORIZED', 30000);
      expect(summary).toContain('30');
      expect(summary).toContain('kartici');
    });

    it('should show returned message for RELEASED', () => {
      const summary = getGuestDepositSummary('RELEASED', 30000);
      expect(summary).toContain('vraceno');
    });

    it('should handle undefined amount', () => {
      const summary = getGuestDepositSummary('AUTHORIZED');
      expect(summary).toContain('Depozit');
    });
  });

  // ─── Action Required ───────────────────────────────────────────

  describe('isChargeStatusActionRequired', () => {
    it('should return true for REAUTH_REQUIRED', () => {
      expect(isChargeStatusActionRequired('REAUTH_REQUIRED')).toBe(true);
    });

    it('should return true for CAPTURE_FAILED', () => {
      expect(isChargeStatusActionRequired('CAPTURE_FAILED')).toBe(true);
    });

    it('should return false for AUTHORIZED', () => {
      expect(isChargeStatusActionRequired('AUTHORIZED')).toBe(false);
    });

    it('should return false for CAPTURED', () => {
      expect(isChargeStatusActionRequired('CAPTURED')).toBe(false);
    });
  });
});

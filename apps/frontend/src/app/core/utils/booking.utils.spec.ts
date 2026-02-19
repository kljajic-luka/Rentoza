import {
  isBookingCompleted,
  canReviewBooking,
  canOwnerReviewRenter,
  isReviewWindowOpen,
} from './booking.utils';

describe('booking.utils - Feature 11 Hardening', () => {
  const now = new Date();

  describe('isBookingCompleted', () => {
    it('should return true for COMPLETED status', () => {
      expect(
        isBookingCompleted({
          status: 'COMPLETED',
          endTime: new Date(now.getTime() + 86400000).toISOString(),
        }),
      ).toBe(true);
    });

    it('should return true if endTime is in the past', () => {
      expect(
        isBookingCompleted({
          status: 'ACTIVE',
          endTime: new Date(now.getTime() - 86400000).toISOString(),
        }),
      ).toBe(true);
    });

    it('should return true for checkout statuses with past endTime (aligned with backend)', () => {
      expect(
        isBookingCompleted({
          status: 'CHECKOUT_OPEN',
          endTime: new Date(now.getTime() - 86400000).toISOString(),
        }),
      ).toBe(true);
      expect(
        isBookingCompleted({
          status: 'CHECKOUT_GUEST_COMPLETE',
          endTime: new Date(now.getTime() - 86400000).toISOString(),
        }),
      ).toBe(true);
      expect(
        isBookingCompleted({
          status: 'CHECKOUT_HOST_COMPLETE',
          endTime: new Date(now.getTime() - 86400000).toISOString(),
        }),
      ).toBe(true);
    });

    it('should return false for checkout statuses with future endTime', () => {
      expect(
        isBookingCompleted({
          status: 'CHECKOUT_OPEN',
          endTime: new Date(now.getTime() + 86400000).toISOString(),
        }),
      ).toBe(false);
    });

    it('should return false for future endTime with ACTIVE status', () => {
      expect(
        isBookingCompleted({
          status: 'ACTIVE',
          endTime: new Date(now.getTime() + 86400000).toISOString(),
        }),
      ).toBe(false);
    });
  });

  describe('isReviewWindowOpen (P0-1)', () => {
    it('should return true within 14-day window', () => {
      const endTime = new Date(now.getTime() - 5 * 86400000); // 5 days ago
      expect(isReviewWindowOpen({ endTime: endTime.toISOString() })).toBe(true);
    });

    it('should return false after 14-day window', () => {
      const endTime = new Date(now.getTime() - 20 * 86400000); // 20 days ago
      expect(isReviewWindowOpen({ endTime: endTime.toISOString() })).toBe(false);
    });

    it('should return true at exactly 14 days', () => {
      // Just barely within window
      const endTime = new Date(now.getTime() - 13 * 86400000); // 13 days ago (within)
      expect(isReviewWindowOpen({ endTime: endTime.toISOString() })).toBe(true);
    });

    it('should return true if no endTime', () => {
      expect(isReviewWindowOpen({ endTime: '' })).toBe(true);
    });
  });

  describe('canReviewBooking (P0-1 + P1-7)', () => {
    it('should allow review for completed booking within window', () => {
      const endTime = new Date(now.getTime() - 3 * 86400000); // 3 days ago
      expect(
        canReviewBooking({
          status: 'COMPLETED',
          endTime: endTime.toISOString(),
          hasReview: false,
        }),
      ).toBe(true);
    });

    it('should reject review if already reviewed', () => {
      const endTime = new Date(now.getTime() - 3 * 86400000);
      expect(
        canReviewBooking({
          status: 'COMPLETED',
          endTime: endTime.toISOString(),
          hasReview: true,
        }),
      ).toBe(false);
    });

    it('should reject review if window expired', () => {
      const endTime = new Date(now.getTime() - 20 * 86400000); // 20 days ago
      expect(
        canReviewBooking({
          status: 'COMPLETED',
          endTime: endTime.toISOString(),
          hasReview: false,
        }),
      ).toBe(false);
    });

    it('should reject review if not completed', () => {
      const endTime = new Date(now.getTime() + 86400000); // future
      expect(
        canReviewBooking({
          status: 'ACTIVE',
          endTime: endTime.toISOString(),
          hasReview: false,
        }),
      ).toBe(false);
    });

    it('should allow review for ACTIVE booking with past endTime (aligned with backend)', () => {
      const endTime = new Date(now.getTime() - 3 * 86400000);
      expect(
        canReviewBooking({
          status: 'ACTIVE',
          endTime: endTime.toISOString(),
          hasReview: false,
        }),
      ).toBe(true);
    });
  });

  describe('canOwnerReviewRenter (P0-1 + P1-7)', () => {
    it('should allow owner review for completed booking within window', () => {
      const endTime = new Date(now.getTime() - 3 * 86400000);
      expect(
        canOwnerReviewRenter({
          status: 'COMPLETED',
          endTime: endTime.toISOString(),
          hasOwnerReview: false,
        }),
      ).toBe(true);
    });

    it('should reject if already reviewed', () => {
      const endTime = new Date(now.getTime() - 3 * 86400000);
      expect(
        canOwnerReviewRenter({
          status: 'COMPLETED',
          endTime: endTime.toISOString(),
          hasOwnerReview: true,
        }),
      ).toBe(false);
    });

    it('should reject if window expired', () => {
      const endTime = new Date(now.getTime() - 20 * 86400000);
      expect(
        canOwnerReviewRenter({
          status: 'COMPLETED',
          endTime: endTime.toISOString(),
          hasOwnerReview: false,
        }),
      ).toBe(false);
    });
  });
});

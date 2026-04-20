import {
  generateDeterministicIdempotencyKey,
  generateDeterministicIdempotencyKeySync,
  BookingIntentParams,
} from './idempotency-key.util';

describe('Idempotency Key Utility', () => {
  const baseParams: BookingIntentParams = {
    userId: 'user-123',
    carId: '42',
    startTime: '2025-03-01T09:00:00',
    endTime: '2025-03-03T10:00:00',
  };

  describe('generateDeterministicIdempotencyKey', () => {
    it('should produce a key prefixed with bk_', async () => {
      const key = await generateDeterministicIdempotencyKey(baseParams);
      expect(key.startsWith('bk_')).toBe(true);
    });

    it('should produce a key of exactly 64 chars (backend max)', async () => {
      const key = await generateDeterministicIdempotencyKey(baseParams);
      // "bk_" (3) + 61 truncated hex chars = 64
      expect(key.length).toBe(64);
    });

    it('should never exceed 64 chars for any input', async () => {
      const longParams: BookingIntentParams = {
        userId: 'very-long-user-id-that-could-be-a-uuid-a1b2c3d4e5f6',
        carId: '999999999',
        startTime: '2025-12-31T23:59:59.999',
        endTime: '2026-12-31T23:59:59.999',
      };
      const key = await generateDeterministicIdempotencyKey(longParams);
      expect(key.length).toBeLessThanOrEqual(64);
    });

    it('should produce the same key for the same parameters', async () => {
      const key1 = await generateDeterministicIdempotencyKey(baseParams);
      const key2 = await generateDeterministicIdempotencyKey(baseParams);
      expect(key1).toBe(key2);
    });

    it('should produce different keys for different userIds', async () => {
      const key1 = await generateDeterministicIdempotencyKey(baseParams);
      const key2 = await generateDeterministicIdempotencyKey({
        ...baseParams,
        userId: 'user-456',
      });
      expect(key1).not.toBe(key2);
    });

    it('should produce different keys for different carIds', async () => {
      const key1 = await generateDeterministicIdempotencyKey(baseParams);
      const key2 = await generateDeterministicIdempotencyKey({
        ...baseParams,
        carId: '99',
      });
      expect(key1).not.toBe(key2);
    });

    it('should produce different keys for different startTimes', async () => {
      const key1 = await generateDeterministicIdempotencyKey(baseParams);
      const key2 = await generateDeterministicIdempotencyKey({
        ...baseParams,
        startTime: '2025-03-01T10:00:00',
      });
      expect(key1).not.toBe(key2);
    });

    it('should produce different keys for different endTimes', async () => {
      const key1 = await generateDeterministicIdempotencyKey(baseParams);
      const key2 = await generateDeterministicIdempotencyKey({
        ...baseParams,
        endTime: '2025-03-04T10:00:00',
      });
      expect(key1).not.toBe(key2);
    });

    it('should handle numeric userId and carId', async () => {
      const key = await generateDeterministicIdempotencyKey({
        userId: 123,
        carId: 42,
        startTime: '2025-03-01T09:00:00',
        endTime: '2025-03-03T10:00:00',
      });
      expect(key.startsWith('bk_')).toBe(true);
      expect(key.length).toBe(64);
    });
  });

  describe('generateDeterministicIdempotencyKeySync', () => {
    it('should produce a key prefixed with bk_', () => {
      const key = generateDeterministicIdempotencyKeySync(baseParams);
      expect(key.startsWith('bk_')).toBe(true);
    });

    it('should produce a key not exceeding 64 chars', () => {
      const key = generateDeterministicIdempotencyKeySync(baseParams);
      expect(key.length).toBeLessThanOrEqual(64);
    });

    it('should produce the same key for the same parameters', () => {
      const key1 = generateDeterministicIdempotencyKeySync(baseParams);
      const key2 = generateDeterministicIdempotencyKeySync(baseParams);
      expect(key1).toBe(key2);
    });

    it('should produce different keys for different parameters', () => {
      const key1 = generateDeterministicIdempotencyKeySync(baseParams);
      const key2 = generateDeterministicIdempotencyKeySync({
        ...baseParams,
        userId: 'user-different',
      });
      expect(key1).not.toBe(key2);
    });
  });
});

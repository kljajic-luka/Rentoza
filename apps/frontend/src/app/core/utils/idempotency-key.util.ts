/**
 * Deterministic idempotency key generator.
 *
 * Produces a stable key from booking intent parameters so that:
 * - The same booking intent always produces the same key
 * - Retries (including after browser refresh) reuse the same key
 * - Different booking intents produce different keys
 *
 * Uses SHA-256 hashing for consistent, collision-resistant output.
 *
 * MAX LENGTH: 64 chars (3-char prefix "bk_" + 61-char truncated hex hash).
 * Matches backend PaymentIdempotencyKey column limit of 64.
 */

/**
 * Parameters that uniquely identify a booking intent.
 */
export interface BookingIntentParams {
  userId: string | number;
  carId: string | number;
  startTime: string;
  endTime: string;
}

/** Maximum total key length, matching backend column constraint. */
const MAX_KEY_LENGTH = 64;
/** Prefix for booking idempotency keys. */
const KEY_PREFIX = 'bk_';
/** Max hex chars = MAX_KEY_LENGTH - prefix length. */
const MAX_HASH_CHARS = MAX_KEY_LENGTH - KEY_PREFIX.length; // 61

/**
 * Generate a deterministic idempotency key from booking intent parameters.
 *
 * The key is a truncated SHA-256 hex hash of the concatenated parameters,
 * prefixed with "bk_" for easy identification in logs.
 *
 * Total length: 3 ("bk_") + 61 (truncated hex) = 64 chars max.
 *
 * @param params Booking intent parameters (userId, carId, startTime, endTime)
 * @returns Deterministic key string in format "bk_{hex-hash}" (<=64 chars)
 */
export async function generateDeterministicIdempotencyKey(
  params: BookingIntentParams,
): Promise<string> {
  const input = `${params.userId}|${params.carId}|${params.startTime}|${params.endTime}`;
  const hash = await sha256Hex(input);
  return `${KEY_PREFIX}${hash.slice(0, MAX_HASH_CHARS)}`;
}

/**
 * Synchronous fallback for deterministic idempotency key.
 * Uses a simple FNV-1a hash when SubtleCrypto is not available.
 */
export function generateDeterministicIdempotencyKeySync(params: BookingIntentParams): string {
  const input = `${params.userId}|${params.carId}|${params.startTime}|${params.endTime}`;
  const hash = fnv1aHash(input);
  return `${KEY_PREFIX}${hash.slice(0, MAX_HASH_CHARS)}`;
}

/**
 * SHA-256 hex hash using SubtleCrypto API.
 */
async function sha256Hex(input: string): Promise<string> {
  const encoder = new TextEncoder();
  const data = encoder.encode(input);
  const hashBuffer = await crypto.subtle.digest('SHA-256', data);
  const hashArray = Array.from(new Uint8Array(hashBuffer));
  return hashArray.map((b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * FNV-1a hash for synchronous fallback.
 * Produces a repeating hex string truncated to MAX_HASH_CHARS.
 */
function fnv1aHash(input: string): string {
  let hash = 0x811c9dc5;
  for (let i = 0; i < input.length; i++) {
    hash ^= input.charCodeAt(i);
    hash = (hash * 0x01000193) >>> 0;
  }
  const hex = hash.toString(16).padStart(8, '0');
  // Repeat to fill enough chars for truncation
  return (hex + hex + hex + hex + hex + hex + hex + hex).slice(0, MAX_HASH_CHARS);
}

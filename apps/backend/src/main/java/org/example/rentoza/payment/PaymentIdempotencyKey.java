package org.example.rentoza.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Deterministic idempotency key generator for all payment provider calls.
 *
 * <h2>Key Design</h2>
 * <p>Keys are composed of semantic segments so they are:
 * <ul>
 *   <li><b>Deterministic</b> — same operation+booking always produces same key</li>
 *   <li><b>Scoped</b> — different operations on same booking produce different keys</li>
 *   <li><b>Attempt-aware</b> — retry attempts append a suffix to avoid replaying
 *       a "capture-attempt-1" result when issuing "capture-attempt-2"</li>
 * </ul>
 *
 * <h2>Key Format</h2>
 * <pre>
 *   pay_{operation}_{bookingId}_{qualifier}[_retry{n}]
 *   e.g.:
 *     pay_auth_123_booking
 *     pay_capture_123_booking_retry1
 *     pay_refund_123_cancel
 *     pay_deposit_auth_123_checkin
 *     pay_payout_123_host_456
 * </pre>
 *
 * <p>The key length is capped at 64 characters (matches the DB column length).
 */
public final class PaymentIdempotencyKey {

    private static final int MAX_LENGTH = 64;

    private PaymentIdempotencyKey() {}

    // ── Booking charge ──────────────────────────────────────────────────────

    public static String forAuthorize(Long bookingId) {
        return build("auth", bookingId, "booking", 0);
    }

    /**
     * Reauthorization key — scoped per attempt so each reauth gets a distinct provider
     * slot, preventing the original {@link #forAuthorize} entry from masking a new auth.
     */
    public static String forReauth(Long bookingId, int attempt) {
        return build("reauth", bookingId, "booking", attempt);
    }

    public static String forCapture(Long bookingId, int attempt) {
        return build("capture", bookingId, "booking", attempt);
    }

    public static String forRelease(Long bookingId, int attempt) {
        return build("release", bookingId, "booking", attempt);
    }

    /** Single-attempt release — delegates to attempt 1. */
    public static String forRelease(Long bookingId) {
        return forRelease(bookingId, 1);
    }

    // ── Security deposit ────────────────────────────────────────────────────

    public static String forDepositAuthorize(Long bookingId) {
        return build("dep_auth", bookingId, "checkin", 0);
    }

    public static String forDepositCapture(Long bookingId, int attempt) {
        return build("dep_capture", bookingId, "checkout", attempt);
    }

    public static String forDepositRelease(Long bookingId, int attempt) {
        return build("dep_release", bookingId, "checkout", attempt);
    }

    /** Single-attempt deposit release — delegates to attempt 1. */
    public static String forDepositRelease(Long bookingId) {
        return forDepositRelease(bookingId, 1);
    }

    // ── Refund ──────────────────────────────────────────────────────────────

    /**
     * @param qualifier short noun describing the refund source
     *                  (e.g., "cancel", "dispute", "noshow")
     */
    public static String forRefund(Long bookingId, String qualifier, int attempt) {
        return build("refund", bookingId, qualifier, attempt);
    }

    // ── Payout ──────────────────────────────────────────────────────────────

    public static String forPayout(Long bookingId, Long hostId, int attempt) {
        return build("payout_" + hostId, bookingId, "host", attempt);
    }

    // ── Saga / Checkout ──────────────────────────────────────────────────────

    /**
     * Deterministic key for the checkout-saga remainder charge (after deposit capture).
     * Ensures retries reuse the same provider idempotency slot.
     */
    public static String forCheckoutRemainder(Long bookingId) {
        return truncate("pay_chkout_rem_" + bookingId);
    }

    /**
     * Deterministic key for a checkout-saga compensation refund (rolling back a capture).
     * Prevents creating duplicate refunds when compensate() is called multiple times.
     */
    public static String forSagaCompensation(Long bookingId) {
        return truncate("pay_saga_comp_" + bookingId);
    }

    // ── Ad-hoc charges ──────────────────────────────────────────────────────

    public static String forDamageCharge(Long claimId, int attempt) {
        return build("dmg_charge", claimId, "claim", attempt);
    }

    /**
     * Scoped to both booking and claim — preferred for service code.
     * Distinct from {@link #forDamageCharge(Long, int)} which is claim-only.
     */
    public static String forDamageCharge(Long bookingId, Long claimId) {
        return truncate("pay_dmg_" + bookingId + "_c" + claimId);
    }

    public static String forLateFee(Long bookingId, int attempt) {
        return build("latefee", bookingId, "checkout", attempt);
    }

    /** Single-attempt late fee — delegates to attempt 1. */
    public static String forLateFee(Long bookingId) {
        return forLateFee(bookingId, 1);
    }

    public static String forExtension(Long extensionId, int attempt) {
        return build("ext", extensionId, "extension", attempt);
    }

    /**
     * Scoped to both booking and extension — preferred for service code.
     * Distinct from {@link #forExtension(Long, int)} which is extension-only.
     */
    public static String forExtension(Long bookingId, Long extensionId) {
        return truncate("pay_ext_" + bookingId + "_e" + extensionId);
    }

    // ── Fallback ─────────────────────────────────────────────────────────────

    /**
     * Non-deterministic random key — for legacy callers only.
     *
     * <p><b>DEPRECATED:</b> Random keys violate idempotency guarantees. Every call
     * to this method produces a new key, meaning retries will create duplicate
     * provider-side operations instead of being deduplicated.
     *
     * @deprecated Use a deterministic {@code for*()} method instead. This method
     *             will be removed in a future release.
     */
    @Deprecated(forRemoval = true)
    public static String random() {
        return "pay_rnd_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    // ── Private builder ──────────────────────────────────────────────────────

    private static String build(String op, Long entityId, String qualifier, int attempt) {
        String base = "pay_" + op + "_" + entityId + "_" + qualifier;
        String key  = attempt > 0 ? base + "_r" + attempt : base;
        return truncate(key);
    }

    /**
     * H-7 FIX: Deterministic truncation using SHA-256 when key exceeds MAX_LENGTH.
     *
     * <p>Previous implementation used naive {@code substring(0, MAX_LENGTH)} which
     * could cause collisions when two distinct keys shared a common prefix longer
     * than 64 characters (e.g., payout keys with long host IDs and high attempt numbers).
     *
     * <p>New strategy: keeps a human-readable prefix (first 20 chars) + "_h_" + 40-char
     * SHA-256 hex digest = 63 total characters. The full original key is hashed, so
     * two keys differing only in their suffix still produce distinct results.
     */
    static String truncate(String key) {
        if (key.length() <= MAX_LENGTH) {
            return key;
        }
        // Deterministic hash: prefix ensures human debuggability; hash ensures uniqueness
        String hash = sha256Hex(key);
        // 20 prefix + "_h_" (3) + 40 hex chars = 63 chars (fits in 64)
        return key.substring(0, 20) + "_h_" + hash.substring(0, 40);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by JCA spec — this cannot happen on a compliant JVM
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}

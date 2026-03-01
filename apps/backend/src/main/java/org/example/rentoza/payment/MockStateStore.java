package org.example.rentoza.payment;

import org.example.rentoza.payment.PaymentProvider.ProviderResult;

import java.math.BigDecimal;
import java.util.function.Supplier;

/**
 * Pluggable state store for {@link MockPaymentProvider}.
 *
 * <p>Abstracts authorization/transaction/idempotency state so it can be backed by
 * in-memory maps (unit tests, single-instance dev) or Redis (staging replicas).
 *
 * @see InMemoryMockStateStore
 * @see RedisMockStateStore
 */
interface MockStateStore {

    // ── Authorization lifecycle ──

    void saveAuthorization(String authId, MockAuthorization auth);

    MockAuthorization loadAuthorization(String authId);

    // ── Captured transactions ──

    void saveCapturedTransaction(String txnId, BigDecimal amount);

    BigDecimal loadCapturedAmount(String txnId);

    // ── Refund tracking ──

    BigDecimal getRefundedAmount(String txnId);

    void addRefundedAmount(String txnId, BigDecimal amount);

    // ── Idempotency ──

    /**
     * Atomic compute-if-absent for idempotency.
     * If a result for {@code key} already exists, return it without invoking {@code computation}.
     * Otherwise invoke {@code computation}, store, and return the result.
     */
    ProviderResult computeIdempotent(String key, Supplier<ProviderResult> computation);

    // ── SCA sessions ──

    void saveScaSession(String token, MockScaSession session);

    MockScaSession loadScaSession(String token);

    // ── Locking ──

    /**
     * Execute {@code action} under a mutual-exclusion lock scoped to {@code lockKey}.
     * For in-memory: {@code synchronized} on a per-key object.
     * For Redis: SETNX-based distributed lock with TTL.
     */
    <T> T withLock(String lockKey, Supplier<T> action);
}

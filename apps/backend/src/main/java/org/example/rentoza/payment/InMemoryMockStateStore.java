package org.example.rentoza.payment;

import org.example.rentoza.payment.PaymentProvider.ProviderResult;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * In-memory state store backed by {@link ConcurrentHashMap}.
 *
 * <p>Preserves the original {@link MockPaymentProvider} behavior for unit tests
 * that construct the provider directly via {@code new MockPaymentProvider()} without Spring.
 */
class InMemoryMockStateStore implements MockStateStore {

    private final Map<String, MockAuthorization> authorizations = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> capturedTransactions = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> refundedAmounts = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, ProviderResult> idempotencyStore = new ConcurrentHashMap<>();
    private final Map<String, MockScaSession> scaSessions = new ConcurrentHashMap<>();

    @Override
    public void saveAuthorization(String authId, MockAuthorization auth) {
        authorizations.put(authId, auth);
    }

    @Override
    public MockAuthorization loadAuthorization(String authId) {
        return authorizations.get(authId);
    }

    @Override
    public void saveCapturedTransaction(String txnId, BigDecimal amount) {
        capturedTransactions.put(txnId, amount);
    }

    @Override
    public BigDecimal loadCapturedAmount(String txnId) {
        return capturedTransactions.get(txnId);
    }

    @Override
    public BigDecimal getRefundedAmount(String txnId) {
        return refundedAmounts.getOrDefault(txnId, BigDecimal.ZERO);
    }

    @Override
    public void addRefundedAmount(String txnId, BigDecimal amount) {
        refundedAmounts.merge(txnId, amount, BigDecimal::add);
    }

    @Override
    public ProviderResult computeIdempotent(String key, Supplier<ProviderResult> computation) {
        return idempotencyStore.computeIfAbsent(key, k -> computation.get());
    }

    @Override
    public void saveScaSession(String token, MockScaSession session) {
        scaSessions.put(token, session);
    }

    @Override
    public MockScaSession loadScaSession(String token) {
        return scaSessions.get(token);
    }

    @Override
    public <T> T withLock(String lockKey, Supplier<T> action) {
        Object lock = locks.computeIfAbsent(lockKey, k -> new Object());
        synchronized (lock) {
            return action.get();
        }
    }
}

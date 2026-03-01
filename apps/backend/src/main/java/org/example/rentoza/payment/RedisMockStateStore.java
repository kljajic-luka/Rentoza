package org.example.rentoza.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.payment.PaymentProvider.ProviderResult;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Redis-backed state store for {@link MockPaymentProvider}.
 *
 * <p>Allows staging replicas to share mock payment state. Uses
 * {@link StringRedisTemplate} for persistence and SETNX for distributed locking.
 *
 * <h2>Redis Key Scheme</h2>
 * <pre>
 *   {namespace}:auth:{authId}       → JSON MockAuthorization
 *   {namespace}:captured:{txnId}    → amount as plain string
 *   {namespace}:refunded:{txnId}    → cumulative refunded amount
 *   {namespace}:idem:{key}          → JSON ProviderResult
 *   {namespace}:lock:{key}          → lock owner UUID (SETNX with TTL)
 * </pre>
 */
@Slf4j
class RedisMockStateStore implements MockStateStore {

    /** Lua script for atomic lock release: only deletes if we still own the lock. */
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT;
    static {
        RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>();
        RELEASE_LOCK_SCRIPT.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "return redis.call('del', KEYS[1]) else return 0 end");
        RELEASE_LOCK_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String ns;
    private final Duration ttl;

    RedisMockStateStore(StringRedisTemplate redis, ObjectMapper objectMapper,
                        String namespace, Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ns = namespace;
        this.ttl = ttl;
    }

    // ── Authorization ──────────────────────────────────────────────────────────

    @Override
    public void saveAuthorization(String authId, MockAuthorization auth) {
        redis.opsForValue().set(key("auth:" + authId), serialize(auth), ttl);
    }

    @Override
    public MockAuthorization loadAuthorization(String authId) {
        String json = redis.opsForValue().get(key("auth:" + authId));
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, MockAuthorization.class);
        } catch (JsonProcessingException e) {
            log.error("[Mock-Redis] Failed to deserialize authorization {}", authId, e);
            return null;
        }
    }

    // ── Captured Transactions ──────────────────────────────────────────────────

    @Override
    public void saveCapturedTransaction(String txnId, BigDecimal amount) {
        redis.opsForValue().set(key("captured:" + txnId), amount.toPlainString(), ttl);
    }

    @Override
    public BigDecimal loadCapturedAmount(String txnId) {
        String val = redis.opsForValue().get(key("captured:" + txnId));
        return val != null ? new BigDecimal(val) : null;
    }

    // ── Refund Tracking ────────────────────────────────────────────────────────

    @Override
    public BigDecimal getRefundedAmount(String txnId) {
        String val = redis.opsForValue().get(key("refunded:" + txnId));
        return val != null ? new BigDecimal(val) : BigDecimal.ZERO;
    }

    @Override
    public void addRefundedAmount(String txnId, BigDecimal amount) {
        // Called under withLock — no concurrent mutation risk.
        BigDecimal current = getRefundedAmount(txnId);
        redis.opsForValue().set(key("refunded:" + txnId),
                current.add(amount).toPlainString(), ttl);
    }

    // ── Idempotency ────────────────────────────────────────────────────────────

    @Override
    public ProviderResult computeIdempotent(String idempotencyKey, Supplier<ProviderResult> computation) {
        String redisKey = key("idem:" + idempotencyKey);

        // Fast path: check without lock
        String existing = redis.opsForValue().get(redisKey);
        if (existing != null) {
            return deserializeResult(existing);
        }

        // Slow path: lock, double-check, compute, store
        return withLock("idem_lock:" + idempotencyKey, () -> {
            String doubleCheck = redis.opsForValue().get(redisKey);
            if (doubleCheck != null) {
                return deserializeResult(doubleCheck);
            }
            ProviderResult result = computation.get();
            redis.opsForValue().set(redisKey, serialize(result), ttl);
            return result;
        });
    }

    // ── Distributed Lock ───────────────────────────────────────────────────────

    @Override
    public <T> T withLock(String lockKey, Supplier<T> action) {
        String redisKey = key("lock:" + lockKey);
        String lockValue = UUID.randomUUID().toString();
        Duration lockTtl = Duration.ofSeconds(30);

        for (int i = 0; i < 200; i++) {  // 200 x 50 ms = 10 s max wait
            Boolean acquired = redis.opsForValue().setIfAbsent(redisKey, lockValue, lockTtl);
            if (Boolean.TRUE.equals(acquired)) {
                try {
                    return action.get();
                } finally {
                    redis.execute(RELEASE_LOCK_SCRIPT, List.of(redisKey), lockValue);
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Lock acquisition interrupted for key: " + lockKey, e);
            }
        }
        throw new RuntimeException("Failed to acquire mock-payment lock: " + lockKey + " after 10s");
    }

    // ── Serialization helpers ──────────────────────────────────────────────────

    private String key(String suffix) {
        return ns + ":" + suffix;
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Mock payment state serialization failed", e);
        }
    }

    private ProviderResult deserializeResult(String json) {
        try {
            return objectMapper.readValue(json, ProviderResult.class);
        } catch (JsonProcessingException e) {
            log.error("[Mock-Redis] Failed to deserialize ProviderResult", e);
            throw new RuntimeException("Mock payment state deserialization failed", e);
        }
    }
}

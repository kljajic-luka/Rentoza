# Admin Dashboard Audit Remediation — Implementation Prompt

> **Purpose:** Hand this prompt to an AI coding agent to remediate every finding from
> the production-readiness audit of the Rentoza admin dashboard module.
>
> **Target standard:** Peer-to-peer rental marketplace at the scale of Turo / Airbnb.

---

## Role

You are a Senior Staff Engineer implementing production-critical fixes for **Rentoza**,
a peer-to-peer car rental marketplace (Spring Boot + Angular). Every fix involves real
user money, trust & safety workflows, or regulatory compliance. Treat each change as if
a wrong implementation causes a 3 AM pager alert with financial consequences.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot 3, Java 17+, JPA/Hibernate, PostgreSQL |
| Auth | Supabase JWT (ES256), stateless sessions, HttpOnly cookies |
| Payments | Stripe-like provider via `BookingPaymentService` |
| Frontend | Angular 17+ (standalone components), Angular Material |
| Infra | Google Cloud Run, Redis (rate limiting) |

---

## Conventions You MUST Follow

These are verified patterns already in the codebase. Do not deviate.

### Locking

- **Pessimistic locks** use `@Lock(LockModeType.PESSIMISTIC_WRITE)` with
  `@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")` on
  repository query methods.
- **Optimistic locks** use `@Version private Long version;` on entities.
  Both `Booking` and `DamageClaim` already have `@Version`.
- Existing example: `BookingRepository.findByIdWithLock()`.

### Transactions

- Class-level `@Transactional` for write-heavy services.
- Method-level `@Transactional(readOnly = true)` for read-only methods.
- Default propagation `REQUIRED`, isolation `READ_COMMITTED`.

### Audit Trail

- All admin mutations call `auditService.logAction(admin, action, resourceType, resourceId, beforeState, afterState, reason)`.
- Before/after state captured via `auditService.toJson(dto)`.
- `AdminAuditLog` is append-only — `@PreUpdate`/`@PreRemove` throw.

### Metrics

- Counters: `meterRegistry.counter("name", "tag", "value").increment()`
- Timers: `Timer.Sample timer = Timer.start(meterRegistry)` → `timer.stop(Timer.builder(...).register(meterRegistry))`

### MDC Logging

- `MDC.put("key", value)` at method entry, `MDC.clear()` in `finally`.

### Tests

- JUnit 5 with `@ExtendWith(MockitoExtension.class)`.
- `@Nested` inner classes grouped by method under test.
- `@DisplayName` on every test.
- `ArgumentCaptor` for verifying audit calls.
- `SimpleMeterRegistry` for metrics assertions.
- AssertJ: `assertThat(...).isEqualTo(...)`, `assertThatThrownBy(...)`.
- See `AdminCarServiceTest.java` for the canonical example.

### DTOs

- `@Data @Builder @NoArgsConstructor @AllArgsConstructor` on all DTOs.
- Validation: `@NotBlank`, `@NotNull`, `@Size`, `@Positive`, `@PositiveOrZero` from `jakarta.validation`.
- Static factory: `fromEntity(Entity e)` on DTO classes for mapping.

### Repository Queries

- Custom queries use `@Query` with JPQL and `@Param`.
- `@EntityGraph(attributePaths = {...})` for eager loading.
- `JpaSpecificationExecutor<T>` for dynamic filtering.

---

## Implementation Plan

Work through these groups **in order**. Each group contains fixes at the same
priority level. Within a group, fixes are independent — parallelise if possible.
Commit after each group passes its tests.

---

## GROUP 1 — CRITICAL (Must complete first)

These fixes prevent data loss, financial loss, or authentication bypass.

---

### C-1: Remove Unverified JWT Claims Extraction

**Files to modify:**
- `apps/backend/src/main/java/org/example/rentoza/security/supabase/SupabaseJwtUtil.java`

**What is wrong:**
`extractAllClaims()` (around line 321) falls back to `parseClaimsWithoutVerification()`
when the public key is missing OR when any generic `Exception` is caught. The
unverified claims (including `sub` UUID) flow into `SupabaseJwtAuthFilter` and are
used for user authentication. An attacker can forge a JWT with an unknown `kid`,
trigger the fallback, and impersonate any user — including admins.

**Exact changes:**

1. In `extractAllClaims()`, replace the fallback paths:

```java
// BEFORE (line ~333-334):
if (publicKey == null) {
    log.warn("No public key found for claims extraction");
    return parseClaimsWithoutVerification(token);
}

// AFTER:
if (publicKey == null) {
    log.error("SECURITY: No public key found for kid={}. Rejecting token.", kid);
    return null;
}
```

2. In the same method, replace the generic catch block:

```java
// BEFORE (line ~345-348):
} catch (Exception e) {
    log.warn("Unable to parse Supabase JWT: {}", e.getMessage());
    return parseClaimsWithoutVerification(token);
}

// AFTER:
} catch (Exception e) {
    log.warn("Unable to parse Supabase JWT: {}", e.getMessage());
    return null;
}
```

3. Deprecate (or delete) the `parseClaimsWithoutVerification()` method entirely.
   If other code paths call it, make them return `null` on failure instead.

4. Verify in `SupabaseJwtAuthFilter.doFilterInternal()` that when `extractAllClaims`
   returns `null`, the filter calls `filterChain.doFilter(request, response); return;`
   (i.e., the request proceeds unauthenticated and downstream security rules reject it).

**Test:**
- Unit test: craft a JWT with unknown `kid` → `extractAllClaims()` returns `null`.
- Unit test: craft a JWT with valid `kid` but tampered payload → returns `null`.
- Integration test: forged JWT with `sub` pointing to admin UUID → request to
  `/api/admin/dashboard/kpis` returns 401.

---

### C-2: Stop Returning Claims from Expired Tokens

**Files to modify:**
- `apps/backend/src/main/java/org/example/rentoza/security/supabase/SupabaseJwtUtil.java`

**What is wrong:**
The `ExpiredJwtException` catch block (line ~342) returns `e.getClaims()`, allowing
expired tokens to produce valid claims for user identification.

**Exact changes:**

```java
// BEFORE (line ~342-344):
} catch (ExpiredJwtException e) {
    return e.getClaims();
}

// AFTER:
} catch (ExpiredJwtException e) {
    log.debug("Supabase JWT expired at {}", e.getClaims().getExpiration());
    return null;
}
```

**Test:**
- Unit test: expired JWT → `extractAllClaims()` returns `null`.
- Ensure the token denylist check still works: a non-expired but denylisted token
  should also be rejected (this is handled separately in the filter, just verify it
  still works).

---

### C-3: Prevent Double-Payout via Pessimistic Locking

**Files to modify:**
- `apps/backend/src/main/java/org/example/rentoza/booking/BookingRepository.java`
- `apps/backend/src/main/java/org/example/rentoza/admin/service/AdminFinancialService.java`

**What is wrong:**
`processBatchPayouts()` reads `booking.getPaymentReference()` and then calls
`processHostPayout()` without a lock. Two concurrent batch requests with the same
`bookingId` can both pass the null-check and both execute payouts.

**Exact changes:**

1. Add a new locked query in `BookingRepository` (follow the pattern of
   `findByIdWithLock`):

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints({
    @QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")
})
@Query("SELECT b FROM Booking b " +
       "JOIN FETCH b.car c " +
       "LEFT JOIN FETCH c.owner " +
       "WHERE b.id = :id")
Optional<Booking> findByIdWithLockForPayout(@Param("id") Long id);
```

2. In `AdminFinancialService.processBatchPayouts()`, replace:

```java
// BEFORE (line ~170):
Booking booking = bookingRepo.findById(bookingId)
    .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));

// AFTER:
Booking booking = bookingRepo.findByIdWithLockForPayout(bookingId)
    .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
```

3. **Important architectural note:** `processHostPayout()` does NOT set
   `paymentReference` immediately — it creates a ledger entry. The
   `paymentReference` is only set when the payment provider confirms. This means
   the `booking.getPaymentReference() != null` check (line 183) does NOT protect
   against double-queueing. Add a check for existing ledger entries:

   If a `PayoutLedger` entity/repo exists, add:
   ```java
   // After the paymentReference check, add:
   if (payoutLedgerRepo.existsByBookingId(bookingId)) {
       failures.add(BatchPayoutResult.PayoutFailure.builder()
           .bookingId(bookingId)
           .reason("Payout already queued in ledger")
           .errorCode("DUPLICATE_PAYOUT")
           .build());
       continue;
   }
   ```

   If no ledger repo is available, add a `payoutQueuedAt` Instant field to the
   Booking entity and set it immediately before calling `processHostPayout()`. Check
   it alongside `paymentReference`.

4. Apply the same locked query in `retryPayout()`:

```java
// BEFORE (line ~242):
Booking booking = bookingRepo.findById(bookingId)
    .orElseThrow(...);

// AFTER:
Booking booking = bookingRepo.findByIdWithLockForPayout(bookingId)
    .orElseThrow(...);
```

**Test:**
- Unit test: mock `findByIdWithLockForPayout` returning a booking with
  `paymentReference != null` → verify failure result with `DUPLICATE_PAYOUT`.
- Integration test (if feasible): two concurrent `processBatchPayouts` calls with
  overlapping booking IDs → only one succeeds per booking.

---

### C-4: Add Pessimistic Locking to Checkout Dispute Resolution

**Files to modify:**
- `apps/backend/src/main/java/org/example/rentoza/booking/dispute/DamageClaimRepository.java`
- `apps/backend/src/main/java/org/example/rentoza/admin/service/AdminDisputeService.java`

**What is wrong:**
`resolveCheckoutDispute()` fetches `DamageClaim` without a lock. Two admins can
resolve the same dispute simultaneously with contradictory decisions (one approves,
one rejects), causing deposit to be both captured and released.

**Exact changes:**

1. Add a locked finder to `DamageClaimRepository`:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints({
    @QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")
})
@Query("SELECT c FROM DamageClaim c " +
       "JOIN FETCH c.booking b " +
       "LEFT JOIN FETCH b.car " +
       "WHERE c.id = :id")
Optional<DamageClaim> findByIdWithLock(@Param("id") Long id);
```

2. In `AdminDisputeService.resolveCheckoutDispute()`, replace:

```java
// BEFORE (line ~551):
DamageClaim claim = damageClaimRepo.findById(damageClaimId)
    .orElseThrow(...);

// AFTER:
DamageClaim claim = damageClaimRepo.findByIdWithLock(damageClaimId)
    .orElseThrow(() -> new ResourceNotFoundException("Damage claim not found: " + damageClaimId));
```

3. Apply the same pattern to `resolveCheckInDispute()` and `resolveDispute()` — any
   method that mutates a `DamageClaim`.

4. Ensure the method is `@Transactional` (it should inherit from class-level, but
   verify). The pessimistic lock is held for the duration of the transaction.

**Test:**
- Unit test: verify `findByIdWithLock` is called (not `findById`).
- Unit test: resolved dispute → second resolve attempt throws
  `IllegalStateException("Dispute cannot be resolved in current state")`.

---

### C-5: Bound Approved Amount by Claim and Deposit

**Files to modify:**
- `apps/backend/src/main/java/org/example/rentoza/admin/service/AdminDisputeService.java`

**What is wrong:**
The `PARTIAL` and `APPROVE` branches accept any `approvedAmountRsd` without checking
it against the original claim or the security deposit. An admin (or compromised admin
account) can approve an amount exceeding both.

**Exact changes:**

In `resolveCheckoutDispute()`, after the switch statement opens, add validation at
the top of both `APPROVE` and `PARTIAL` cases:

```java
case APPROVE -> {
    approvedAmount = request.getApprovedAmountRsd() != null
        ? request.getApprovedAmountRsd()
        : originalClaimAmount;

    // NEW: Bound by claim amount
    if (approvedAmount.compareTo(originalClaimAmount) > 0) {
        throw new IllegalArgumentException(
            "Approved amount (" + approvedAmount + " RSD) cannot exceed claimed amount ("
            + originalClaimAmount + " RSD)");
    }

    // NEW: Bound by deposit amount
    BigDecimal depositAmount = booking.getSecurityDeposit() != null
        ? booking.getSecurityDeposit() : BigDecimal.ZERO;
    if (approvedAmount.compareTo(depositAmount) > 0) {
        throw new IllegalArgumentException(
            "Approved amount (" + approvedAmount + " RSD) cannot exceed security deposit ("
            + depositAmount + " RSD)");
    }

    claim.setApprovedAmount(approvedAmount);
    // ... rest unchanged
}
```

Apply the same two checks in the `PARTIAL` case (after the existing `> 0` check).

**Test:**
- `approvedAmount > claimedAmount` → `IllegalArgumentException`.
- `approvedAmount > securityDeposit` → `IllegalArgumentException`.
- `approvedAmount == claimedAmount` and `approvedAmount == securityDeposit` → succeeds (boundary).
- `approvedAmount = 0` for PARTIAL → existing validation catches it.

---

### C-6: Add Recovery Path for Failed Refunds on Check-In Cancel

**Files to modify:**
- `apps/backend/src/main/java/org/example/rentoza/admin/service/AdminDisputeService.java`
- `apps/backend/src/main/java/org/example/rentoza/booking/BookingStatus.java` (if `REFUND_PENDING` doesn't exist)

**What is wrong:**
`resolveCheckInCancel()` sets `booking.setStatus(BookingStatus.CANCELLED)` then
calls `processFullRefund()` in a try/catch that swallows the exception. If refund
fails, the booking is cancelled but the guest is never refunded, with no mechanism
for recovery.

**Exact changes:**

Option A (preferred): Don't swallow the refund exception. Let the `@Transactional`
boundary roll back both the booking status change and the dispute resolution:

```java
// BEFORE:
try {
    paymentService.processFullRefund(booking.getId(),
        "Check-in dispute: " + resolution.getCancellationReason());
} catch (Exception e) {
    log.error("Failed to process refund for booking {} ...", booking.getId(), e);
    // Continue - manual refund will be needed
}

// AFTER:
try {
    paymentService.processFullRefund(booking.getId(),
        "Check-in dispute: " + resolution.getCancellationReason());
} catch (Exception e) {
    log.error("CRITICAL: Refund failed for booking {} during check-in dispute cancellation. " +
              "Rolling back cancellation. Admin must retry.", booking.getId(), e);
    throw new RuntimeException(
        "Refund failed for booking " + booking.getId() +
        ". Cancellation rolled back. Please retry or process refund manually.", e);
}
```

This ensures the booking is never marked `CANCELLED` without a successful refund.
The admin sees an error and can retry.

Option B (if business requires immediate cancellation regardless of refund): Add a
`CANCELLED_REFUND_PENDING` status or a `refundPending` boolean field on Booking.
Create a scheduled job that retries pending refunds. This is more complex; prefer
Option A unless explicitly required.

**Test:**
- Mock `processFullRefund` to throw → verify booking status is NOT `CANCELLED`
  (transaction rolled back).
- Mock `processFullRefund` to succeed → verify booking status IS `CANCELLED`.

---

## GROUP 2 — HIGH (Must complete before public launch)

---

### H-1: Implement Retry Count Tracking for Payouts

**Files to modify:**
- `apps/backend/src/main/java/org/example/rentoza/booking/Booking.java`
- `apps/backend/src/main/resources/db/migration/` (new Flyway migration)
- `apps/backend/src/main/java/org/example/rentoza/admin/service/AdminFinancialService.java`

**Changes:**

1. Add fields to `Booking` entity:

```java
@Column(name = "payout_retry_count")
private Integer payoutRetryCount = 0;

@Column(name = "last_payout_retry_at")
private Instant lastPayoutRetryAt;
```

2. Create Flyway migration:

```sql
ALTER TABLE bookings ADD COLUMN payout_retry_count INTEGER DEFAULT 0;
ALTER TABLE bookings ADD COLUMN last_payout_retry_at TIMESTAMPTZ;
```

3. In `retryPayout()`, enforce the limit:

```java
if (booking.getPayoutRetryCount() != null && booking.getPayoutRetryCount() >= MAX_RETRY_COUNT) {
    throw new IllegalStateException(
        "Maximum retry count (" + MAX_RETRY_COUNT + ") exceeded for booking " + bookingId +
        ". Manual payout required.");
}

// Before calling processHostPayout:
booking.setPayoutRetryCount(
    (booking.getPayoutRetryCount() != null ? booking.getPayoutRetryCount() : 0) + 1);
booking.setLastPayoutRetryAt(Instant.now());
bookingRepo.save(booking);
```

4. Update `toPayoutQueueDto()` to use the real retry count:

```java
.retryCount(booking.getPayoutRetryCount() != null ? booking.getPayoutRetryCount() : 0)
```

---

### H-2: Calculate Frozen Funds from Disputed Bookings

**Files to modify:**
- `apps/backend/src/main/java/org/example/rentoza/booking/BookingRepository.java`
- `apps/backend/src/main/java/org/example/rentoza/admin/service/AdminFinancialService.java`

**Changes:**

1. Add aggregate query to `BookingRepository`:

```java
@Query("SELECT COALESCE(SUM(b.totalAmount), 0) FROM Booking b " +
       "WHERE b.status IN :statuses")
BigDecimal sumTotalAmountByStatuses(@Param("statuses") Collection<BookingStatus> statuses);
```

2. In `getEscrowBalance()`, replace the hardcoded zero:

```java
// BEFORE:
BigDecimal frozenFunds = BigDecimal.ZERO;

// AFTER:
BigDecimal frozenFunds = bookingRepo.sumTotalAmountByStatuses(
    List.of(BookingStatus.CHECKOUT_DAMAGE_DISPUTE, BookingStatus.CHECK_IN_DISPUTE));
```

3. While here, also replace the in-memory sum for `totalEscrow` with a database
   aggregate query (fixes M-3 at the same time):

```java
// BEFORE:
List<Booking> activeBookings = bookingRepo.findByStatusIn(List.of(...));
BigDecimal totalEscrow = activeBookings.stream()
    .map(Booking::getTotalAmount)
    .reduce(BigDecimal.ZERO, BigDecimal::add);

// AFTER:
BigDecimal totalEscrow = bookingRepo.sumTotalAmountByStatuses(
    List.of(BookingStatus.PENDING_APPROVAL, BookingStatus.APPROVED,
            BookingStatus.ACTIVE, BookingStatus.PENDING_CHECKOUT,
            BookingStatus.CHECK_IN_OPEN, BookingStatus.CHECK_IN_HOST_COMPLETE,
            BookingStatus.CHECK_IN_COMPLETE, BookingStatus.IN_TRIP,
            BookingStatus.CHECKOUT_OPEN, BookingStatus.CHECKOUT_GUEST_COMPLETE,
            BookingStatus.CHECKOUT_HOST_COMPLETE));
```

---

### H-3: Add State Machine Transition Guards to Car Service

**Files to modify:**
- `apps/backend/src/main/java/org/example/rentoza/admin/service/AdminCarService.java`

**Changes:**

Add validation at the top of each method:

```java
// In rejectCar():
if (car.getApprovalStatus() != ApprovalStatus.PENDING) {
    throw new IllegalStateException(
        "Cannot reject car in state " + car.getApprovalStatus() + ". Only PENDING cars can be rejected.");
}

// In suspendCar():
if (car.getApprovalStatus() != ApprovalStatus.APPROVED) {
    throw new IllegalStateException(
        "Cannot suspend car in state " + car.getApprovalStatus() + ". Only APPROVED cars can be suspended.");
}

// In reactivateCar():
if (car.getApprovalStatus() != ApprovalStatus.SUSPENDED) {
    throw new IllegalStateException(
        "Cannot reactivate car in state " + car.getApprovalStatus() + ". Only SUSPENDED cars can be reactivated.");
}

// In approveCar():
if (car.getApprovalStatus() != ApprovalStatus.PENDING) {
    throw new IllegalStateException(
        "Cannot approve car in state " + car.getApprovalStatus() + ". Only PENDING cars can be approved.");
}
```

---

### H-4: Return Cached Response Body from Idempotency Service

**Files to modify:**
- `apps/backend/src/main/java/org/example/rentoza/admin/controller/AdminCarController.java`

**Changes:**

```java
// BEFORE (line ~148-153):
if (idempotencyKey != null) {
    var cached = idempotencyService.checkIdempotency(idempotencyKey, adminId);
    if (cached.isPresent() && cached.get().getStatus() == IdempotencyService.IdempotencyStatus.COMPLETED) {
        log.info("Returning cached approval response for key={}", idempotencyKey.substring(0, 8));
        return ResponseEntity.ok().build();
    }
}

// AFTER:
if (idempotencyKey != null) {
    var cached = idempotencyService.checkIdempotency(idempotencyKey, adminId);
    if (cached.isPresent()) {
        var result = cached.get();
        if (result.getStatus() == IdempotencyService.IdempotencyStatus.COMPLETED) {
            log.info("Returning cached approval response for key={}", idempotencyKey.substring(0, 8));
            if (result.getResponseBody() != null) {
                return ResponseEntity.status(result.getHttpStatus())
                    .body(objectMapper.readValue(result.getResponseBody(), AdminCarDto.class));
            }
            return ResponseEntity.ok().build();
        }
        if (result.getStatus() == IdempotencyService.IdempotencyStatus.PROCESSING) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "OPERATION_IN_PROGRESS",
                             "message", "This operation is already being processed"));
        }
    }
}
```

Inject `ObjectMapper` into the controller if not already present.

---

### H-5: Add Pessimistic Locking to Car Approval

**Files to modify:**
- `apps/backend/src/main/java/org/example/rentoza/car/CarRepository.java`
- `apps/backend/src/main/java/org/example/rentoza/admin/service/AdminCarService.java`

**Changes:**

1. `CarRepository` already has `findByIdForUpdate()`. Use it in `AdminCarService`.

2. In `approveCar()`, `rejectCar()`, `suspendCar()`, `reactivateCar()` — replace:

```java
// BEFORE:
Car car = carRepo.findWithDetailsById(carId)
    .orElseThrow(...);

// AFTER:
Car car = carRepo.findByIdForUpdate(carId)
    .orElseThrow(() -> new ResourceNotFoundException("Car not found: " + carId));
```

Note: `findByIdForUpdate` may not eagerly load relations. If the DTO mapping needs
`owner`, `features`, etc., either:
- Create a new `findWithDetailsByIdForUpdate()` with `@Lock` + `@EntityGraph`, or
- Access the relations within the transaction (they'll lazy-load since we're in a
  `@Transactional` method).

---

### H-6: Expand Force-Complete Payment State Guard

**Files to modify:**
- `apps/backend/src/main/java/org/example/rentoza/admin/controller/AdminBookingController.java`

**Changes:**

```java
// BEFORE (line ~138-145):
ChargeLifecycleStatus chargeStatus = booking.getChargeLifecycleStatus();
if (chargeStatus == ChargeLifecycleStatus.AUTHORIZED
        || chargeStatus == ChargeLifecycleStatus.REAUTH_REQUIRED) {
    return ResponseEntity.badRequest().body(Map.of(...));
}

// AFTER:
ChargeLifecycleStatus chargeStatus = booking.getChargeLifecycleStatus();
if (chargeStatus != null && !chargeStatus.isTerminal()) {
    return ResponseEntity.badRequest().body(Map.of(
        "error", "PAYMENT_IN_PROGRESS",
        "message", "Cannot force-complete: payment is in non-terminal state " + chargeStatus +
                   ". Wait for payment to settle or resolve manually.",
        "chargeLifecycleStatus", chargeStatus.name()
    ));
}
```

This uses the existing `isTerminal()` method on `ChargeLifecycleStatus` to catch
all non-terminal states (PENDING, AUTHORIZED, REAUTH_REQUIRED, CAPTURE_FAILED)
instead of enumerating individual values.

---

### H-7: Fix User Deletion — Audit Before Delete

**Files to modify:**
- `apps/backend/src/main/java/org/example/rentoza/admin/service/AdminUserService.java`

**Changes:**

Move the audit log call to BEFORE the delete:

```java
public void deleteUser(Long userId, String reason, User admin) {
    User targetUser = userRepo.findById(userId)
        .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

    if (targetUser.getRole() == Role.ADMIN) {
        throw new IllegalArgumentException("Cannot delete admin users");
    }

    // Capture state BEFORE any cascade changes
    String beforeState = auditService.toJson(AdminUserDetailDto.fromEntity(targetUser, ...));

    // Audit FIRST — even if cascade fails, the intent is logged
    auditService.logAction(
        admin,
        AdminAction.USER_DELETED,
        ResourceType.USER,
        userId,
        beforeState,
        null,
        reason
    );

    // Then cascade
    cancelUserBookings(targetUser);
    deactivateUserCars(targetUser);
    anonymizeUserReviews(targetUser);
    userRepo.delete(targetUser);
}
```

This ensures audit is always persisted. If a cascade step fails, `@Transactional`
rolls back everything including the audit entry — which is correct because the
deletion didn't actually happen.

---

### H-8: Suppress Setters on AdminAuditLog

**Files to modify:**
- `apps/backend/src/main/java/org/example/rentoza/admin/entity/AdminAuditLog.java`

**Changes:**

```java
// BEFORE:
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder

// AFTER:
@Getter
@Setter(AccessLevel.NONE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // JPA requires no-arg, but hide it
@AllArgsConstructor(access = AccessLevel.PRIVATE)    // Only @Builder uses this
@Builder
```

Import: `import lombok.AccessLevel;`

---

### H-9: Fix Payout Queue Pagination

**Files to modify:**
- `apps/backend/src/main/java/org/example/rentoza/booking/BookingRepository.java`
- `apps/backend/src/main/java/org/example/rentoza/admin/service/AdminFinancialService.java`

**Changes:**

1. Add a database-level query that includes the `paymentReference IS NULL` filter:

```java
@Query("SELECT b FROM Booking b " +
       "JOIN FETCH b.car c " +
       "LEFT JOIN FETCH c.owner " +
       "WHERE b.status = :status " +
       "AND b.updatedAt < :cutoff " +
       "AND b.paymentReference IS NULL " +
       "ORDER BY b.updatedAt ASC")
Page<Booking> findPendingPayouts(
    @Param("status") BookingStatus status,
    @Param("cutoff") Instant cutoff,
    Pageable pageable);
```

2. In `getPayoutQueue()`:

```java
// BEFORE:
Page<Booking> pendingPayouts = bookingRepo.findByStatusAndUpdatedAtBefore(
    BookingStatus.COMPLETED, cutoffDate, pageable);
List<PayoutQueueDto> dtos = pendingPayouts.getContent().stream()
    .filter(booking -> booking.getPaymentReference() == null)
    .map(this::toPayoutQueueDto)
    .collect(Collectors.toList());
return new PageImpl<>(dtos, pageable, pendingPayouts.getTotalElements());

// AFTER:
Page<Booking> pendingPayouts = bookingRepo.findPendingPayouts(
    BookingStatus.COMPLETED, cutoffDate, pageable);
return pendingPayouts.map(this::toPayoutQueueDto);
```

---

### H-10: Write Tests for Critical Admin Services

**Files to create:**
- `apps/backend/src/test/java/org/example/rentoza/admin/service/AdminFinancialServiceTest.java`
- `apps/backend/src/test/java/org/example/rentoza/admin/service/AdminDisputeServiceTest.java`
- `apps/backend/src/test/java/org/example/rentoza/admin/service/AdminUserServiceTest.java`

Follow the pattern in `AdminCarServiceTest.java`. Each test class must cover:

**AdminFinancialServiceTest:**
- `processBatchPayouts` — happy path (dry run and real)
- `processBatchPayouts` — booking not found → failure entry
- `processBatchPayouts` — booking not COMPLETED → failure entry
- `processBatchPayouts` — booking already has paymentReference → `DUPLICATE_PAYOUT`
- `retryPayout` — happy path
- `retryPayout` — max retries exceeded → `IllegalStateException`
- `retryPayout` — payment fails → exception thrown, audit logged
- `getEscrowBalance` — calculates totals correctly
- `getPayoutQueue` — only returns COMPLETED bookings past holding period

**AdminDisputeServiceTest:**
- `resolveCheckoutDispute` with APPROVE → deposit captured, booking transitioned
- `resolveCheckoutDispute` with REJECT → deposit released
- `resolveCheckoutDispute` with PARTIAL → approved amount bounded by claim and deposit
- `resolveCheckoutDispute` with PARTIAL amount > claim → `IllegalArgumentException`
- `resolveCheckoutDispute` on already-resolved dispute → `IllegalStateException`
- `resolveCheckInDispute` with CANCEL → refund called, booking cancelled
- `resolveCheckInDispute` with CANCEL, refund fails → exception propagated

**AdminUserServiceTest:**
- `deleteUser` — cascades bookings, cars, reviews, audit logged
- `deleteUser` — admin target → `IllegalArgumentException`
- `banUser` — sets banned, reason, timestamp, audit logged
- `calculateRiskScore` — boundary tests (new account, high cancellation, max disputes)
- `calculateRiskScore` — capped at 100

---

## GROUP 3 — MEDIUM (Fix before scale)

---

### M-1: Fix Dashboard KPI Timezone Consistency

**File:** `AdminDashboardService.java`

Replace `Instant.now()` with consistent Serbia-timezone conversion:

```java
// BEFORE:
Instant nowInstant = Instant.now();

// AFTER:
Instant nowInstant = SerbiaTimeZone.toInstant(now);
```

---

### M-2: Fix N+1 Query in Recent Bookings

**File:** `BookingRepository.java`, `AdminDashboardService.java`

Add:
```java
@Query("SELECT b FROM Booking b " +
       "JOIN FETCH b.car c " +
       "JOIN FETCH b.renter " +
       "LEFT JOIN FETCH c.owner " +
       "ORDER BY b.createdAt DESC")
List<Booking> findRecentBookingsWithRelations(Pageable pageable);
```

Use in `getRecentBookings()`:
```java
List<Booking> bookings = bookingRepo.findRecentBookingsWithRelations(
    PageRequest.of(0, Math.min(limit, 20)));
```

---

### M-3: Replace In-Memory Escrow Sum with DB Aggregate

Already addressed in H-2 above. Verify both `totalEscrow` and `frozenFunds` use
database aggregate queries.

---

### M-4: Escape LIKE Pattern Special Characters

**File:** `AdminBookingController.java`

Create a utility method and use it in `buildSpecification()`:

```java
private String escapeLikePattern(String input) {
    return input.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
}

// In buildSpecification:
String pattern = "%" + escapeLikePattern(search.toLowerCase()) + "%";
```

Apply the same pattern to any other admin controller or service that builds LIKE
queries from user input.

---

### M-5: Add Pagination Parameter Validation

**Files:** `AdminBookingController.java`, `AdminFinancialController.java`, any
controller accepting `page`/`size` params.

```java
@GetMapping
public ResponseEntity<Page<AdminBookingDto>> listBookings(
    @RequestParam(required = false) BookingStatus status,
    @RequestParam(required = false) String search,
    @RequestParam(defaultValue = "0") @Min(0) int page,
    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
```

Add `@Validated` to the controller class to enable method-level validation:
```java
@RestController
@RequestMapping("/api/admin/bookings")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminBookingController {
```

---

### M-6: Add @Positive to Path Variable IDs

**Files:** All admin controllers.

Add `@Positive` to every `@PathVariable Long id` parameter:

```java
public ResponseEntity<AdminCarDto> getCarDetail(
    @PathVariable @Positive Long id) {
```

Add `@Validated` to the controller class if not already present.

---

### M-7: Fix Revenue Precision in Metrics Snapshot

**File:** `AdminDashboardService.java`

```java
// BEFORE:
.totalRevenueCents(kpis.getTotalRevenueThisMonth()
    .multiply(BigDecimal.valueOf(100)).longValue())

// AFTER:
.totalRevenueCents(kpis.getTotalRevenueThisMonth()
    .multiply(BigDecimal.valueOf(100))
    .setScale(0, RoundingMode.HALF_UP)
    .longValue())
```

---

### M-8: Add Admin User ID to MDC in Audit Interceptor

**File:** `AdminAuditInterceptor.java`

```java
@Override
public boolean preHandle(HttpServletRequest request,
                         HttpServletResponse response,
                         Object handler) {
    String requestId = UUID.randomUUID().toString().substring(0, 8);
    MDC.put("requestId", requestId);
    MDC.put("path", request.getRequestURI());

    // NEW: Add admin identity to MDC
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.isAuthenticated()) {
        MDC.put("adminUser", auth.getName());
    }

    response.setHeader("X-Request-ID", requestId);
    return true;
}
```

Import: `import org.springframework.security.core.Authentication;`
Import: `import org.springframework.security.core.context.SecurityContextHolder;`

---

### M-9: Add Null Safety to Payout DTO Mapping

**File:** `AdminFinancialService.java`

```java
private PayoutQueueDto toPayoutQueueDto(Booking booking) {
    Car car = booking.getCar();
    if (car == null || car.getOwner() == null) {
        log.warn("Booking {} has null car or owner — skipping payout DTO mapping", booking.getId());
        return null;
    }
    User host = car.getOwner();
    // ... rest unchanged
}
```

In `getPayoutQueue()`, filter out nulls after mapping:

```java
return pendingPayouts.map(this::toPayoutQueueDto);
// If using the Stream approach:
.filter(Objects::nonNull)
```

---

### M-10: Scope Idempotency Key by Operation Type

**File:** `AdminCarController.java`

The `storeSuccess` call (around line 167) already passes `"CAR_APPROVE"`. Make the
check call match:

```java
// BEFORE:
var cached = idempotencyService.checkIdempotency(idempotencyKey, adminId);

// AFTER:
var cached = idempotencyService.checkIdempotency(idempotencyKey, adminId);
// Filter: only return cached result if it was for the same operation
if (cached.isPresent() && !"CAR_APPROVE".equals(cached.get().getOperationType())) {
    log.warn("Idempotency key {} was used for a different operation: {}",
             idempotencyKey.substring(0, 8), cached.get().getOperationType());
    cached = Optional.empty();
}
```

If `IdempotencyService.checkIdempotency` supports a third parameter for operation
type, prefer that instead.

---

## GROUP 4 — LOW / IMPROVEMENTS

---

### L-1: Remove Duplicate Import

**File:** `AdminDisputeService.java` — remove the duplicate `AdminAction` import.

### L-2: Add Service-Layer Authorization

Add `@PreAuthorize("hasRole('ADMIN')")` to these service methods (defense in depth):

- `AdminFinancialService.processBatchPayouts()`
- `AdminFinancialService.retryPayout()`
- `AdminDisputeService.resolveCheckoutDispute()`
- `AdminDisputeService.resolveCheckInDispute()`
- `AdminUserService.deleteUser()`
- `AdminUserService.banUser()`

### L-3: Migrate Rate Limiter Off Deprecated JwtUtil

**File:** `RateLimitingFilter.java`

Replace `JwtUtil jwtUtil` with `SupabaseJwtUtil supabaseJwtUtil`. Update the
`getRateLimitKey()` method to use `supabaseJwtUtil.getEmail(token)` (or equivalent).
Remove the deprecated `JwtUtil` import.

### L-4: Add Database-Level Audit Log Immutability

Create a Flyway migration:

```sql
-- Prevent UPDATE on audit log at database level
CREATE OR REPLACE FUNCTION prevent_audit_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'admin_audit_log is immutable: % operations are not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_immutable_update
    BEFORE UPDATE ON admin_audit_log
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();

CREATE TRIGGER audit_log_immutable_delete
    BEFORE DELETE ON admin_audit_log
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();
```

### L-5: Fix `toPayoutQueueDto` Hardcoded Retry Count

Already addressed in H-1 (retry count tracking).

---

## Verification Checklist

After all changes, verify:

- [ ] All existing tests still pass (`./gradlew test` or `mvn test`)
- [ ] New tests pass for AdminFinancialService, AdminDisputeService, AdminUserService
- [ ] No regressions in admin API endpoints (manual smoke test or integration test)
- [ ] Flyway migrations apply cleanly on a fresh database
- [ ] Admin dashboard frontend loads without errors (KPIs, payout queue, disputes)
- [ ] Forged JWT with unknown `kid` → 401 on all admin endpoints
- [ ] Expired JWT → 401 on all admin endpoints
- [ ] Concurrent car approval requests → only one succeeds (manual test with `curl`)
- [ ] Batch payout with duplicate booking IDs → `DUPLICATE_PAYOUT` error for second
- [ ] Dispute resolution by two admins simultaneously → second gets lock timeout or
  state error
- [ ] Partial dispute approval with amount > deposit → `IllegalArgumentException`
- [ ] Car reject on non-PENDING car → `IllegalStateException`
- [ ] Force-complete on booking with AUTHORIZED payment → 400 error
- [ ] User deletion logs audit BEFORE cascade operations
- [ ] Payout queue pagination shows correct total count

---

## What NOT to Change

- Do not modify the frontend Angular components unless a backend API contract changes
  (in which case, update the corresponding DTO interfaces in
  `apps/frontend/src/app/core/models/`).
- Do not change the `BookingPaymentService` internals — treat it as a black box.
- Do not modify the `SecurityConfig` filter chain order.
- Do not add new admin role types (SUPER_ADMIN, FINANCE_ADMIN, etc.) — that's a
  separate project.
- Do not change the `AdminAuditLog` table schema (only add DB triggers).
- Do not refactor working code that isn't related to audit findings. Keep changes
  minimal and focused.

# Audit Remediation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix all 16 audit items (7 CRITICAL, 9 HIGH) identified in the Rentoza Execution Prompt before deployment.

**Architecture:** Each fix is a targeted, minimally-invasive change to the Spring Boot backend. Database schema changes use Flyway migrations (V75+). All fixes follow existing patterns: constructor injection, `@Transactional`, BigDecimal for money, Serbian-language user-facing messages.

**Tech Stack:** Java 21, Spring Boot 3.5.x, PostgreSQL, Flyway, JPA/Hibernate, Micrometer metrics

---

## CRITICAL FIXES (C-1 through C-7) — Deployment Blockers

---

### Task 1: C-1 — Fix `compensateCompleteBooking()` status rollback

**Severity:** CRITICAL — Masks failed checkout as completed booking

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/booking/checkout/saga/CheckoutSagaOrchestrator.java:944-950`

**Step 1: Fix the status constant**

Change line 946 from:
```java
booking.setStatus(BookingStatus.COMPLETED);  // Revert to previous status
```
to:
```java
booking.setStatus(BookingStatus.CHECKOUT_HOST_COMPLETE);  // Revert to pre-saga status
```

Also fix the log message on line 949:
```java
log.info("[Saga] Reverted booking {} status to CHECKOUT_HOST_COMPLETE", saga.getBookingId());
```

**Step 2: Run tests**

Run: `./gradlew :apps:backend:test --tests "*CheckoutSaga*" -x :apps:frontend:build`
Expected: All existing tests pass

**Step 3: Commit**

```bash
git add apps/backend/src/main/java/org/example/rentoza/booking/checkout/saga/CheckoutSagaOrchestrator.java
git commit -m "fix(audit-C1): compensateCompleteBooking reverts to CHECKOUT_HOST_COMPLETE

- Was incorrectly setting COMPLETED, masking failed saga as success
- Correct pre-saga state is CHECKOUT_HOST_COMPLETE
- CRITICAL audit item C-1

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: C-2 — Add checkout photo validation in saga's `executeValidateReturn()`

**Severity:** CRITICAL — Saga can complete without required evidence photos

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/booking/checkout/saga/CheckoutSagaOrchestrator.java:385`
- Read (context): `apps/backend/src/main/java/org/example/rentoza/booking/checkin/CheckInPhotoRepository.java`

**Step 1: Replace the TODO with actual validation**

Replace line 385:
```java
// TODO: Validate checkout photos exist
```
with:
```java
// C-2 FIX: Validate guest checkout photos exist (minimum 6 required slots)
long guestCheckoutPhotoCount = checkInPhotoRepository.countGuestCheckoutPhotos(booking.getId());
if (guestCheckoutPhotoCount < 6) {
    throw new IllegalStateException(
            String.format("Nedovoljno fotografija za checkout. Potrebno: 6, pronađeno: %d",
                    guestCheckoutPhotoCount));
}
```

**Step 2: Verify `countGuestCheckoutPhotos` query exists or add it**

Check `CheckInPhotoRepository` for the query. If missing, add:
```java
@Query("""
    SELECT COUNT(p) FROM CheckInPhoto p
    WHERE p.booking.id = :bookingId
    AND p.photoType IN (
        'CHECKOUT_FRONT', 'CHECKOUT_REAR', 'CHECKOUT_LEFT',
        'CHECKOUT_RIGHT', 'CHECKOUT_INTERIOR', 'CHECKOUT_ODOMETER'
    )
    AND p.deleted = false
    """)
long countGuestCheckoutPhotos(@Param("bookingId") Long bookingId);
```

**Step 3: Ensure `checkInPhotoRepository` is injected into the orchestrator**

Check constructor of `CheckoutSagaOrchestrator`. If `CheckInPhotoRepository` is not already injected, add it as a constructor parameter.

**Step 4: Run tests**

Run: `./gradlew :apps:backend:test --tests "*CheckoutSaga*" -x :apps:frontend:build`
Expected: PASS

**Step 5: Commit**

```bash
git add apps/backend/src/main/java/org/example/rentoza/booking/checkout/saga/CheckoutSagaOrchestrator.java
git add apps/backend/src/main/java/org/example/rentoza/booking/checkin/CheckInPhotoRepository.java
git commit -m "fix(audit-C2): validate checkout photos exist before saga proceeds

- Replace TODO stub with actual guest checkout photo count check
- Require minimum 6 photos (front, rear, left, right, interior, odometer)
- Saga now fails fast if checkout evidence is missing
- CRITICAL audit item C-2

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: C-3 — Add partial unique index on `checkout_saga_state`

**Severity:** CRITICAL — Two sagas can run concurrently for the same booking

**Files:**
- Create: `apps/backend/src/main/resources/db/migration/V75__audit_saga_unique_index.sql`

**Step 1: Write the Flyway migration**

```sql
-- V75: Audit C-3 — Prevent concurrent sagas on same booking
-- Partial unique index ensures at most one RUNNING or SUSPENDED saga per booking.

CREATE UNIQUE INDEX IF NOT EXISTS idx_saga_booking_active
    ON checkout_saga_state (booking_id)
    WHERE status IN ('RUNNING', 'SUSPENDED');
```

**Step 2: Run migration locally**

Run: `./gradlew :apps:backend:flywayMigrate` or start the app and verify migration applies.

**Step 3: Commit**

```bash
git add apps/backend/src/main/resources/db/migration/V75__audit_saga_unique_index.sql
git commit -m "fix(audit-C3): add partial unique index preventing concurrent sagas

- CREATE UNIQUE INDEX on checkout_saga_state(booking_id) WHERE status IN ('RUNNING','SUSPENDED')
- Prevents two sagas running for the same booking at the database level
- CRITICAL audit item C-3

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: C-4 — Add pessimistic locking to saga recovery queries

**Severity:** CRITICAL — Recovery scheduler can contend with active saga execution

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/booking/checkout/saga/CheckoutSagaStateRepository.java:49-66`

**Step 1: Add `@Lock` and `SKIP LOCKED` to recovery queries**

For `findStuckSagas`, `findRetryableSagas`, and `findSagasNeedingCompensation`, add pessimistic write locks with SKIP LOCKED hint so recovery doesn't block active sagas.

Replace each query method:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
@Query("""
    SELECT s FROM CheckoutSagaState s
    WHERE s.status = 'RUNNING'
    AND s.updatedAt < :threshold
    ORDER BY s.updatedAt ASC
    """)
List<CheckoutSagaState> findStuckSagas(@Param("threshold") Instant threshold);

@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
@Query("""
    SELECT s FROM CheckoutSagaState s
    WHERE s.status = 'FAILED'
    AND s.retryCount < 3
    ORDER BY s.updatedAt ASC
    """)
List<CheckoutSagaState> findRetryableSagas();

@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
@Query("""
    SELECT s FROM CheckoutSagaState s
    WHERE s.status = 'COMPENSATING'
    ORDER BY s.updatedAt ASC
    """)
List<CheckoutSagaState> findSagasNeedingCompensation();
```

Add imports at top:
```java
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;
```

**Note:** `lock.timeout = -2` is the Hibernate hint for `SKIP LOCKED` on PostgreSQL.

**Step 2: Run tests**

Run: `./gradlew :apps:backend:test --tests "*SagaRecovery*" --tests "*CheckoutSaga*" -x :apps:frontend:build`
Expected: PASS

**Step 3: Commit**

```bash
git add apps/backend/src/main/java/org/example/rentoza/booking/checkout/saga/CheckoutSagaStateRepository.java
git commit -m "fix(audit-C4): add SKIP LOCKED to saga recovery queries

- findStuckSagas, findRetryableSagas, findSagasNeedingCompensation now use
  PESSIMISTIC_WRITE with lock.timeout=-2 (SKIP LOCKED)
- Prevents recovery scheduler from contending with active saga execution
- Entity already has @Version for optimistic locking (defense-in-depth)
- CRITICAL audit item C-4

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: C-5 — Cap auto-approved claim amount at security deposit

**Severity:** CRITICAL — Host could file claim exceeding deposit, get auto-approved for full amount

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/booking/dispute/DamageClaimService.java:420-423`

**Step 1: Add deposit cap**

Replace lines 420-422:
```java
claim.setStatus(DamageClaimStatus.AUTO_APPROVED);
claim.setApprovedAmount(claim.getClaimedAmount());
claimRepository.save(claim);
```
with:
```java
claim.setStatus(DamageClaimStatus.AUTO_APPROVED);

// C-5 FIX: Cap approved amount at security deposit (never approve more than guest paid)
BigDecimal depositAmount = claim.getBooking().getSecurityDeposit();
BigDecimal cappedAmount = claim.getClaimedAmount();
if (depositAmount != null && cappedAmount.compareTo(depositAmount) > 0) {
    cappedAmount = depositAmount;
    log.warn("[DamageClaim] Auto-approved claim {} capped at deposit: claimed={} RSD, deposit={} RSD",
            claim.getId(), claim.getClaimedAmount(), depositAmount);
}
claim.setApprovedAmount(cappedAmount);
claimRepository.save(claim);
```

**Step 2: Run tests**

Run: `./gradlew :apps:backend:test --tests "*DamageClaim*" -x :apps:frontend:build`
Expected: PASS

**Step 3: Commit**

```bash
git add apps/backend/src/main/java/org/example/rentoza/booking/dispute/DamageClaimService.java
git commit -m "fix(audit-C5): cap auto-approved claim amount at security deposit

- autoApproveExpiredClaims now limits approvedAmount to MIN(claimedAmount, deposit)
- Prevents hosts from getting auto-approved for more than guest's security deposit
- CRITICAL audit item C-5

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 6: C-6 — Set `adminReviewRequired` in `DamageClaimService.createClaim()`

**Severity:** CRITICAL — High-value claims filed via standalone path skip admin review

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/booking/dispute/DamageClaimService.java:137-149`

**Step 1: Add admin review flag to claim builder**

After line 136 (where the builder starts), add `adminReviewRequired` determination. Find the `.build()` call and add the field before it.

Add before the builder:
```java
// C-6 FIX: Flag high-value claims for mandatory admin review
// Matches threshold used in CheckOutService.createCheckoutDamageClaim()
boolean adminReviewRequired = claimedAmount != null
        && claimedAmount.compareTo(new BigDecimal("50000")) > 0;
```

Add to the builder chain (before `.build()`):
```java
.adminReviewRequired(adminReviewRequired)
```

**Step 2: Run tests**

Run: `./gradlew :apps:backend:test --tests "*DamageClaim*" -x :apps:frontend:build`
Expected: PASS

**Step 3: Commit**

```bash
git add apps/backend/src/main/java/org/example/rentoza/booking/dispute/DamageClaimService.java
git commit -m "fix(audit-C6): set adminReviewRequired for high-value standalone claims

- createClaim() now flags claims >50,000 RSD for admin review
- Matches threshold already used in CheckOutService.createCheckoutDamageClaim()
- CRITICAL audit item C-6

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 7: C-7 — Re-throw exception in `compensateCaptureDeposit()`

**Severity:** CRITICAL — Swallowed refund failure marks saga as COMPENSATED with unreversed charges

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/booking/checkout/saga/CheckoutSagaOrchestrator.java:937-940`

**Step 1: Re-throw the exception**

Replace lines 937-940:
```java
} catch (Exception e) {
    log.error("[Saga] CRITICAL: Exception during deposit capture compensation for booking {}: {}",
            saga.getBookingId(), e.getMessage(), e);
}
```
with:
```java
} catch (Exception e) {
    log.error("[Saga] CRITICAL: Exception during deposit capture compensation for booking {}: {}",
            saga.getBookingId(), e.getMessage(), e);
    // C-7 FIX: Re-throw so saga transitions to FAILED, not COMPENSATED
    // Swallowing this leaves guest charged with no refund record
    throw new RuntimeException("Compensation refund failed for booking " + saga.getBookingId(), e);
}
```

Also handle refund API failure (non-exception) — after the `if (refundResult.isSuccess())` block, add else-throw:

Between lines 932-936, the existing `else` block only logs. Add a throw:
```java
} else {
    log.error("[Saga] CRITICAL: Failed to refund deposit capture for booking {}. " +
            "Manual intervention required. Error: {}",
            saga.getBookingId(), refundResult.getErrorMessage());
    // C-7 FIX: Fail the compensation so saga doesn't mark as COMPENSATED
    throw new RuntimeException("Compensation refund refused by payment provider for booking "
            + saga.getBookingId() + ": " + refundResult.getErrorMessage());
}
```

**Step 2: Run tests**

Run: `./gradlew :apps:backend:test --tests "*CheckoutSaga*" -x :apps:frontend:build`
Expected: PASS

**Step 3: Commit**

```bash
git add apps/backend/src/main/java/org/example/rentoza/booking/checkout/saga/CheckoutSagaOrchestrator.java
git commit -m "fix(audit-C7): re-throw in compensateCaptureDeposit on refund failure

- Exception was swallowed, causing saga to mark as COMPENSATED without actual refund
- Now re-throws RuntimeException so saga transitions to FAILED
- Also throws on payment provider refusal (non-exception failure)
- Ensures no guest is silently charged with no refund record
- CRITICAL audit item C-7

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## HIGH FIXES (H-1 through H-9) — Pre-Launch Required

---

### Task 8: H-1 — Use pessimistic locking in `initiateCheckout()`

**Severity:** HIGH — Two concurrent checkout initiations can create duplicate sessions

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/booking/checkout/CheckOutService.java:191`

**Step 1: Replace `findByIdWithRelations` with `findByIdWithLock`**

Check that `BookingRepository` has a `findByIdWithLock` method (it should, since `HostCheckoutPhotoService` uses it). Change line 191:

```java
Booking booking = bookingRepository.findByIdWithLock(bookingId)
        .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
```

**Step 2: Run tests**

Run: `./gradlew :apps:backend:test --tests "*CheckOut*" -x :apps:frontend:build`
Expected: PASS

**Step 3: Commit**

```bash
git add apps/backend/src/main/java/org/example/rentoza/booking/checkout/CheckOutService.java
git commit -m "fix(audit-H1): use pessimistic lock in initiateCheckout

- findByIdWithRelations replaced with findByIdWithLock (SELECT FOR UPDATE)
- Prevents race condition where two concurrent requests create duplicate checkout sessions
- HIGH audit item H-1

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 9: H-2 — Guard against overwriting admin hold reason in saga

**Severity:** HIGH — Saga overwrites admin-set deposit hold reason with generic policy text

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/booking/checkout/saga/CheckoutSagaOrchestrator.java:791`

**Step 1: Add guard before setting hold reason**

Replace line 791:
```java
booking.setSecurityDepositHoldReason("48h post-checkout hold period (standard policy)");
```
with:
```java
// H-2 FIX: Don't overwrite admin-set hold reason
if (booking.getSecurityDepositHoldReason() == null) {
    booking.setSecurityDepositHoldReason("48h post-checkout hold period (standard policy)");
}
```

**Step 2: Run tests**

Run: `./gradlew :apps:backend:test --tests "*CheckoutSaga*" -x :apps:frontend:build`
Expected: PASS

**Step 3: Commit**

```bash
git add apps/backend/src/main/java/org/example/rentoza/booking/checkout/saga/CheckoutSagaOrchestrator.java
git commit -m "fix(audit-H2): guard against overwriting admin deposit hold reason

- Only set default hold reason if one isn't already set
- Prevents saga from overwriting admin-set reason with generic policy text
- HIGH audit item H-2

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 10: H-3 — Clear `approvedAmount` on admin rejection

**Severity:** HIGH — Rejected claim retains stale approved amount, confusing downstream logic

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/booking/dispute/DamageClaim.java:400-405`

**Step 1: Add the line to clear approvedAmount**

In the `rejectByAdmin()` method, add after setting status:
```java
public void rejectByAdmin(User admin, String notes) {
    this.status = DamageClaimStatus.ADMIN_REJECTED;
    this.approvedAmount = null;  // H-3 FIX: Clear stale approved amount on rejection
    this.reviewedBy = admin;
    this.reviewedAt = Instant.now();
    this.adminNotes = notes;
}
```

**Step 2: Run tests**

Run: `./gradlew :apps:backend:test --tests "*DamageClaim*" -x :apps:frontend:build`
Expected: PASS

**Step 3: Commit**

```bash
git add apps/backend/src/main/java/org/example/rentoza/booking/dispute/DamageClaim.java
git commit -m "fix(audit-H3): clear approvedAmount on admin claim rejection

- rejectByAdmin() now sets approvedAmount = null
- Prevents stale amount from confusing downstream financial logic
- HIGH audit item H-3

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 11: H-4 — Validate `claimedAmount` against security deposit

**Severity:** HIGH — Host can claim more than guest's deposit

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/booking/dispute/DamageClaimService.java:100-149`

**Step 1: Add validation after booking retrieval**

After the booking is loaded (line 101) and the host validation (lines 104-107), add:

```java
// H-4 FIX: Validate claimed amount does not exceed security deposit
BigDecimal depositAmount = booking.getSecurityDeposit();
if (depositAmount != null && claimedAmount.compareTo(depositAmount) > 0) {
    throw new IllegalArgumentException(String.format(
            "Iznos prijave (%.0f RSD) ne može biti veći od depozita (%.0f RSD)",
            claimedAmount.doubleValue(), depositAmount.doubleValue()));
}
```

**Step 2: Run tests**

Run: `./gradlew :apps:backend:test --tests "*DamageClaim*" -x :apps:frontend:build`
Expected: PASS

**Step 3: Commit**

```bash
git add apps/backend/src/main/java/org/example/rentoza/booking/dispute/DamageClaimService.java
git commit -m "fix(audit-H4): validate claimedAmount <= security deposit

- createClaim() rejects claims exceeding the guest's security deposit
- Prevents hosts from filing inflated damage claims
- HIGH audit item H-4

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 12: H-5 — Guard `checkoutSessionId` generation in scheduler

**Severity:** HIGH — Scheduler overwrites existing session ID, orphaning associated data

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/booking/checkout/CheckOutService.java:276`

**Step 1: Add null check before generating UUID**

Replace line 276:
```java
booking.setCheckoutSessionId(UUID.randomUUID().toString());
```
with:
```java
// H-5 FIX: Don't overwrite existing session ID
if (booking.getCheckoutSessionId() == null) {
    booking.setCheckoutSessionId(UUID.randomUUID().toString());
}
```

**Step 2: Run tests**

Run: `./gradlew :apps:backend:test --tests "*CheckOut*" -x :apps:frontend:build`
Expected: PASS

**Step 3: Commit**

```bash
git add apps/backend/src/main/java/org/example/rentoza/booking/checkout/CheckOutService.java
git commit -m "fix(audit-H5): guard checkoutSessionId generation in scheduler

- Only generate new UUID if checkoutSessionId is null
- Prevents scheduler from orphaning photos/events linked to existing session
- HIGH audit item H-5

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 13: H-6 — Use signed URLs in photo DTOs

**Severity:** HIGH — Raw storage keys exposed to frontend instead of signed URLs

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/booking/checkin/CheckInPhotoService.java:952-967`
- Modify: `apps/backend/src/main/java/org/example/rentoza/booking/checkout/HostCheckoutPhotoService.java:425-441,611-631`

**Step 1: Inject `PhotoUrlService` into `CheckInPhotoService`**

`CheckInPhotoService` already uses constructor injection. Check if `PhotoUrlService` is already injected. If not, add it as a field:

```java
private final PhotoUrlService photoUrlService;
```

And add it to the constructor parameter list.

**Step 2: Fix `CheckInPhotoService.mapToDTO()` (line 956)**

Replace:
```java
.url(photo.getStorageKey())
```
with:
```java
.url(photoUrlService.generateSignedUrl(
        photo.getStorageBucket().getBucketName(),
        photo.getStorageKey(),
        photo.getId()))
```

**Step 3: Inject `PhotoUrlService` into `HostCheckoutPhotoService`**

Same pattern — add as constructor dependency if not present.

**Step 4: Fix `HostCheckoutPhotoService.handleAcceptedPhoto()` (line 428)**

Replace:
```java
.url(storageKey)
```
with:
```java
.url(photoUrlService.generateSignedUrl("check-out-photos", storageKey, photo.getId()))
```

**Step 5: Fix `HostCheckoutPhotoService.toDTO()` (line 615)**

Replace:
```java
.url(photo.getStorageKey())
```
with:
```java
.url(photoUrlService.generateSignedUrl("check-out-photos", photo.getStorageKey(), photo.getId()))
```

**Step 6: Run tests**

Run: `./gradlew :apps:backend:test --tests "*CheckInPhoto*" --tests "*HostCheckoutPhoto*" -x :apps:frontend:build`
Expected: PASS (may need to mock `PhotoUrlService` in test classes)

**Step 7: Commit**

```bash
git add apps/backend/src/main/java/org/example/rentoza/booking/checkin/CheckInPhotoService.java
git add apps/backend/src/main/java/org/example/rentoza/booking/checkout/HostCheckoutPhotoService.java
git commit -m "fix(audit-H6): return signed URLs instead of raw storage keys in photo DTOs

- CheckInPhotoService.mapToDTO() now uses PhotoUrlService.generateSignedUrl()
- HostCheckoutPhotoService.toDTO() and handleAcceptedPhoto() use signed URLs
- Prevents raw Supabase storage keys from being exposed to frontend
- HIGH audit item H-6

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 14: H-7 — Add admin role validation in damage claim admin methods

**Severity:** HIGH — Any authenticated user could call admin approve/reject if they guess the endpoint

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/booking/dispute/DamageClaimService.java:336,375`

**Step 1: Add role check in `adminApprove()`**

After loading the user (line 344-345), add:

```java
User admin = userRepository.findById(adminUserId)
        .orElseThrow(() -> new ResourceNotFoundException("Admin korisnik nije pronađen"));

// H-7 FIX: Validate admin role (defense-in-depth — controller @PreAuthorize is first layer)
if (admin.getRole() != Role.ADMIN) {
    throw new AccessDeniedException("Samo administratori mogu odobriti prijave štete");
}
```

**Step 2: Add role check in `adminReject()`**

After loading the user (line 383-384), add:

```java
User admin = userRepository.findById(adminUserId)
        .orElseThrow(() -> new ResourceNotFoundException("Admin korisnik nije pronađen"));

// H-7 FIX: Validate admin role (defense-in-depth)
if (admin.getRole() != Role.ADMIN) {
    throw new AccessDeniedException("Samo administratori mogu odbiti prijave štete");
}
```

Add import if needed:
```java
import org.example.rentoza.user.Role;
```

**Step 3: Run tests**

Run: `./gradlew :apps:backend:test --tests "*DamageClaim*" -x :apps:frontend:build`
Expected: PASS

**Step 4: Commit**

```bash
git add apps/backend/src/main/java/org/example/rentoza/booking/dispute/DamageClaimService.java
git commit -m "fix(audit-H7): add admin role validation in damage claim admin methods

- adminApprove() and adminReject() now verify user.getRole() == ADMIN
- Defense-in-depth: controller @PreAuthorize is first layer, service check is second
- HIGH audit item H-7

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 15: H-8 — Fix DECIMAL precision in saga state

**Severity:** HIGH — DECIMAL(10,2) overflows at ~80M RSD; should be DECIMAL(19,2)

**Files:**
- Create: `apps/backend/src/main/resources/db/migration/V76__audit_fix_saga_decimal_precision.sql`
- Modify: `apps/backend/src/main/java/org/example/rentoza/booking/checkout/saga/CheckoutSagaState.java`

**Step 1: Write the Flyway migration**

```sql
-- V76: Audit H-8 — Widen DECIMAL precision on checkout_saga_state financial columns
-- DECIMAL(10,2) overflows at ~80M RSD. DECIMAL(19,2) supports up to ~10 quadrillion.

ALTER TABLE checkout_saga_state ALTER COLUMN extra_mileage_charge TYPE DECIMAL(19, 2);
ALTER TABLE checkout_saga_state ALTER COLUMN fuel_charge TYPE DECIMAL(19, 2);
ALTER TABLE checkout_saga_state ALTER COLUMN late_fee TYPE DECIMAL(19, 2);
ALTER TABLE checkout_saga_state ALTER COLUMN damage_claim_charge TYPE DECIMAL(19, 2);
ALTER TABLE checkout_saga_state ALTER COLUMN total_charges TYPE DECIMAL(19, 2);
ALTER TABLE checkout_saga_state ALTER COLUMN captured_amount TYPE DECIMAL(19, 2);
ALTER TABLE checkout_saga_state ALTER COLUMN released_amount TYPE DECIMAL(19, 2);
ALTER TABLE checkout_saga_state ALTER COLUMN remainder_amount TYPE DECIMAL(19, 2);
```

**Step 2: Update entity annotations**

In `CheckoutSagaState.java`, change all financial column precision annotations from `precision = 10` to `precision = 19`:

```java
@Column(name = "extra_mileage_charge", precision = 19, scale = 2)
@Column(name = "fuel_charge", precision = 19, scale = 2)
@Column(name = "late_fee", precision = 19, scale = 2)
@Column(name = "damage_claim_charge", precision = 19, scale = 2)
@Column(name = "total_charges", precision = 19, scale = 2)
@Column(name = "captured_amount", precision = 19, scale = 2)
@Column(name = "released_amount", precision = 19, scale = 2)
@Column(name = "remainder_amount", precision = 19, scale = 2)
```

**Step 3: Run migration and tests**

Run: `./gradlew :apps:backend:test --tests "*CheckoutSaga*" -x :apps:frontend:build`
Expected: PASS

**Step 4: Commit**

```bash
git add apps/backend/src/main/resources/db/migration/V76__audit_fix_saga_decimal_precision.sql
git add apps/backend/src/main/java/org/example/rentoza/booking/checkout/saga/CheckoutSagaState.java
git commit -m "fix(audit-H8): widen DECIMAL(10,2) to DECIMAL(19,2) in saga state

- Flyway migration V76 alters 8 financial columns to DECIMAL(19,2)
- Entity annotations updated to match (precision = 19)
- DECIMAL(10,2) overflows at ~80M RSD — insufficient for edge cases
- HIGH audit item H-8

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 16: H-9 — Add per-user-per-minute rate limit on photo upload endpoints

**Severity:** HIGH — Per-booking cap exists but user can spam uploads across bookings

**Files:**
- Modify: `apps/backend/src/main/resources/application-dev.properties`
- Modify: `apps/backend/src/main/resources/application-prod.properties`

**Step 1: Verify existing rate limiting configuration**

The codebase already has comprehensive rate limiting. Check that photo upload endpoints have a per-minute limit configured. Based on research, `application-dev.properties` already has `20 req/min` for photo uploads and `application-prod.properties` matches.

If the configuration already has per-user photo upload rate limits (20 req/min), this item may already be addressed. Verify by checking that the `RateLimitingFilter` applies to `/api/bookings/*/check-in/*/photos` paths.

If it's already configured, document it in a commit:

**Step 2: Commit**

```bash
git commit --allow-empty -m "docs(audit-H9): verify per-user photo upload rate limiting exists

- RateLimitingFilter already applies 20 req/min per-user on photo upload paths
- PhotoRateLimitService adds 30 uploads/10min per-user Caffeine cache layer
- Both application-dev.properties and application-prod.properties configured
- Per-booking cap (MAX_PHOTOS_PER_BOOKING=20) provides additional defense
- HIGH audit item H-9 — already addressed by existing infrastructure

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Execution Order

Execute tasks in this order (CRITICAL first, then HIGH):

1. **C-1** (Task 1) — One-line fix, zero risk
2. **H-3** (Task 10) — One-line fix, zero risk
3. **H-5** (Task 12) — Two-line fix, zero risk
4. **H-2** (Task 9) — Three-line fix, zero risk
5. **C-7** (Task 7) — Small fix, must re-throw exception
6. **C-5** (Task 5) — Small fix with BigDecimal cap
7. **C-6** (Task 6) — Small fix, add adminReviewRequired
8. **H-4** (Task 11) — Small validation addition
9. **H-7** (Task 14) — Small role check additions
10. **H-1** (Task 8) — Method call change
11. **C-2** (Task 2) — Moderate: add query + validation logic
12. **H-6** (Task 13) — Moderate: inject service, fix 3 locations
13. **C-3** (Task 3) — DB migration only
14. **H-8** (Task 15) — DB migration + entity annotations
15. **C-4** (Task 4) — Repository annotations
16. **H-9** (Task 16) — Verification only (likely already addressed)

---

## Final Verification

After all tasks are committed, run the full test suite:

```bash
./gradlew :apps:backend:test -x :apps:frontend:build
```

Verify all Flyway migrations apply cleanly:

```bash
./gradlew :apps:backend:flywayInfo
```

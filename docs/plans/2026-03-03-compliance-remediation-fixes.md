# Compliance Remediation Fixes — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix all Critical and Important findings from the consolidated verification report before merge/production.

**Architecture:** Fixes are grouped into three commits: (1) Critical fixes for merge-blocking issues, (2) Important backend fixes, (3) Important frontend + cleanup fixes. Each task is a surgical change with exact file paths and code.

**Tech Stack:** Java 21, Spring Boot 3.5, JPA/Hibernate, Flyway/PostgreSQL, Angular 19 standalone + TypeScript

---

## Task 1: C1 — Unify admin compliance gate with canApprove logic

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/admin/service/AdminCarService.java:410-441`

**Problem:** `buildComplianceIssues()` only checks car-level registration/insurance/tech dates + document verification timestamp. It does NOT check: (1) owner identity verification, (2) per-document PENDING status, (3) per-document expiry dates. The DTO's `canApprove` in `AdminCarReviewDetailDto.java:124-168` checks all of these, meaning a direct API call can still approve a non-compliant car.

**Step 1: Update buildComplianceIssues to match canApprove logic**

Replace the `buildComplianceIssues` method in `AdminCarService.java` (lines 410-441) with:

```java
private List<String> buildComplianceIssues(Car car) {
    List<String> issues = new ArrayList<>();
    LocalDate today = LocalDate.now();

    // 1. Owner identity must be verified
    if (car.getOwner() == null || !Boolean.TRUE.equals(car.getOwner().getIsIdentityVerified())) {
        issues.add("Owner identity not verified");
    }

    // 2. Registration must exist and not be expired (use !isAfter for consistency with DTO)
    if (car.getRegistrationExpiryDate() == null) {
        issues.add("Registration expiry date not set");
    } else if (!car.getRegistrationExpiryDate().isAfter(today)) {
        issues.add("Registration expired on " + car.getRegistrationExpiryDate());
    }

    // 3. Insurance must exist and not be expired
    if (car.getInsuranceExpiryDate() == null) {
        issues.add("Insurance expiry date not set");
    } else if (!car.getInsuranceExpiryDate().isAfter(today)) {
        issues.add("Insurance expired on " + car.getInsuranceExpiryDate());
    }

    // 4. Technical inspection must exist and not be expired
    if (car.getTechnicalInspectionExpiryDate() == null) {
        issues.add("Technical inspection expiry date not set");
    } else if (!car.getTechnicalInspectionExpiryDate().isAfter(today)) {
        issues.add("Technical inspection expired on " + car.getTechnicalInspectionExpiryDate());
    }

    // 5. Per-document verification: all documents must be verified (not PENDING)
    List<CarDocument> documents = documentService.getDocumentsForCarWithVerifiedBy(car.getId());
    for (CarDocument doc : documents) {
        if ("PENDING".equals(doc.getVerificationStatus())) {
            issues.add("Document not yet verified: " + doc.getDocumentType());
        }
        // 6. Per-document expiry check
        if (doc.getExpiryDate() != null && doc.getExpiryDate().isBefore(today)) {
            issues.add("Document expired: " + doc.getDocumentType() + " (expired " + doc.getExpiryDate() + ")");
        }
    }

    return issues;
}
```

This also fixes **I1** (date boundary inconsistency) by using `!isAfter(today)` — same semantics as the DTO.

**Step 2: Fix I11 — change blocked approval audit from CAR_REJECTED to correct action**

In `AdminCarService.java` line 150, change:
```java
AdminAction.CAR_REJECTED,
```
to:
```java
AdminAction.CAR_APPROVAL_BLOCKED,
```

If `CAR_APPROVAL_BLOCKED` doesn't exist in the `AdminAction` enum, add it. If the enum is a simple string-based approach, check the `AdminAction` class first. If adding a new constant is not feasible without migration risk, use a differentiated detail string in the existing log instead — change line 155 detail text from `"Approval blocked by compliance gate: "` to `"SYSTEM_BLOCKED: Compliance gate prevented approval: "`.

**Step 3: Update buildComplianceSnapshot to include owner verification**

In `AdminCarService.java` after line 458, add owner verification to the snapshot:

```java
snapshot.put("ownerIdentityVerified",
        car.getOwner() != null ? car.getOwner().getIsIdentityVerified() : false);
```

**Step 4: Run tests**

Run: `cd apps/backend && mvn test -pl . -Dtest=AdminCarServiceTest -Dsurefire.failIfNoSpecifiedTests=false`

**Step 5: Commit**

```bash
git add apps/backend/src/main/java/org/example/rentoza/admin/service/AdminCarService.java
```

---

## Task 2: C2 — Add RENTER_ACCEPTED status

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreementStatus.java`
- Modify: `apps/backend/src/main/resources/db/migration/V78__rental_agreement_infrastructure.sql:52`
- Modify: `apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreementService.java:147-153`
- Modify: `apps/frontend/src/app/core/services/booking.service.ts` (RentalAgreementDTO type)

**Problem:** When renter accepts first and owner hasn't, status stays PENDING — indistinguishable from untouched.

**Step 1: Add RENTER_ACCEPTED to enum**

In `RentalAgreementStatus.java`, add `RENTER_ACCEPTED` between `OWNER_ACCEPTED` and `FULLY_ACCEPTED`:

```java
public enum RentalAgreementStatus {
    PENDING,
    OWNER_ACCEPTED,
    RENTER_ACCEPTED,
    FULLY_ACCEPTED,
    EXPIRED,
    VOIDED
}
```

**Step 2: Update V78 CHECK constraint**

This is a new migration since V78 hasn't been applied to production yet. Update the CHECK constraint in `V78__rental_agreement_infrastructure.sql` line 52:

```sql
CONSTRAINT chk_ra_status CHECK (status IN ('PENDING', 'OWNER_ACCEPTED', 'RENTER_ACCEPTED', 'FULLY_ACCEPTED', 'EXPIRED', 'VOIDED'))
```

**Step 3: Update acceptAsRenter to set RENTER_ACCEPTED**

In `RentalAgreementService.java` line 152, change:

```java
        } else {
            // Renter accepted first, owner hasn't yet — stays PENDING
            log.info("Agreement accepted by renter (awaiting owner): bookingId={}", bookingId);
        }
```

to:

```java
        } else {
            agreement.setStatus(RentalAgreementStatus.RENTER_ACCEPTED);
            log.info("Agreement accepted by renter (awaiting owner): bookingId={}", bookingId);
        }
```

**Step 4: Update frontend DTO type**

In `apps/frontend/src/app/core/services/booking.service.ts`, update the status type in the `RentalAgreementDTO` interface to include `'RENTER_ACCEPTED'`:

```typescript
status: 'PENDING' | 'OWNER_ACCEPTED' | 'RENTER_ACCEPTED' | 'FULLY_ACCEPTED' | 'EXPIRED' | 'VOIDED';
```

**Step 5: Run tests**

Run: `cd apps/backend && mvn test`

---

## Task 3: C3 — Add DB-level immutability for acceptance evidence fields

**Files:**
- Modify: `apps/backend/src/main/resources/db/migration/V78__rental_agreement_infrastructure.sql:61-90`

**Problem:** The V78 trigger protects content_hash, agreement_version, etc., but acceptance evidence fields (owner_accepted_at, owner_ip, owner_user_agent, and renter equivalents) can be overwritten after being set.

**Step 1: Add acceptance evidence immutability to the trigger function**

In `V78__rental_agreement_infrastructure.sql`, within the `prevent_rental_agreement_immutable_update()` function, add after the `renter_user_id` check (after line 87):

```sql
    -- Acceptance evidence: once set, cannot be changed
    IF OLD.owner_accepted_at IS NOT NULL AND OLD.owner_accepted_at IS DISTINCT FROM NEW.owner_accepted_at THEN
        RAISE EXCEPTION 'rental_agreements.owner_accepted_at is immutable once set';
    END IF;
    IF OLD.owner_ip IS NOT NULL AND OLD.owner_ip IS DISTINCT FROM NEW.owner_ip THEN
        RAISE EXCEPTION 'rental_agreements.owner_ip is immutable once set';
    END IF;
    IF OLD.owner_user_agent IS NOT NULL AND OLD.owner_user_agent IS DISTINCT FROM NEW.owner_user_agent THEN
        RAISE EXCEPTION 'rental_agreements.owner_user_agent is immutable once set';
    END IF;
    IF OLD.renter_accepted_at IS NOT NULL AND OLD.renter_accepted_at IS DISTINCT FROM NEW.renter_accepted_at THEN
        RAISE EXCEPTION 'rental_agreements.renter_accepted_at is immutable once set';
    END IF;
    IF OLD.renter_ip IS NOT NULL AND OLD.renter_ip IS DISTINCT FROM NEW.renter_ip THEN
        RAISE EXCEPTION 'rental_agreements.renter_ip is immutable once set';
    END IF;
    IF OLD.renter_user_agent IS NOT NULL AND OLD.renter_user_agent IS DISTINCT FROM NEW.renter_user_agent THEN
        RAISE EXCEPTION 'rental_agreements.renter_user_agent is immutable once set';
    END IF;
```

Key difference from the core field immutability: these check `OLD.field IS NOT NULL` first — allowing the initial write (NULL → value) but blocking any subsequent change (value → different value).

---

## Task 4: C4 — Gate canCheckIn() on agreement status in frontend

**Files:**
- Modify: `apps/frontend/src/app/features/bookings/pages/booking-detail/booking-detail.component.ts:774-779`

**Problem:** `canCheckIn()` only checks booking status, not agreement acceptance status. Users can proceed to handshake even without accepted agreement.

**Step 1: Update canCheckIn to check agreement**

Replace `canCheckIn()` method (lines 774-779):

```typescript
canCheckIn(): boolean {
  const status = this.booking()?.status;
  const bookingStatusOk = status
    ? ['CONFIRMED', 'CHECK_IN_OPEN', 'HOST_SUBMITTED', 'GUEST_ACKNOWLEDGED'].includes(status)
    : false;
  if (!bookingStatusOk) return false;

  // Gate on agreement acceptance — both parties must accept before check-in
  const agreement = this.agreement();
  if (agreement && agreement.status !== 'FULLY_ACCEPTED') {
    return false;
  }
  return true;
}
```

Note: if `agreement` is null (legacy booking without agreement), we allow check-in to preserve backward compatibility. The backend feature flag provides the hard gate.

---

## Task 5: I2 — Change GET to POST for tax aggregation

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/admin/controller/AdminTaxController.java:44`

**Problem:** `GET /api/admin/tax/monthly-summary` calls `aggregateForPPPPD()` which creates/updates rows. Violates HTTP semantics.

**Step 1: Split into GET (read) and POST (aggregate)**

Change the existing endpoint from GET to POST and add a read-only GET:

```java
/**
 * Generate/update monthly tax withholding summaries for all owners.
 * Creates or updates summary rows — use POST since this mutates data.
 */
@PostMapping("/monthly-summary/aggregate")
public ResponseEntity<List<TaxWithholdingSummary>> aggregateMonthlySummary(
        @RequestParam @Min(2024) int year,
        @RequestParam @Min(1) @Max(12) int month) {

    log.info("[AdminTax] Aggregating monthly summary for {}-{}", year, month);
    List<TaxWithholdingSummary> summaries = taxWithholdingService.aggregateForPPPPD(year, month);
    return ResponseEntity.ok(summaries);
}

/**
 * Get existing monthly tax withholding summaries (read-only).
 */
@GetMapping("/monthly-summary")
public ResponseEntity<List<TaxWithholdingSummary>> getMonthlySummary(
        @RequestParam @Min(2024) int year,
        @RequestParam @Min(1) @Max(12) int month) {

    log.info("[AdminTax] Fetching monthly summary for {}-{}", year, month);
    List<TaxWithholdingSummary> summaries = summaryRepository
            .findByTaxPeriodYearAndTaxPeriodMonth(year, month);
    return ResponseEntity.ok(summaries);
}
```

---

## Task 6: I3 — Add @Transactional to markFiled

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/admin/controller/AdminTaxController.java:70-91`

**Problem:** markFiled does read-modify-save without transaction protection. TOCTOU race if two admins mark-file simultaneously.

**Step 1: Move markFiled logic to TaxWithholdingService**

Add to `TaxWithholdingService.java`:

```java
@Transactional
public TaxWithholdingSummary markFiled(Long summaryId, String reference) {
    TaxWithholdingSummary summary = summaryRepository.findById(summaryId)
            .orElseThrow(() -> new org.example.rentoza.exception.ResourceNotFoundException(
                    "Tax summary not found: " + summaryId));

    if (summary.isPpppdFiled()) {
        return summary; // Idempotent
    }

    summary.setPpppdFiled(true);
    summary.setPpppdFilingDate(LocalDate.now());
    if (reference != null && !reference.isBlank()) {
        summary.setPpppdReference(reference.trim());
    }

    TaxWithholdingSummary saved = summaryRepository.save(summary);
    log.info("[Tax] Monthly summary {} marked as PPPPD-filed (ref: {})", summaryId, reference);
    return saved;
}
```

Update `AdminTaxController.java` markFiled endpoint to delegate:

```java
@PostMapping("/monthly-summary/{id}/mark-filed")
public ResponseEntity<TaxWithholdingSummary> markFiled(
        @PathVariable Long id,
        @RequestParam(required = false) String reference) {

    TaxWithholdingSummary saved = taxWithholdingService.markFiled(id, reference);
    return ResponseEntity.ok(saved);
}
```

---

## Task 7: I4 — Fail loudly on null ownerType instead of defaulting to INDIVIDUAL

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/payment/TaxWithholdingService.java:65-66`

**Problem:** Null ownerType silently defaults to INDIVIDUAL (withholding applies). For a tax system, wrong withholding is worse than an error.

**Step 1: Change null handling**

Replace line 66:
```java
ledger.setOwnerTaxType(ownerType != null ? ownerType.name() : "INDIVIDUAL");
```
with:
```java
if (ownerType == null) {
    throw new IllegalStateException(
            "Owner type must be set before tax withholding can be calculated. Owner user ID: "
            + owner.getId());
}
ledger.setOwnerTaxType(ownerType.name());
```

---

## Task 8: I5 — Migrate CarResponseDTO to read listingStatus

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/car/dto/CarResponseDTO.java:148-151`

**Problem:** CarResponseDTO still reads `car.getApprovalStatus()`, not `car.getListingStatus()`.

**Step 1: Update to use listingStatus**

Replace lines 148-151:
```java
if (isOwner) {
    // Phase 5: Source from listingStatus for active logic
    this.approvalStatus = car.getApprovalStatus();
}
```
with:
```java
if (isOwner) {
    // Phase 5: listingStatus is source of truth — map to ApprovalStatus for API compat
    this.approvalStatus = car.getListingStatus() != null
        ? ApprovalStatus.valueOf(car.getListingStatus().name())
        : car.getApprovalStatus();
}
```

Note: This assumes `ListingStatus` and `ApprovalStatus` share the same enum constant names for the states that matter (APPROVED, PENDING_APPROVAL, etc). Verify this before implementation — if they don't share names, use a mapping method.

---

## Task 9: I6 + I7 — Frontend agreement error feedback + role check

**Files:**
- Modify: `apps/frontend/src/app/features/bookings/pages/booking-detail/booking-detail.component.ts:728-741` (acceptAgreement)
- Modify: `apps/frontend/src/app/features/bookings/pages/booking-detail/booking-detail.component.ts:720-726` (canAcceptAgreement)

**Problem I6:** acceptAgreement error handler silently clears spinner with no user feedback.
**Problem I7:** canAcceptAgreement doesn't check if the current user already accepted.

**Step 1: Add error feedback to acceptAgreement**

Replace the error handler in `acceptAgreement()` (lines 738-740):
```typescript
error: () => {
    this.isAcceptingAgreement.set(false);
},
```
with:
```typescript
error: (err) => {
    this.isAcceptingAgreement.set(false);
    this.error.set(err.error?.message || 'Prihvatanje ugovora nije uspelo. Pokušajte ponovo.');
},
```

**Step 2: Fix canAcceptAgreement role check**

Replace `canAcceptAgreement()` (lines 720-726):
```typescript
canAcceptAgreement(): boolean {
  const a = this.agreement();
  if (!a || a.status === 'FULLY_ACCEPTED') return false;

  const booking = this.booking();
  const currentUserId = this.authService.currentUserId();
  if (!booking || !currentUserId) return false;

  // Check if current user is owner and hasn't accepted yet
  const isOwner = booking.carOwnerId === currentUserId;
  if (isOwner) return !a.ownerAccepted;

  // Check if current user is renter and hasn't accepted yet
  const isRenter = booking.renterId === currentUserId;
  if (isRenter) return !a.renterAccepted;

  return false;
}
```

Note: Verify the exact property names used for `carOwnerId`, `renterId`, and `currentUserId` from the booking model and auth service before implementing.

---

## Task 10: I8 — Use TreeMap for hash determinism

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreementService.java:180,197,226`

**Problem:** `LinkedHashMap` insertion order is not guaranteed to be sorted for nested maps. `ORDER_MAP_ENTRIES_BY_KEYS` doesn't recursively sort nested maps.

**Step 1: Replace LinkedHashMap with TreeMap in snapshot builders and hash computation**

In `buildVehicleSnapshot()` line 180:
```java
Map<String, Object> snapshot = new TreeMap<>();
```

In `buildTermsSnapshot()` line 198:
```java
Map<String, Object> terms = new TreeMap<>();
```

In `computeContentHash()` line 226:
```java
Map<String, Object> canonical = new TreeMap<>();
```

Add import:
```java
import java.util.TreeMap;
```

---

## Task 11: I9 — Add findByOwnerUserIdAndStatus repository method

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreementRepository.java`

**Step 1: Add the missing method**

Add after line 22:
```java
List<RentalAgreement> findByOwnerUserIdAndStatus(Long ownerUserId, RentalAgreementStatus status);
```

---

## Task 12: I10 — Remove single-transaction backfill

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreementBackfillService.java:40`

**Problem:** `@Transactional` on the entire backfill means one big transaction. Lock contention risk.

**Step 1: Remove @Transactional from backfillAgreements**

Remove `@Transactional` from line 40. The inner `agreementService.generateAgreement()` already runs in its own transaction.

```java
public BackfillResult backfillAgreements() {
```

Note: `findByStatusIn` and `findBookingIdsWithAgreements` are read-only queries that run fine without an outer transaction.

---

## Task 13: I12 — Fix stale comment in CarService

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/car/CarService.java:710`

**Step 1: Update the Javadoc**

Replace line 710:
```java
     * - approvalStatus = APPROVED
```
with:
```java
     * - listingStatus = APPROVED
```

---

## Task 14: S2 — Add @Param annotation to findUnfiledForPeriod

**Files:**
- Modify: `apps/backend/src/main/java/org/example/rentoza/payment/TaxWithholdingSummaryRepository.java:20-21`

**Step 1: Add @Param annotations**

Replace:
```java
@Query("SELECT t FROM TaxWithholdingSummary t WHERE t.taxPeriodYear = :year AND t.taxPeriodMonth = :month AND t.ppppdFiled = false")
List<TaxWithholdingSummary> findUnfiledForPeriod(int year, int month);
```
with:
```java
@Query("SELECT t FROM TaxWithholdingSummary t WHERE t.taxPeriodYear = :year AND t.taxPeriodMonth = :month AND t.ppppdFiled = false")
List<TaxWithholdingSummary> findUnfiledForPeriod(@Param("year") int year, @Param("month") int month);
```

---

## Task 15: Run full test suite and commit

**Step 1: Run backend tests**

```bash
cd apps/backend && mvn test
```

**Step 2: Fix any test failures from intentional behavior changes**

Expected changes:
- Tests referencing `buildComplianceIssues` behavior may need owner identity verification setup
- Tests checking PENDING status after renter acceptance need RENTER_ACCEPTED
- Tax tests with null ownerType need explicit ownerType set on test User objects

**Step 3: Commit all changes**

```bash
git add -A
git commit -m "fix(compliance): remediate verification report findings (C1-C4, I1-I12)

Critical fixes:
- C1: Unify admin compliance gate with DTO canApprove logic
- C2: Add RENTER_ACCEPTED agreement status
- C3: DB-level immutability for acceptance evidence fields
- C4: Gate canCheckIn() on agreement status in frontend

Important fixes:
- I1: Align date boundary checks to use !isAfter consistently
- I2: Split GET/POST for tax aggregation endpoint
- I3: Add @Transactional to markFiled via service layer
- I4: Fail on null ownerType instead of defaulting to INDIVIDUAL
- I5: Migrate CarResponseDTO to read listingStatus
- I6: Add error feedback on agreement accept failure
- I7: Fix canAcceptAgreement role check
- I8: Use TreeMap for hash determinism
- I9: Add findByOwnerUserIdAndStatus repository method
- I10: Remove single-transaction backfill wrapper
- I11: Differentiate compliance-blocked from admin-rejected audit
- I12: Fix stale Javadoc in CarService

Co-Authored-By: Claude <noreply@anthropic.com>"
```

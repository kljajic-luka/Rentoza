# Handshake Flow Defect Fix — Design Document

**Date:** 2026-02-28
**Author:** Claude Code (Sonnet 4.6)
**Severity:** P0 — production trip-start failure
**Scope:** Option B — targeted correctness + observability + tests (no service consolidation)

---

## 1. Forensic Evidence (preserved as facts)

For booking 58 on 2026-02-28:
- Backend received **multiple** host handshake requests → recorded **multiple** `HANDSHAKE_HOST_CONFIRMED` events.
- **No** `HANDSHAKE_GUEST_CONFIRMED` event was recorded.
- **No** `TRIP_STARTED` event was recorded.
- Guest condition acknowledgment **did** succeed.

---

## 2. Root Cause Catalogue

| # | File | Line(s) | Defect | Fix |
|---|---|---|---|---|
| 1 | `CheckInService.java` | 650 | `handshakeCompletedAt != null && isHostHandshakeConfirmed` — guard requires trip to have already started; never fires on partial host-only confirmation | Replace with `isHostHandshakeConfirmed(booking)` only |
| 1b | `CheckInCommandService.java` | 526 | Same broken guard in CQRS path | Same fix |
| 2 | `CheckInService.mapToStatusDTO` | 1360–1420 | `hostConfirmedHandshake`, `guestConfirmedHandshake`, `handshakeComplete`, `guestConditionAcknowledged`, `canStartTrip`, `lastUpdated` never set — all serialise as `false`/`null` | Compute from event queries and booking status |
| 3 | `CheckInController.java` | 432, 242, 324, 373 | Cached idempotency replay returns `.build()` (empty body) | Deserialise `result.getResponseBody()` → `CheckInStatusDTO` and return it |
| 4 | `check-in.service.ts` | 773–782 | Optimistic `status:'IN_TRIP'` fires whenever `handshakeReady` is true — misleads after one-sided confirm | Remove optimistic IN_TRIP block; server response drives state |
| 5 | `handshake.component.ts` | 87–107 | Status card reads `hostCheckInComplete`/`guestCheckInComplete` (pre-handshake phase, always `true` in handshake screen) | Swap to `hostConfirmedHandshake`/`guestConfirmedHandshake` |
| 5b | `handshake.component.ts` | 675–681 | `isWaitingForOther` uses `guestCheckInComplete` (always true) so waiting state never shows | Use server-side `hostConfirmedHandshake`/`guestConfirmedHandshake` |
| 5c | `handshake.component.ts` | 690–700 | `canConfirm` only checks local `_isConfirmed` signal — resets on component re-creation, allows repeat confirm | Also gate on server-side per-actor flag |
| 6 | `check-in.model.ts` | 124–222 | TypeScript interface missing `hostConfirmedHandshake`, `guestConfirmedHandshake`, `canStartTrip`, `lastUpdated` | Add optional typed fields |

---

## 3. Architecture: No Service Consolidation

The legacy `CheckInService` and CQRS `CheckInCommandService` both expose `confirmHandshake`. The `CheckInController` calls `checkInService.confirmHandshake()` (legacy path). This design **does not** consolidate them — both paths receive the same targeted fix independently. Consolidation is a separate tech-debt task.

---

## 4. Data Flow (corrected)

```
Client (host)                   CheckInController               CheckInService
   │                                   │                               │
   │  POST /handshake                  │                               │
   │  X-Idempotency-Key: K1 ──────────►│                               │
   │                                   │ idempotency miss              │
   │                                   │ markProcessing(K1)            │
   │                                   │──────────────────────────────►│
   │                                   │                               │ findByIdWithLock()
   │                                   │                               │ status==CHECK_IN_COMPLETE ✓
   │                                   │                               │
   │                                   │                               │ isHostHandshakeConfirmed? NO
   │                                   │                               │ → recordEvent(HANDSHAKE_HOST_CONFIRMED)
   │                                   │                               │
   │                                   │                               │ isGuestHandshakeConfirmed? NO
   │                                   │                               │ → skip startTrip
   │                                   │                               │
   │                                   │                               │ mapToStatusDTO:
   │                                   │                               │   hostConfirmedHandshake=true  ← NEW
   │                                   │                               │   guestConfirmedHandshake=false ← NEW
   │                                   │                               │   handshakeComplete=false      ← NEW
   │                                   │                               │   status=CHECK_IN_COMPLETE
   │                                   │◄──────────────────────────────│
   │                                   │ storeSuccess(K1, dto)        │
   │◄──────────────────────────────────│                               │
   │  200 { hostConfirmedHandshake:true,                               │
   │         guestConfirmedHandshake:false }                           │
   │                                   │                               │
   │  POST /handshake (retry)           │                               │
   │  X-Idempotency-Key: K1 ──────────►│                               │
   │                                   │ idempotency HIT               │
   │                                   │ → deserialise cached dto ← FIX│
   │◄──────────────────────────────────│                               │
   │  200 { hostConfirmedHandshake:true, ... }  (same body)            │

Client (guest)                                                         │
   │  POST /handshake                  │                               │
   │─────────────────────────────────►│                               │
   │                                   │──────────────────────────────►│
   │                                   │                               │ isHostHandshakeConfirmed? YES ← already there
   │                                   │                               │ isGuestHandshakeConfirmed? NO
   │                                   │                               │ → recordEvent(HANDSHAKE_GUEST_CONFIRMED)
   │                                   │                               │ → hostConfirmed && guestConfirmed → startTrip()
   │                                   │                               │   booking.status = IN_TRIP
   │                                   │                               │   recordEvent(TRIP_STARTED)
   │                                   │◄──────────────────────────────│
   │◄──────────────────────────────────│                               │
   │  200 { handshakeComplete:true,    │                               │
   │         status:'IN_TRIP' }        │                               │
```

---

## 5. Backend Changes (A, B, D, E)

### 5.1  Fix idempotency guard — both paths (Defect 1 / 1b)

**`CheckInService.java:650`** — change:
```java
// BEFORE (broken):
if (booking.getHandshakeCompletedAt() != null && isHostHandshakeConfirmed(booking)) {

// AFTER (correct):
if (isHostHandshakeConfirmed(booking)) {
```

**`CheckInCommandService.java:526`** — same change.

Both guest paths are already correct (`if (!isGuestHandshakeConfirmed(booking))`).

### 5.2  Populate handshake fields in `mapToStatusDTO` (Defect 2)

Add to the builder in `CheckInService.mapToStatusDTO` (~line 1360):
```java
.hostConfirmedHandshake(isHostHandshakeConfirmed(booking))
.guestConfirmedHandshake(isGuestHandshakeConfirmed(booking))
.handshakeComplete(booking.getStatus() == BookingStatus.IN_TRIP)
.guestConditionAcknowledged(booking.getGuestCheckInCompletedAt() != null)
.canStartTrip(booking.getStatus() == BookingStatus.CHECK_IN_COMPLETE)
.lastUpdated(LocalDateTime.now(SERBIA_ZONE))
```

### 5.3  Return cached body in controller (Defect 3)

All four `.build()` calls in `CheckInController` (lines 242, 324, 373, 432):
```java
// BEFORE:
return ResponseEntity.status(result.getHttpStatus()).build();

// AFTER (using injected ObjectMapper):
try {
    CheckInStatusDTO dto = objectMapper.readValue(
        result.getResponseBody(), CheckInStatusDTO.class);
    return ResponseEntity.status(result.getHttpStatus()).body(dto);
} catch (Exception ex) {
    log.warn("[CheckIn] Cached body deserialisation failed, returning empty", ex);
    return ResponseEntity.status(result.getHttpStatus()).build();
}
```

`ObjectMapper` is already available in the Spring context; add constructor injection to controller.

### 5.4  Structured logging + Micrometer counters (E)

In `CheckInService.confirmHandshake` and `CheckInCommandService.confirmHandshake`, at handshake decision points:

```java
// On host confirm attempt:
log.info("[handshake] attempted role=HOST bookingId={} hostConfirmed={} guestConfirmed={} status={}",
    booking.getId(), hostAlreadyConfirmed, guestConfirmed, booking.getStatus());
handshakeAttemptedCounter.withTag("role","HOST").increment();

// On new confirmation recorded:
handshakeConfirmedCounter.withTag("role","HOST").increment();

// After record, if other party not yet confirmed:
if (!guestConfirmed) {
    handshakeWaitingOtherPartyCounter.increment();
}

// On startTrip():
handshakeTripStartedCounter.increment();
log.info("[handshake] trip_started bookingId={} actorRole={} hostConfirmed=true guestConfirmed=true",
    booking.getId(), actorRole);
```

New counters registered in constructor:
- `handshake.attempted` tagged `role` (HOST/GUEST)
- `handshake.confirmed` tagged `role`
- `handshake.waiting_other_party`
- `handshake.trip_started`

---

## 6. Frontend Changes (C, F-frontend)

### 6.1  TypeScript model (`check-in.model.ts`)

Add to `CheckInStatusDTO` interface:
```typescript
hostConfirmedHandshake?: boolean;
guestConfirmedHandshake?: boolean;
canStartTrip?: boolean;
lastUpdated?: string;
```

### 6.2  Remove optimistic IN_TRIP (`check-in.service.ts:773–782`)

Delete the entire optimistic update block. The `tap` callback already sets state from the server response — that is sufficient and truthful.

### 6.3  Fix `handshake.component.ts`

**Status card** (lines 87–107): swap field references:
```html
<!-- BEFORE -->
[class.completed]="status?.hostCheckInComplete"
{{ status?.hostCheckInComplete ? 'Završio' : 'Čeka potvrdu' }}

<!-- AFTER -->
[class.completed]="status?.hostConfirmedHandshake"
{{ status?.hostConfirmedHandshake ? 'Potvrdio primopredaju' : 'Čeka potvrdu' }}
```
Same swap for guest side.

**`isWaitingForOther` computed** (lines 675–681): server-driven only:
```typescript
isWaitingForOther = computed(() => {
  if (!this.status) return false;
  if (this.status.host) {
    return this.status.hostConfirmedHandshake === true
        && this.status.guestConfirmedHandshake !== true;
  }
  return this.status.guestConfirmedHandshake === true
      && this.status.hostConfirmedHandshake !== true;
});
```

**`canConfirm` computed** (lines 690–700): gate on server-confirmed state:
```typescript
canConfirm = computed(() => {
  if (!this.status) return false;
  const alreadyConfirmed = this.status.host
    ? this.status.hostConfirmedHandshake === true
    : this.status.guestConfirmedHandshake === true;
  if (alreadyConfirmed) return false;
  if (this.status.host
      && this.isLicenseVerificationRequired()
      && !this.status.licenseVerifiedInPerson) return false;
  return !this._isConfirmed() && !this.checkInService.isLoading();
});
```

**`roleInstructions` computed**: add waiting-for-other branch:
```typescript
roleInstructions = computed(() => {
  if (this.isWaitingForOther()) {
    return this.status?.host
      ? 'Vaša potvrda je primljena. Čekamo gosta.'
      : 'Vaša potvrda je primljena. Čekamo domaćina.';
  }
  return this.status?.host
    ? 'Kada predate ključeve gostu, prevucite za potvrdu'
    : 'Kada primite ključeve, prevucite za potvrdu';
});
```

**Disable swipe when already confirmed** (`onTouchStart`, `onMouseDown`): `canConfirm()` already gates these — no additional change needed once `canConfirm` is fixed.

---

## 7. Tests (F)

### 7.1  Backend unit tests (new class: `HandshakeCorrectnessTest`)

Using the same Mockito setup pattern as the existing `HandshakeIdempotencyTest`.

| # | Scenario | Assertions |
|---|---|---|
| T1 | Host-only confirm | Exactly 1 `HANDSHAKE_HOST_CONFIRMED` event; status stays `CHECK_IN_COMPLETE`; no `TRIP_STARTED` |
| T2 | Host retry (same host, second call) | Still exactly 1 `HANDSHAKE_HOST_CONFIRMED` event (idempotent); no additional event |
| T3 | Guest then host | After host call: exactly 1 each of `HANDSHAKE_GUEST_CONFIRMED` and `HANDSHAKE_HOST_CONFIRMED`; `TRIP_STARTED` emitted once; status = `IN_TRIP` |
| T4 | Host then guest | Symmetric: same invariants as T3 |
| T5 | `mapToStatusDTO` fields | After host-only: `hostConfirmedHandshake=true`, `guestConfirmedHandshake=false`, `handshakeComplete=false`, `canStartTrip=true` |

### 7.2  Controller test (idempotency cached body)

Extend existing `CheckInControllerTimingBlockedTest` or add `HandshakeControllerIdempotencyTest`:

| # | Scenario | Assertions |
|---|---|---|
| T6 | Retry with same idempotency key | Returns `200` with identical non-null body (not empty); event count unchanged |

### 7.3  Frontend spec tests

In `handshake.component.spec.ts` (new or extend):

| # | Scenario | Assertions |
|---|---|---|
| T7 | `hostConfirmedHandshake=true`, `guestConfirmedHandshake=false` | Status card shows host=confirmed, guest=pending; swipe disabled for host; `isWaitingForOther()=true` |
| T8 | `hostConfirmedHandshake=false`, `guestConfirmedHandshake=false` | Both pending; `canConfirm()=true` for both roles |
| T9 | After confirm → server responds with `handshakeComplete=false` | No optimistic IN_TRIP shown; status stays at handshake screen |

---

## 8. Non-Goals

- Service consolidation (CheckInCommandService absorbing CheckInService handshake path) — separate backlog item.
- WebSocket push on handshake partial confirmation — current polling interval handles this adequately.
- Any change to payment capture, no-show, or geofence logic.

---

## 9. Rollout Checklist

1. **Feature flag**: no new flag needed — fixes are unconditional correctness corrections.
2. **DB migration**: none.
3. **Deploy order**: backend first, then frontend (backend changes are backward-compatible).
4. **Smoke test**: after deploy, manually perform host-only confirm on staging → verify `hostConfirmedHandshake=true`, `guestConfirmedHandshake=false` in response; verify no duplicate events in audit log.
5. **Monitoring queries**:
   - `SELECT COUNT(*) FROM check_in_event WHERE type='HANDSHAKE_HOST_CONFIRMED' GROUP BY booking_id HAVING COUNT(*) > 1` — should return 0 new rows.
   - Grafana: `handshake.trip_started` rate should match `handshake.confirmed{role=GUEST}` rate within 5 min.
6. **Rollback**: revert frontend deploy first (no state mutation); then revert backend if needed. Duplicate events already in DB for booking 58 are cosmetically present but harmless (trip never started, idempotency guard now works correctly going forward).

---

## 10. Files Changed Summary

| File | Change |
|------|--------|
| `CheckInService.java` | Fix host guard (line 650); populate 6 DTO fields in `mapToStatusDTO`; add counters/logs |
| `CheckInCommandService.java` | Fix host guard (line 526); add counters/logs |
| `CheckInController.java` | Return cached body at 4 idempotency replay points; inject `ObjectMapper` |
| `check-in.service.ts` | Remove optimistic IN_TRIP block |
| `handshake.component.ts` | Fix status card fields; fix `isWaitingForOther`; fix `canConfirm`; update `roleInstructions` |
| `check-in.model.ts` | Add 4 missing optional fields to interface |
| `HandshakeCorrectnessTest.java` | New: T1–T5 backend unit tests |
| `HandshakeControllerIdempotencyTest.java` | New/extended: T6 controller test |
| `handshake.component.spec.ts` | New/extended: T7–T9 frontend tests |

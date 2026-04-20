# Handshake Flow Defect Fix — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix all six defect areas in the two-party handshake flow so one-sided confirms never appear complete, retries produce no duplicate events, and the API returns truthful per-party state to all clients.

**Architecture:** Surgical targeted fixes across two backend service classes, one controller, and three frontend files. No service consolidation, no schema changes. TDD throughout — write the failing test, verify it fails, implement the fix, verify it passes, commit.

**Tech Stack:** Java 21 / Spring Boot 3 / Micrometer (backend), Angular 17+ / Jasmine / TypeScript (frontend), Maven (backend build), npm (frontend build).

---

## Quick reference: key file paths

| Symbol | Absolute path |
|--------|--------------|
| `SVC` | `apps/backend/src/main/java/org/example/rentoza/booking/checkin/CheckInService.java` |
| `CMD` | `apps/backend/src/main/java/org/example/rentoza/booking/checkin/cqrs/CheckInCommandService.java` |
| `CTL` | `apps/backend/src/main/java/org/example/rentoza/booking/checkin/CheckInController.java` |
| `MODEL` | `apps/frontend/src/app/core/models/check-in.model.ts` |
| `FE_SVC` | `apps/frontend/src/app/core/services/check-in.service.ts` |
| `COMP` | `apps/frontend/src/app/features/bookings/check-in/handshake.component.ts` |
| `T_BACK` | `apps/backend/src/test/java/org/example/rentoza/booking/checkin/HandshakeCorrectnessTest.java` |
| `T_CTL` | `apps/backend/src/test/java/org/example/rentoza/booking/checkin/HandshakeControllerIdempotencyTest.java` |
| `T_FE` | `apps/frontend/src/app/features/bookings/check-in/handshake.component.spec.ts` |

---

## Task 1 — Write failing backend unit tests (T1–T5)

**Files:**
- Create: `apps/backend/src/test/java/org/example/rentoza/booking/checkin/HandshakeCorrectnessTest.java`

### Step 1: Create the test file

```java
package org.example.rentoza.booking.checkin;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.dto.CheckInStatusDTO;
import org.example.rentoza.booking.checkin.dto.HandshakeConfirmationDTO;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.booking.photo.PhotoUrlService;
import org.example.rentoza.car.Car;
import org.example.rentoza.config.FeatureFlags;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.payment.BookingPaymentService;
import org.example.rentoza.security.LockboxEncryptionService;
import org.example.rentoza.user.RenterVerificationService;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Handshake Correctness: idempotency guard, DTO fields, trip-start invariants")
class HandshakeCorrectnessTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private CheckInEventService eventService;
    @Mock private CheckInPhotoRepository photoRepository;
    @Mock private GuestCheckInPhotoRepository guestPhotoRepository;
    @Mock private GeofenceService geofenceService;
    @Mock private NotificationService notificationService;
    @Mock private LockboxEncryptionService lockboxEncryptionService;
    @Mock private RenterVerificationService renterVerificationService;
    @Mock private FeatureFlags featureFlags;
    @Mock private CheckInValidationService validationService;
    @Mock private DamageClaimRepository damageClaimRepository;
    @Mock private UserRepository userRepository;
    @Mock private PhotoUrlService photoUrlService;
    @Mock private BookingPaymentService bookingPaymentService;

    private CheckInService checkInService;
    private Booking booking;
    private User host;
    private User renter;

    @BeforeEach
    void setUp() {
        checkInService = new CheckInService(
                bookingRepository, eventService, photoRepository,
                guestPhotoRepository, geofenceService, notificationService,
                lockboxEncryptionService, renterVerificationService,
                featureFlags, validationService, damageClaimRepository,
                userRepository, new SimpleMeterRegistry(),
                photoUrlService, bookingPaymentService
        );
        // Disable @Value-injected license verification so trip-start tests don't require it
        ReflectionTestUtils.setField(checkInService, "licenseVerificationEnabled", false);
        ReflectionTestUtils.setField(checkInService, "licenseVerificationRequired", false);

        host = new User(); host.setId(100L);
        renter = new User(); renter.setId(200L);
        Car car = new Car(); car.setId(1L); car.setOwner(host);

        booking = new Booking();
        booking.setId(1L);
        booking.setCar(car);
        booking.setRenter(renter);
        booking.setCheckInSessionId("session-abc");
        booking.setCheckInEvents(new ArrayList<>());
        booking.setCheckInPhotos(new ArrayList<>());
        booking.setStatus(BookingStatus.CHECK_IN_COMPLETE);

        // Common stubs
        when(bookingRepository.findByIdWithLock(1L)).thenReturn(Optional.of(booking));
        when(photoRepository.findByBookingId(1L)).thenReturn(List.of());
        when(geofenceService.getDefaultRadiusMeters()).thenReturn(100);
        when(featureFlags.isDualPartyPhotosRequiredForHandshake()).thenReturn(false);
        when(featureFlags.isStrictCheckinEnabled()).thenReturn(false);
    }

    // ---- helper ----
    private HandshakeConfirmationDTO dto(Long bookingId, boolean confirmed) {
        HandshakeConfirmationDTO dto = new HandshakeConfirmationDTO();
        dto.setBookingId(bookingId);
        dto.setConfirmed(confirmed);
        return dto;
    }

    // ---- T1: host-only confirm records exactly one HOST event, status stays CHECK_IN_COMPLETE ----

    @Test
    @DisplayName("T1: host-only confirm → exactly one HOST event, no TRIP_STARTED, status stays CHECK_IN_COMPLETE")
    void hostOnlyConfirm_recordsOneHostEvent_noTripStart() {
        when(eventService.hasEventOfType(1L, CheckInEventType.HANDSHAKE_HOST_CONFIRMED))
                .thenReturn(false, true); // false on guard check, true after recording
        when(eventService.hasEventOfType(1L, CheckInEventType.HANDSHAKE_GUEST_CONFIRMED))
                .thenReturn(false);

        CheckInStatusDTO result = checkInService.confirmHandshake(dto(1L, true), 100L);

        assertThat(result).isNotNull();
        // Exactly one HOST event recorded
        verify(eventService, times(1)).recordEvent(
                any(), any(), eq(CheckInEventType.HANDSHAKE_HOST_CONFIRMED),
                eq(100L), eq(CheckInActorRole.HOST), any());
        // No TRIP_STARTED
        verify(eventService, never()).recordEvent(
                any(), any(), eq(CheckInEventType.TRIP_STARTED), any(), any(), any());
        // Booking still CHECK_IN_COMPLETE (not IN_TRIP)
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CHECK_IN_COMPLETE);
    }

    // ---- T2: host retry → no duplicate HOST event ----

    @Test
    @DisplayName("T2: host retry when already confirmed → zero additional HOST events")
    void hostRetry_whenAlreadyConfirmed_noAdditionalEvent() {
        // Host already confirmed (simulate prior confirmation in DB)
        when(eventService.hasEventOfType(1L, CheckInEventType.HANDSHAKE_HOST_CONFIRMED))
                .thenReturn(true);
        when(eventService.hasEventOfType(1L, CheckInEventType.HANDSHAKE_GUEST_CONFIRMED))
                .thenReturn(false);

        checkInService.confirmHandshake(dto(1L, true), 100L);

        // No HOST event should be recorded on a retry
        verify(eventService, never()).recordEvent(
                any(), any(), eq(CheckInEventType.HANDSHAKE_HOST_CONFIRMED),
                any(), any(), any());
    }

    // ---- T3: host already confirmed → guest confirms → trip starts exactly once ----

    @Test
    @DisplayName("T3: host pre-confirmed, guest confirms → TRIP_STARTED exactly once, status = IN_TRIP")
    void guestConfirmsAfterHost_tripStartsOnce() {
        // Host is already confirmed; guest is not yet confirmed
        when(eventService.hasEventOfType(1L, CheckInEventType.HANDSHAKE_HOST_CONFIRMED))
                .thenReturn(true);
        when(eventService.hasEventOfType(1L, CheckInEventType.HANDSHAKE_GUEST_CONFIRMED))
                .thenReturn(false, true); // false on guard check, true on trip-start check

        CheckInStatusDTO result = checkInService.confirmHandshake(dto(1L, true), 200L); // guest

        assertThat(result).isNotNull();
        verify(eventService, times(1)).recordEvent(
                any(), any(), eq(CheckInEventType.HANDSHAKE_GUEST_CONFIRMED),
                eq(200L), eq(CheckInActorRole.GUEST), any());
        verify(eventService, times(1)).recordEvent(
                any(), any(), eq(CheckInEventType.TRIP_STARTED), any(), any(), any());
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.IN_TRIP);
    }

    // ---- T4: guest already confirmed → host confirms → trip starts exactly once ----

    @Test
    @DisplayName("T4: guest pre-confirmed, host confirms → TRIP_STARTED exactly once, status = IN_TRIP")
    void hostConfirmsAfterGuest_tripStartsOnce() {
        // Guest is already confirmed; host is not yet confirmed
        when(eventService.hasEventOfType(1L, CheckInEventType.HANDSHAKE_HOST_CONFIRMED))
                .thenReturn(false, true); // false on guard check, true on trip-start check
        when(eventService.hasEventOfType(1L, CheckInEventType.HANDSHAKE_GUEST_CONFIRMED))
                .thenReturn(true);

        CheckInStatusDTO result = checkInService.confirmHandshake(dto(1L, true), 100L); // host

        assertThat(result).isNotNull();
        verify(eventService, times(1)).recordEvent(
                any(), any(), eq(CheckInEventType.HANDSHAKE_HOST_CONFIRMED),
                eq(100L), eq(CheckInActorRole.HOST), any());
        verify(eventService, times(1)).recordEvent(
                any(), any(), eq(CheckInEventType.TRIP_STARTED), any(), any(), any());
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.IN_TRIP);
    }

    // ---- T5: mapToStatusDTO populates per-party handshake fields after host-only confirm ----

    @Test
    @DisplayName("T5: after host-only confirm, DTO has hostConfirmedHandshake=true, guestConfirmedHandshake=false, handshakeComplete=false, canStartTrip=true")
    void dtoFields_afterHostOnlyConfirm() {
        when(eventService.hasEventOfType(1L, CheckInEventType.HANDSHAKE_HOST_CONFIRMED))
                .thenReturn(false, true, true); // guard=false → record; trip-check=true; mapToStatusDTO=true
        when(eventService.hasEventOfType(1L, CheckInEventType.HANDSHAKE_GUEST_CONFIRMED))
                .thenReturn(false, false); // trip-check=false; mapToStatusDTO=false

        CheckInStatusDTO result = checkInService.confirmHandshake(dto(1L, true), 100L);

        assertThat(result.isHostConfirmedHandshake()).isTrue();
        assertThat(result.isGuestConfirmedHandshake()).isFalse();
        assertThat(result.isHandshakeComplete()).isFalse();
        assertThat(result.isCanStartTrip()).isTrue();
        assertThat(result.isGuestConditionAcknowledged()).isFalse(); // booking has no guestCheckInCompletedAt
        assertThat(result.getLastUpdated()).isNotNull();
    }
}
```

### Step 2: Run to verify tests FAIL

```bash
cd apps/backend
./mvnw test -pl . -Dtest=HandshakeCorrectnessTest -q 2>&1 | tail -30
```

Expected: compilation errors (`isHostConfirmedHandshake()`, `isGuestConfirmedHandshake()`, `isCanStartTrip()`, `isHandshakeComplete()`, `isGuestConditionAcknowledged()`, `getLastUpdated()` not found on `CheckInStatusDTO`) or test failures.

> **If tests compile but T5 assertions pass:** the DTO fields were already populated — skip Task 2's DTO section and only apply the guard fix.

---

## Task 2 — Fix `CheckInService`: host guard + `mapToStatusDTO` fields

**Files:**
- Modify: `SVC` (lines 650, 1360–1420)

### Step 1: Fix the host idempotency guard

In `SVC`, find line 650:

```java
// BEFORE (broken — requires trip to have already started):
if (booking.getHandshakeCompletedAt() != null && isHostHandshakeConfirmed(booking)) {
    log.debug("[CheckIn] Host already confirmed handshake for booking {}", dto.getBookingId());
} else {
    eventService.recordEvent(
        booking,
        booking.getCheckInSessionId(),
        CheckInEventType.HANDSHAKE_HOST_CONFIRMED,
        userId,
        CheckInActorRole.HOST,
        Map.of(
            "confirmedAt", Instant.now().toString(),
            "verifiedPhysicalId", dto.getHostVerifiedPhysicalId() != null
                ? dto.getHostVerifiedPhysicalId() : false
        )
    );
}
```

Replace the entire `if (isHost)` block (lines ~649–665) with:

```java
// Process host confirmation
if (isHost) {
    if (isHostHandshakeConfirmed(booking)) {
        log.debug("[CheckIn] Host already confirmed handshake for booking {} — skipping duplicate",
                dto.getBookingId());
    } else {
        eventService.recordEvent(
            booking,
            booking.getCheckInSessionId(),
            CheckInEventType.HANDSHAKE_HOST_CONFIRMED,
            userId,
            CheckInActorRole.HOST,
            Map.of(
                "confirmedAt", Instant.now().toString(),
                "verifiedPhysicalId", dto.getHostVerifiedPhysicalId() != null
                    ? dto.getHostVerifiedPhysicalId() : false
            )
        );
    }
}
```

### Step 2: Add missing fields to `mapToStatusDTO`

In `SVC`, find the builder in `mapToStatusDTO` (around line 1360). Locate the `.handshakeReady(...)` line and add **after** it:

```java
.handshakeReady(booking.getStatus() == BookingStatus.CHECK_IN_COMPLETE)
// ADDED: per-party handshake confirmation flags (previously always false)
.hostConfirmedHandshake(isHostHandshakeConfirmed(booking))
.guestConfirmedHandshake(isGuestHandshakeConfirmed(booking))
.handshakeComplete(booking.getStatus() == BookingStatus.IN_TRIP)
.guestConditionAcknowledged(booking.getGuestCheckInCompletedAt() != null)
.canStartTrip(booking.getStatus() == BookingStatus.CHECK_IN_COMPLETE)
.lastUpdated(LocalDateTime.now(SERBIA_ZONE))
```

`LocalDateTime` is already imported. `SERBIA_ZONE` is a class constant. No new imports needed.

### Step 3: Run tests — verify T1–T5 pass

```bash
cd apps/backend
./mvnw test -pl . -Dtest=HandshakeCorrectnessTest -q 2>&1 | tail -20
```

Expected:
```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
```

### Step 4: Run the full check-in test suite for regressions

```bash
cd apps/backend
./mvnw test -pl . -Dtest="CheckIn*,Handshake*,NoShow*" -q 2>&1 | tail -20
```

Expected: all existing tests still pass.

### Step 5: Commit

```bash
cd apps/backend
git add src/main/java/org/example/rentoza/booking/checkin/CheckInService.java \
        src/test/java/org/example/rentoza/booking/checkin/HandshakeCorrectnessTest.java
git commit -m "$(cat <<'EOF'
fix(checkin): fix host handshake idempotency guard and populate DTO fields

- Remove broken handshakeCompletedAt precondition from host confirmation guard
  (guard previously required trip to have started before preventing duplicates)
- Populate hostConfirmedHandshake, guestConfirmedHandshake, handshakeComplete,
  guestConditionAcknowledged, canStartTrip, lastUpdated in mapToStatusDTO
- Add HandshakeCorrectnessTest (T1-T5)

Fixes: duplicate HANDSHAKE_HOST_CONFIRMED events for booking 58

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3 — Fix `CheckInCommandService`: same host guard

**Files:**
- Modify: `CMD` (line 526)

### Step 1: Apply the identical fix

In `CMD`, find `processHostHandshake` at line 525:

```java
// BEFORE (broken):
private void processHostHandshake(Booking booking, HandshakeConfirmationDTO dto, Long userId) {
    if (booking.getHandshakeCompletedAt() != null && isHostHandshakeConfirmed(booking)) {
        log.debug("[CheckIn-Command] Host already confirmed handshake for booking {}", booking.getId());
        return;
    }
    eventService.recordEvent(
            booking,
            booking.getCheckInSessionId(),
            CheckInEventType.HANDSHAKE_HOST_CONFIRMED,
            userId,
            CheckInActorRole.HOST,
            Map.of(
                    "confirmedAt", Instant.now().toString(),
                    "verifiedPhysicalId", dto.getHostVerifiedPhysicalId() != null
                            ? dto.getHostVerifiedPhysicalId() : false
            )
    );
}
```

Replace with:

```java
// AFTER (correct — mirrors CheckInService fix):
private void processHostHandshake(Booking booking, HandshakeConfirmationDTO dto, Long userId) {
    if (isHostHandshakeConfirmed(booking)) {
        log.debug("[CheckIn-Command] Host already confirmed handshake for booking {} — skipping duplicate",
                booking.getId());
        return;
    }
    eventService.recordEvent(
            booking,
            booking.getCheckInSessionId(),
            CheckInEventType.HANDSHAKE_HOST_CONFIRMED,
            userId,
            CheckInActorRole.HOST,
            Map.of(
                    "confirmedAt", Instant.now().toString(),
                    "verifiedPhysicalId", dto.getHostVerifiedPhysicalId() != null
                            ? dto.getHostVerifiedPhysicalId() : false
            )
    );
}
```

### Step 2: Run CQRS tests — verify no regression

```bash
cd apps/backend
./mvnw test -pl . -Dtest="CheckInStatusView*,CheckInCommand*" -q 2>&1 | tail -20
```

Expected: all pass (no change in behavior for CQRS tests; the broken guard was just never triggered correctly).

### Step 3: Commit

```bash
cd apps/backend
git add src/main/java/org/example/rentoza/booking/checkin/cqrs/CheckInCommandService.java
git commit -m "$(cat <<'EOF'
fix(checkin): mirror host handshake idempotency guard fix in CQRS command path

Same broken handshakeCompletedAt precondition removed from
CheckInCommandService.processHostHandshake.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4 — Fix controller cached body + write T6 test

**Files:**
- Create: `apps/backend/src/test/java/org/example/rentoza/booking/checkin/HandshakeControllerIdempotencyTest.java`
- Modify: `CTL` (lines 242, 324, 373, 432) + constructor

### Step 1: Write failing test T6

```java
package org.example.rentoza.booking.checkin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.dto.CheckInStatusDTO;
import org.example.rentoza.idempotency.IdempotencyService;
import org.example.rentoza.idempotency.IdempotencyService.IdempotencyResult;
import org.example.rentoza.idempotency.IdempotencyService.IdempotencyStatus;
import org.example.rentoza.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("T6: Controller cached idempotency response returns non-empty body")
class HandshakeControllerIdempotencyTest {

    @Mock private CheckInService checkInService;
    @Mock private CheckInPhotoService photoService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private CurrentUser currentUser;
    @Mock private CheckInResponseOptimizer responseOptimizer;

    private CheckInController controller;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules(); // for LocalDateTime etc.
        controller = new CheckInController(
                checkInService, photoService, idempotencyService,
                currentUser, responseOptimizer, new SimpleMeterRegistry(),
                objectMapper);
        when(currentUser.id()).thenReturn(100L);
    }

    @Test
    @DisplayName("T6: cached handshake idempotency response returns non-null body and correct status")
    void cachedHandshakeResponse_returnsBodyNotEmpty() throws Exception {
        // Build a cached status DTO
        CheckInStatusDTO cached = CheckInStatusDTO.builder()
                .bookingId(1L)
                .status(BookingStatus.CHECK_IN_COMPLETE)
                .hostConfirmedHandshake(true)
                .guestConfirmedHandshake(false)
                .handshakeComplete(false)
                .build();

        String cachedJson = objectMapper.writeValueAsString(cached);

        IdempotencyResult result = IdempotencyResult.builder()
                .status(IdempotencyStatus.COMPLETED)
                .httpStatus(200)
                .responseBody(cachedJson)
                .build();

        when(idempotencyService.checkIdempotency(eq("key-abc"), eq(100L)))
                .thenReturn(Optional.of(result));

        var dto = new org.example.rentoza.booking.checkin.dto.HandshakeConfirmationDTO();
        dto.setBookingId(1L);
        dto.setConfirmed(true);

        ResponseEntity<CheckInStatusDTO> response =
                controller.confirmHandshake(1L, dto, "key-abc");

        // Body must not be null
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getBookingId()).isEqualTo(1L);
        assertThat(response.getBody().isHostConfirmedHandshake()).isTrue();
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        // Service must NOT have been called (pure cache replay)
        verify(checkInService, never()).confirmHandshake(any(), any());
    }

    @Test
    @DisplayName("T6b: PROCESSING state still returns 409 with null body")
    void processingState_returns409() {
        IdempotencyResult result = IdempotencyResult.builder()
                .status(IdempotencyStatus.PROCESSING)
                .httpStatus(409)
                .build();

        when(idempotencyService.checkIdempotency(eq("key-processing"), eq(100L)))
                .thenReturn(Optional.of(result));

        var dto = new org.example.rentoza.booking.checkin.dto.HandshakeConfirmationDTO();
        dto.setBookingId(1L);
        dto.setConfirmed(true);

        ResponseEntity<CheckInStatusDTO> response =
                controller.confirmHandshake(1L, dto, "key-processing");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNull();
    }
}
```

### Step 2: Run to verify test FAILS

```bash
cd apps/backend
./mvnw test -pl . -Dtest=HandshakeControllerIdempotencyTest -q 2>&1 | tail -20
```

Expected: compilation error because `CheckInController` constructor does not yet accept `ObjectMapper`, **or** T6 fails with body=null assertion.

### Step 3: Inject `ObjectMapper` into controller constructor

In `CTL`, add import and field:

```java
import com.fasterxml.jackson.databind.ObjectMapper;
```

Add field after `photoUploadCounter`:

```java
private final ObjectMapper objectMapper;
```

Add `ObjectMapper objectMapper` as last constructor parameter:

```java
public CheckInController(
        CheckInService checkInService,
        CheckInPhotoService photoService,
        IdempotencyService idempotencyService,
        CurrentUser currentUser,
        CheckInResponseOptimizer responseOptimizer,
        MeterRegistry meterRegistry,
        ObjectMapper objectMapper) {
    // ... existing assignments ...
    this.objectMapper = objectMapper;
    // ... existing counter registration ...
}
```

### Step 4: Create a helper method for cached response deserialization

Add a private helper at the bottom of the class:

```java
/**
 * Deserialise a cached idempotency response body into CheckInStatusDTO.
 * Returns null if body is missing or deserialisation fails (falls back to empty response).
 */
private CheckInStatusDTO deserializeCachedDto(IdempotencyResult result) {
    if (result.getResponseBody() == null) return null;
    try {
        return objectMapper.readValue(result.getResponseBody(), CheckInStatusDTO.class);
    } catch (Exception ex) {
        log.warn("[CheckIn] Cached body deserialisation failed for op={}: {}",
                result.getOperationType(), ex.getMessage());
        return null;
    }
}
```

### Step 5: Replace all four `.build()` cached-response returns

There are four identical `.build()` patterns in `CTL`. Find each occurrence of:

```java
return ResponseEntity.status(result.getHttpStatus()).build();
```

That appears immediately after a `log.info("[CheckIn] Returning cached ... response for key: ..."` line. Replace **all four** with:

```java
return ResponseEntity.status(result.getHttpStatus()).body(deserializeCachedDto(result));
```

The four locations are approximately:
- ~line 242 (host complete idempotency replay)
- ~line 324 (guest condition-ack idempotency replay)
- ~line 373 (license verify idempotency replay)
- ~line 432 (handshake idempotency replay)

> Use your editor's Find in File for `return ResponseEntity.status(result.getHttpStatus()).build();` to locate all four.

### Step 6: Run T6 — verify pass

```bash
cd apps/backend
./mvnw test -pl . -Dtest=HandshakeControllerIdempotencyTest -q 2>&1 | tail -20
```

Expected:
```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
```

### Step 7: Run full check-in tests for regression

```bash
cd apps/backend
./mvnw test -pl . -Dtest="CheckIn*,Handshake*" -q 2>&1 | tail -20
```

### Step 8: Commit

```bash
cd apps/backend
git add src/main/java/org/example/rentoza/booking/checkin/CheckInController.java \
        src/test/java/org/example/rentoza/booking/checkin/HandshakeControllerIdempotencyTest.java
git commit -m "$(cat <<'EOF'
fix(checkin): return cached DTO body on idempotency replay in CheckInController

All four cached-response branches now deserialise the stored JSON body and
return it instead of an empty 200. Adds ObjectMapper constructor injection.
Adds HandshakeControllerIdempotencyTest (T6).

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5 — Add structured logs and Micrometer counters

**Files:**
- Modify: `SVC` (constructor + `confirmHandshake` method)
- Modify: `CMD` (constructor + `confirmHandshake` method)

### Step 1: Add counter fields to `CheckInService`

In `SVC`, after the existing `licenseVerificationCounter` field (~line 98), add:

```java
// Handshake observability counters
private final Counter handshakeAttemptedHostCounter;
private final Counter handshakeAttemptedGuestCounter;
private final Counter handshakeConfirmedHostCounter;
private final Counter handshakeConfirmedGuestCounter;
private final Counter handshakeWaitingOtherPartyCounter;
private final Counter handshakeTripStartedCounter;
```

In the constructor body (~line 175), after the existing counter registrations, add:

```java
this.handshakeAttemptedHostCounter = Counter.builder("handshake.attempted")
        .tag("role", "HOST").description("Host handshake confirmation attempts")
        .register(meterRegistry);
this.handshakeAttemptedGuestCounter = Counter.builder("handshake.attempted")
        .tag("role", "GUEST").description("Guest handshake confirmation attempts")
        .register(meterRegistry);
this.handshakeConfirmedHostCounter = Counter.builder("handshake.confirmed")
        .tag("role", "HOST").description("Host handshake confirmations recorded")
        .register(meterRegistry);
this.handshakeConfirmedGuestCounter = Counter.builder("handshake.confirmed")
        .tag("role", "GUEST").description("Guest handshake confirmations recorded")
        .register(meterRegistry);
this.handshakeWaitingOtherPartyCounter = Counter.builder("handshake.waiting_other_party")
        .description("Handshake confirmations where the other party has not yet confirmed")
        .register(meterRegistry);
this.handshakeTripStartedCounter = Counter.builder("handshake.trip_started")
        .description("Trips started via handshake").register(meterRegistry);
```

### Step 2: Instrument `confirmHandshake` in `CheckInService`

In `SVC`, inside the `confirmHandshake` method, add the following at the indicated positions:

**A) Entry point — after role detection (~line 641), add:**

```java
log.info("[handshake] attempted bookingId={} actorRole={} hostConfirmed={} guestConfirmed={} bookingStatus={}",
        booking.getId(),
        isHost ? "HOST" : "GUEST",
        isHostHandshakeConfirmed(booking),
        isGuestHandshakeConfirmed(booking),
        booking.getStatus());
if (isHost) handshakeAttemptedHostCounter.increment();
else        handshakeAttemptedGuestCounter.increment();
```

**B) Host guard skipped path (~line 651 after the `log.debug` skip):**

No additional counter needed — the attempted counter already fired.

**C) After `eventService.recordEvent(HANDSHAKE_HOST_CONFIRMED)` call, add:**

```java
handshakeConfirmedHostCounter.increment();
```

**D) After `eventService.recordEvent(HANDSHAKE_GUEST_CONFIRMED)` call (~line 857), add:**

```java
handshakeConfirmedGuestCounter.increment();
```

**E) After checking both confirmed but **before** `startTrip` — if only one party confirmed, add:**

Replace the existing check:
```java
boolean hostConfirmed = isHostHandshakeConfirmed(booking);
boolean guestConfirmed = isGuestHandshakeConfirmed(booking);

if (hostConfirmed && guestConfirmed) {
```

With:
```java
boolean hostConfirmed = isHostHandshakeConfirmed(booking);
boolean guestConfirmed = isGuestHandshakeConfirmed(booking);

log.info("[handshake] decision bookingId={} hostConfirmed={} guestConfirmed={} resultingStatus={}",
        booking.getId(), hostConfirmed, guestConfirmed,
        (hostConfirmed && guestConfirmed) ? "IN_TRIP" : "CHECK_IN_COMPLETE");

if (hostConfirmed && guestConfirmed) {
```

**F) After `handshakeCompletedCounter.increment()` inside `startTrip` call path (~line 895), add:**

```java
handshakeTripStartedCounter.increment();
```

**G) After `if (hostConfirmed && guestConfirmed)` block closes but before `bookingRepository.save`, add (for the one-sided case):**

```java
if (!(hostConfirmed && guestConfirmed)) {
    handshakeWaitingOtherPartyCounter.increment();
}
```

### Step 3: Mirror counter additions in `CheckInCommandService`

In `CMD`, add the same six counter fields and registrations in the constructor (after `checkInDurationTimer`). Same counter names so Prometheus aggregates both paths.

In `CMD.confirmHandshake` (line 473) and `CMD.processHostHandshake/processGuestHandshake`, add the same log lines and counter increments following the same pattern as Step 2 above (role is derived from `isHost`).

### Step 4: Compile check

```bash
cd apps/backend
./mvnw compile -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

### Step 5: Commit

```bash
cd apps/backend
git add src/main/java/org/example/rentoza/booking/checkin/CheckInService.java \
        src/main/java/org/example/rentoza/booking/checkin/cqrs/CheckInCommandService.java
git commit -m "$(cat <<'EOF'
feat(checkin): add structured logs and Micrometer counters to handshake flow

Adds handshake.attempted{role}, handshake.confirmed{role},
handshake.waiting_other_party, handshake.trip_started counters.
Adds structured log lines at every handshake decision point with
bookingId, actorRole, hostConfirmed, guestConfirmed, resultingStatus.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6 — TypeScript model + remove frontend optimistic IN_TRIP + T9 test

**Files:**
- Modify: `MODEL` (add 4 fields)
- Modify: `FE_SVC` (remove optimistic block at lines 773–782)
- Create: `apps/frontend/src/app/features/bookings/check-in/handshake.component.spec.ts`

### Step 1: Add missing fields to TypeScript interface

In `MODEL`, find `handshakeComplete: boolean;` (line ~135) and add the four new fields directly after it:

```typescript
  handshakeComplete: boolean;
  // Per-party handshake confirmation flags (populated by server after each individual confirm)
  hostConfirmedHandshake?: boolean;
  guestConfirmedHandshake?: boolean;
  // Action flag: true when status == CHECK_IN_COMPLETE
  canStartTrip?: boolean;
  // ISO timestamp of last server-side status update
  lastUpdated?: string;
```

### Step 2: Write failing frontend service test (T9)

Create `apps/frontend/src/app/features/bookings/check-in/handshake.component.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { CheckInService } from '../../../core/services/check-in.service';
import { CheckInStatusDTO } from '../../../core/models/check-in.model';
import { GeolocationService } from '../../../core/services/geolocation.service';

describe('CheckInService.confirmHandshake — no optimistic IN_TRIP', () => {
  let service: CheckInService;
  let httpMock: HttpTestingController;

  const baseStatus: Partial<CheckInStatusDTO> = {
    bookingId: 1,
    status: 'CHECK_IN_COMPLETE',
    handshakeReady: true,
    hostConfirmedHandshake: false,
    guestConfirmedHandshake: false,
    handshakeComplete: false,
    host: true,
    guest: false,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        CheckInService,
        { provide: GeolocationService, useValue: { position: () => null } },
      ],
    });
    service = TestBed.inject(CheckInService);
    httpMock = TestBed.inject(HttpTestingController);

    // Seed the current status
    (service as any)._status.set({ ...baseStatus } as CheckInStatusDTO);
  });

  afterEach(() => httpMock.verify());

  it('T9: does NOT set status to IN_TRIP before server responds', () => {
    // Start the confirm call (does NOT await)
    service.confirmHandshake(1).subscribe();

    // Before the mock HTTP response is flushed, status must NOT be IN_TRIP
    const statusBeforeResponse = service.status();
    expect(statusBeforeResponse?.status).not.toBe('IN_TRIP');

    // Flush the HTTP request with a partial-confirm server response
    const req = httpMock.expectOne(r => r.url.includes('/check-in/handshake'));
    req.flush({
      ...baseStatus,
      hostConfirmedHandshake: true,
      guestConfirmedHandshake: false,
      status: 'CHECK_IN_COMPLETE',
      handshakeComplete: false,
    } as CheckInStatusDTO);

    // After flush, status still should NOT be IN_TRIP
    const statusAfterResponse = service.status();
    expect(statusAfterResponse?.status).toBe('CHECK_IN_COMPLETE');
    expect(statusAfterResponse?.status).not.toBe('IN_TRIP');
  });
});
```

### Step 3: Run to verify T9 FAILS

```bash
cd apps/frontend
npx ng test --include="**/check-in/handshake.component.spec.ts" --watch=false 2>&1 | tail -30
```

Expected: `Expected 'IN_TRIP' not to be 'IN_TRIP'` — test fails because the optimistic block fires before the HTTP response.

> **Note:** If the test file does not run due to missing imports, check that `GeolocationService` is importable. Adjust the `useValue` mock to match the actual class shape.

### Step 4: Remove optimistic IN_TRIP block from `check-in.service.ts`

In `FE_SVC`, find and remove the entire block at lines 772–782:

```typescript
// DELETE THIS ENTIRE BLOCK:
    // Optimistic update: if both parties ready, show trip as started
    if (previousStatus && previousStatus.handshakeReady) {
      const optimisticStatus: CheckInStatusDTO = {
        ...previousStatus,
        status: 'IN_TRIP',
        handshakeCompletedAt: new Date().toISOString() as any,
      };
      this._status.set(optimisticStatus);
      this.updateStepFromStatus(optimisticStatus);
      this.updatePhaseFromStatus(optimisticStatus);
    }
```

Also remove the now-unused `previousStatus` variable if it is only referenced in this block and the `catchError` rollback. Check whether `previousStatus` is still referenced in the `catchError` callback:

```typescript
catchError((error) => {
  // Rollback on error
  if (previousStatus) {
    this._status.set(previousStatus);
    this.updateStepFromStatus(previousStatus);
    this.updatePhaseFromStatus(previousStatus);
  }
```

`previousStatus` is still used for rollback — **keep it**. Only delete the optimistic block.

### Step 5: Run T9 — verify pass

```bash
cd apps/frontend
npx ng test --include="**/check-in/handshake.component.spec.ts" --watch=false 2>&1 | tail -20
```

Expected: `1 spec, 0 failures`

### Step 6: Commit

```bash
git add apps/frontend/src/app/core/models/check-in.model.ts \
        apps/frontend/src/app/core/services/check-in.service.ts \
        apps/frontend/src/app/features/bookings/check-in/handshake.component.spec.ts
git commit -m "$(cat <<'EOF'
fix(frontend): remove optimistic IN_TRIP on single-party handshake confirm

- Add hostConfirmedHandshake, guestConfirmedHandshake, canStartTrip,
  lastUpdated to CheckInStatusDTO interface
- Remove misleading optimistic status:'IN_TRIP' update that fired before
  the server confirmed both parties had confirmed
- Add T9 spec: status never shows IN_TRIP before server responds

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7 — Fix handshake component: per-party status fields + computed props + T7/T8 tests

**Files:**
- Modify: `COMP`
- Modify: `T_FE` (extend the spec file created in Task 6)

### Step 1: Write failing component tests T7 and T8

Append to `apps/frontend/src/app/features/bookings/check-in/handshake.component.spec.ts`:

```typescript
import { Component } from '@angular/core';
import { ComponentFixture } from '@angular/core/testing';
import { HandshakeComponent } from './handshake.component';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

describe('HandshakeComponent — per-party handshake state', () => {
  let fixture: ComponentFixture<HandshakeComponent>;
  let component: HandshakeComponent;

  function createComponent(statusOverrides: Partial<CheckInStatusDTO>): void {
    TestBed.configureTestingModule({
      imports: [
        HandshakeComponent,
        NoopAnimationsModule,
        HttpClientTestingModule,
      ],
      providers: [
        CheckInService,
        { provide: GeolocationService, useValue: { position: () => null } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(HandshakeComponent);
    component = fixture.componentInstance;
    component.bookingId = 1;
    component.status = {
      bookingId: 1,
      status: 'CHECK_IN_COMPLETE',
      hostCheckInComplete: true,     // always true in handshake screen
      guestCheckInComplete: true,    // always true in handshake screen
      hostConfirmedHandshake: false,
      guestConfirmedHandshake: false,
      handshakeComplete: false,
      handshakeReady: true,
      host: false,
      guest: false,
      ...statusOverrides,
    } as CheckInStatusDTO;
    fixture.detectChanges();
  }

  // T7: host already confirmed, guest has not
  describe('T7: host confirmed, guest pending', () => {
    beforeEach(() => {
      createComponent({ host: true, hostConfirmedHandshake: true, guestConfirmedHandshake: false });
    });

    it('isWaitingForOther() returns true', () => {
      expect(component.isWaitingForOther()).toBeTrue();
    });

    it('canConfirm() returns false (host already confirmed)', () => {
      expect(component.canConfirm()).toBeFalse();
    });

    it('status card shows host as confirmed handshake', () => {
      const compiled = fixture.nativeElement as HTMLElement;
      // The host status item should have class "completed"
      const statusItems = compiled.querySelectorAll('.status-item');
      expect(statusItems[0].classList).toContain('completed'); // host row
      expect(statusItems[1].classList).not.toContain('completed'); // guest row
    });
  });

  // T8: neither party has confirmed
  describe('T8: neither party confirmed', () => {
    beforeEach(() => {
      createComponent({ host: true, hostConfirmedHandshake: false, guestConfirmedHandshake: false });
    });

    it('isWaitingForOther() returns false', () => {
      expect(component.isWaitingForOther()).toBeFalse();
    });

    it('canConfirm() returns true', () => {
      expect(component.canConfirm()).toBeTrue();
    });
  });
});
```

### Step 2: Run to verify T7/T8 FAIL

```bash
cd apps/frontend
npx ng test --include="**/check-in/handshake.component.spec.ts" --watch=false 2>&1 | tail -30
```

Expected: `isWaitingForOther()` test fails (returns false instead of true) and status card test fails (neither row has `completed` class from handshake state).

### Step 3: Fix `handshake.component.ts`

**A) Status card — swap field references (lines 87–107)**

Find:
```html
          <div class="status-item" [class.completed]="status?.hostCheckInComplete">
            <mat-icon>{{ status?.hostCheckInComplete ? 'check_circle' : 'pending' }}</mat-icon>
            <div>
              <span class="status-label">Domaćin</span>
              <span class="status-text">{{
                status?.hostCheckInComplete ? 'Završio' : 'Čeka potvrdu'
              }}</span>
            </div>
          </div>

          <mat-divider vertical></mat-divider>

          <div class="status-item" [class.completed]="status?.guestCheckInComplete">
            <mat-icon>{{ status?.guestCheckInComplete ? 'check_circle' : 'pending' }}</mat-icon>
            <div>
              <span class="status-label">Gost</span>
              <span class="status-text">{{
                status?.guestCheckInComplete ? 'Potvrdio' : 'Čeka potvrdu'
              }}</span>
            </div>
          </div>
```

Replace with:
```html
          <div class="status-item" [class.completed]="status?.hostConfirmedHandshake">
            <mat-icon>{{ status?.hostConfirmedHandshake ? 'check_circle' : 'pending' }}</mat-icon>
            <div>
              <span class="status-label">Domaćin</span>
              <span class="status-text">{{
                status?.hostConfirmedHandshake ? 'Potvrdio primopredaju' : 'Čeka potvrdu'
              }}</span>
            </div>
          </div>

          <mat-divider vertical></mat-divider>

          <div class="status-item" [class.completed]="status?.guestConfirmedHandshake">
            <mat-icon>{{ status?.guestConfirmedHandshake ? 'check_circle' : 'pending' }}</mat-icon>
            <div>
              <span class="status-label">Gost</span>
              <span class="status-text">{{
                status?.guestConfirmedHandshake ? 'Potvrdio primopredaju' : 'Čeka potvrdu'
              }}</span>
            </div>
          </div>
```

**B) `isWaitingForOther` computed (lines 675–681)**

Replace:
```typescript
  isWaitingForOther = computed(() => {
    if (!this.status) return false;
    if (this.status.host) {
      return this._isConfirmed() && !this.status.guestCheckInComplete;
    }
    return this._isConfirmed() && !this.status.hostCheckInComplete;
  });
```

With:
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

**C) `canConfirm` computed (lines 690–700)**

Replace:
```typescript
  canConfirm = computed(() => {
    // Phase 4B: Block confirmation if license verification is required but not done
    if (
      this.status?.host &&
      this.isLicenseVerificationRequired() &&
      !this.status?.licenseVerifiedInPerson
    ) {
      return false;
    }
    return !this._isConfirmed() && !this.checkInService.isLoading();
  });
```

With:
```typescript
  canConfirm = computed(() => {
    if (!this.status) return false;
    // Disable if this actor has already confirmed server-side (persists across component re-creation)
    const alreadyConfirmedServerSide = this.status.host
      ? this.status.hostConfirmedHandshake === true
      : this.status.guestConfirmedHandshake === true;
    if (alreadyConfirmedServerSide) return false;
    // Phase 4B: Block if host license verification is required but not done
    if (
      this.status.host &&
      this.isLicenseVerificationRequired() &&
      !this.status.licenseVerifiedInPerson
    ) {
      return false;
    }
    return !this._isConfirmed() && !this.checkInService.isLoading();
  });
```

**D) `roleInstructions` computed (lines 668–673)**

Replace:
```typescript
  roleInstructions = computed(() => {
    if (this.status?.host) {
      return 'Kada predate ključeve gostu, prevucite za potvrdu';
    }
    return 'Kada primite ključeve, prevucite za potvrdu';
  });
```

With:
```typescript
  roleInstructions = computed(() => {
    if (this.isWaitingForOther()) {
      return this.status?.host
        ? 'Vaša potvrda je primljena. Čekamo gosta da potvrdi.'
        : 'Vaša potvrda je primljena. Čekamo domaćina da potvrdi.';
    }
    if (this.status?.host) {
      return 'Kada predate ključeve gostu, prevucite za potvrdu';
    }
    return 'Kada primite ključeve, prevucite za potvrdu';
  });
```

### Step 4: Run T7/T8 — verify pass

```bash
cd apps/frontend
npx ng test --include="**/check-in/handshake.component.spec.ts" --watch=false 2>&1 | tail -20
```

Expected:
```
3 specs, 0 failures   (T9 + T7 + T8)
```

### Step 5: Run full frontend tests for regression

```bash
cd apps/frontend
npx ng test --watch=false 2>&1 | tail -10
```

Expected: all specs pass.

### Step 6: Commit

```bash
git add apps/frontend/src/app/features/bookings/check-in/handshake.component.ts \
        apps/frontend/src/app/features/bookings/check-in/handshake.component.spec.ts
git commit -m "$(cat <<'EOF'
fix(frontend): drive handshake UI from per-party server confirmation flags

- Status card: swap hostCheckInComplete/guestCheckInComplete (pre-handshake
  phase flags, always true) for hostConfirmedHandshake/guestConfirmedHandshake
- isWaitingForOther: server-driven from per-party flags, no local signal
- canConfirm: gate on server-side actor confirmation to prevent repeat confirm
  after component re-creation
- roleInstructions: show "waiting for other party" message when applicable
- Add T7/T8 component specs

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Final verification

### Run full backend test suite

```bash
cd apps/backend
./mvnw test -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, zero failures.

### Run full frontend test suite

```bash
cd apps/frontend
npx ng test --watch=false 2>&1 | tail -10
```

Expected: all specs pass.

### Confirm no duplicate HOST events in staging DB

After deploying to staging, perform a host-only handshake confirm (do not complete guest side). Then run:

```sql
SELECT booking_id, COUNT(*) as event_count
FROM check_in_event
WHERE event_type = 'HANDSHAKE_HOST_CONFIRMED'
GROUP BY booking_id
HAVING COUNT(*) > 1;
```

Expected: zero rows for any new booking (bookings predating the fix may still show historical duplicates).

### Confirm Prometheus counters appear

```bash
curl -s http://localhost:8080/actuator/prometheus | grep handshake
```

Expected output includes:
```
handshake_attempted_total{role="HOST",...}
handshake_confirmed_total{role="HOST",...}
handshake_waiting_other_party_total
handshake_trip_started_total
```

---

## Rollout checklist

1. **Deploy backend** first (changes are backward-compatible — frontend can still send old requests).
2. **Deploy frontend** second.
3. **Smoke test staging**: host confirms only → verify `hostConfirmedHandshake=true`, `guestConfirmedHandshake=false` in response body; verify UI shows "Čekamo gosta" and host swipe is disabled.
4. **Monitor**: watch `handshake.trip_started` vs `handshake.confirmed{role=GUEST}` rates — they should equalise within 5 minutes of a complete handshake.
5. **Rollback**: revert frontend deploy first (stateless); revert backend only if DB-side regressions appear. Historical duplicate events for booking 58 are cosmetically present but idempotent going forward.

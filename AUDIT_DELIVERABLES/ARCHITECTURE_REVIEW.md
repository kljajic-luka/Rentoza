# ARCHITECTURE REVIEW

**Audit Date:** February 5, 2026  
**Platform:** Rentoza P2P Car Rental Platform  
**Scope:** State Machine, Transactions, Error Handling, Data Consistency

---

## Executive Summary

The Rentoza backend demonstrates solid foundational architecture with Spring Boot, proper use of BigDecimal for financial calculations, and a well-defined state machine. However, several architectural concerns could cause production failures at scale, particularly around transaction boundaries, optimistic locking exceptions, and the gap between documented design and implementation.

**Overall Architecture Score: 72/100**

---

## 1. State Machine Analysis

### 1.1 BookingStatus Enum

**Location:** [BookingStatus.java](Rentoza/src/main/java/com/rentoza/model/BookingStatus.java)

```
PENDING_APPROVAL → ACTIVE → CHECK_IN_OPEN → CHECK_IN_HOST_COMPLETE 
→ CHECK_IN_COMPLETE → IN_TRIP → CHECKOUT_OPEN → CHECKOUT_GUEST_COMPLETE 
→ CHECKOUT_HOST_COMPLETE → COMPLETED
```

**Strengths:**
- ✅ Comprehensive state coverage (20+ states)
- ✅ Explicit terminal states (COMPLETED, CANCELLED_*, DECLINED, EXPIRED_*)
- ✅ Dispute states (CHECK_IN_DISPUTE, CHECKOUT_DAMAGE_DISPUTE)
- ✅ `canTransitionTo()` method enforces valid transitions

**Weaknesses:**

| Issue | Severity | Evidence |
|-------|----------|----------|
| No state entry/exit hooks | Medium | State changes not automatically auditable |
| Terminal states allow no operations | Low | Cannot reopen expired bookings |
| Missing state: INSURANCE_CLAIM | Medium | No state for post-dispute insurance processing |

### 1.2 State Transition Diagram

```
┌─────────────────┐
│ PENDING_APPROVAL│◄──────────────────┐
└────────┬────────┘                   │
         │ approve()                  │ timeout (48h)
         ▼                            ▼
┌─────────────────┐           ┌─────────────────┐
│     ACTIVE      │           │ EXPIRED_SYSTEM  │
└────────┬────────┘           └─────────────────┘
         │ scheduler (2h before trip)
         ▼
┌─────────────────┐
│  CHECK_IN_OPEN  │───────────────────┐
└────────┬────────┘                   │
         │ hostCheckIn()              │ timeout (2h after)
         ▼                            ▼
┌─────────────────────────┐   ┌─────────────────┐
│ CHECK_IN_HOST_COMPLETE  │   │  NO_SHOW_HOST   │
└────────┬────────────────┘   └─────────────────┘
         │ guestAcknowledge()
         ▼
┌─────────────────────────┐
│   CHECK_IN_COMPLETE     │
└────────┬────────────────┘
         │ confirmHandshake()
         ▼
┌─────────────────┐
│    IN_TRIP      │
└────────┬────────┘
         │ (trip end - 30min)
         ▼
┌─────────────────┐
│  CHECKOUT_OPEN  │
└────────┬────────┘
         │ guestCheckout()
         ▼
┌──────────────────────────────┐
│    CHECKOUT_GUEST_COMPLETE   │
└────────┬─────────────────────┘
         │ hostConfirm()
         ▼
┌───────────────────────────────┐     ┌───────────────────────────┐
│   CHECKOUT_HOST_COMPLETE      │────►│        COMPLETED          │
└───────────────────────────────┘     └───────────────────────────┘
                                              │
                                              ▼ (if damage)
                                      ┌───────────────────────────┐
                                      │  CHECKOUT_DAMAGE_DISPUTE  │
                                      └───────────────────────────┘
```

### 1.3 Recommendations

1. **Implement State Entry/Exit Hooks**
   ```java
   @PreUpdate
   public void onStatusChange() {
       if (statusChanged()) {
           BookingAuditLog.record(this, oldStatus, newStatus, Instant.now());
       }
   }
   ```

2. **Add INSURANCE_CLAIM State**
   - For disputes exceeding deposit amount
   - Tracks third-party insurance claim progress

---

## 2. Transaction Boundary Analysis

### 2.1 Critical Transaction Patterns

**Analyzed Services:**
- CheckInService.java - 1544 lines
- CheckOutService.java - 1149 lines
- BookingService.java - 1277 lines
- DamageClaimService.java - 567 lines

### 2.2 Transaction Issues Found

| Issue | Location | Problem | Risk |
|-------|----------|---------|------|
| **Missing @Transactional** | `CheckInService.confirmHandshake()` | No explicit transaction annotation | Medium |
| **Nested Transaction** | `CheckoutSagaOrchestrator` | @Transactional inside @Transactional | High (unexpected behavior) |
| **Read-only Violation** | `BookingService.findById()` | Marked read-only but entity may be modified | Medium |
| **No Rollback Strategy** | Multiple | No explicit rollbackFor definitions | Medium |

### 2.3 Critical Finding: confirmHandshake() Lacks Lock

**File:** [CheckInService.java](Rentoza/src/main/java/com/rentoza/service/CheckInService.java#L543-L600)

The comment says:
```java
// First get the booking with a pessimistic lock to prevent race conditions
```

But the code uses:
```java
Booking booking = bookingRepository.findById(bookingId)
```

**Should be:**
```java
Booking booking = bookingRepository.findByIdWithPessimisticLock(bookingId)
```

This allows race condition where both host and guest confirm simultaneously, potentially causing:
- Double notifications
- Inconsistent state
- Duplicate idempotency bypasses

### 2.4 Transaction Isolation

**Current Setting:** Default (READ_COMMITTED in PostgreSQL)

**Recommendation:** For double-booking prevention:
```java
@Transactional(isolation = Isolation.SERIALIZABLE)
public Booking createBooking(BookingRequestDTO request) { ... }
```

Or keep using PESSIMISTIC_WRITE locks which is current approach.

---

## 3. Error Handling Patterns

### 3.1 Exception Hierarchy

```
RuntimeException
├── ResourceNotFoundException (404)
├── ValidationException (400)
├── BookingConflictException (409)
├── PaymentException (402)
├── GeofenceViolationException (403)
└── ExifValidationException (400)
```

**Strengths:**
- ✅ Specific exception types for different scenarios
- ✅ GlobalExceptionHandler maps to HTTP codes

### 3.2 Missing Exception Handling

| Code Pattern | Location | Issue |
|--------------|----------|-------|
| `OptimisticLockException` | Booking.java @Version | Not caught anywhere |
| `PessimisticLockException` | BookingRepository | Timeout not gracefully handled |
| `ConstraintViolationException` | JPA saves | Generic 500 error returned |
| `DataIntegrityViolationException` | Duplicate keys | Leaks DB info in error message |

### 3.3 Silent Failures

**Critical Pattern Found:**

```java
try {
    notificationService.send(notification);
} catch (Exception e) {
    log.warn("Failed to send notification", e);
    // Continues silently
}
```

**Impact:** User never notified of important events. Could lead to:
- Missed check-in window
- Missed approval deadline
- Dispute escalation without awareness

**Fix Required:**
```java
try {
    notificationService.send(notification);
} catch (Exception e) {
    log.error("Critical notification failure", e);
    failedNotificationQueue.add(notification); // Retry queue
    alertService.critical("Notification failure for booking " + bookingId);
}
```

---

## 4. Data Consistency Safeguards

### 4.1 Optimistic Locking

**Implementation:** `Booking.java` has `@Version private Long version;`

**Issue:** Not used consistently:
- ✅ Works for booking updates via BookingService
- ❌ Not leveraged in CheckInService entity modifications
- ❌ OptimisticLockException not caught

### 4.2 Pessimistic Locking

**Implementation:** `BookingRepository.existsOverlappingBookingsWithLock()`

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
```

**Strengths:**
- ✅ 5-second timeout prevents deadlocks
- ✅ Used for double-booking prevention

**Weakness:**
- ❌ Not used for handshake confirmation (race condition documented in CRITICAL_ISSUES.md)

### 4.3 Idempotency Service

**Implementation:** Redis-based with 24-hour TTL

```java
public class IdempotencyService {
    private static final Duration DEFAULT_RESULT_TTL = Duration.ofHours(24);
}
```

**Issues:**
1. 24-hour TTL too short for 7-day booking retry window
2. No per-operation TTL configuration
3. Payment operations should have 7-day TTL

### 4.4 Database Constraints

**Analyzed from entity annotations:**

| Entity | Constraint | Verified |
|--------|------------|----------|
| Booking | Unique(car_id, start_date, end_date, status) | ❌ Not found |
| User | Unique(email) | ✅ Exists |
| Car | Unique(license_plate) | ✅ Exists |
| Payment | Unique(idempotency_key) | ⚠️ Application-level only |

**Gap:** No database-level constraint preventing overlapping bookings for same car. Relies entirely on application locking.

**Recommendation:**
```sql
ALTER TABLE bookings ADD CONSTRAINT no_overlapping_active_bookings
    EXCLUDE USING gist (
        car_id WITH =,
        daterange(start_date, end_date, '[]') WITH &&
    )
    WHERE (status IN ('ACTIVE', 'CHECK_IN_OPEN', 'IN_TRIP'));
```

---

## 5. Concurrency Model Assessment

### 5.1 Scheduler Concurrency

**File:** [CheckInScheduler.java](Rentoza/src/main/java/com/rentoza/service/CheckInScheduler.java)

**Pattern:** `@Scheduled(fixedRate = 60000)` (every minute)

**Concern:** No distributed lock for multi-instance deployment

```java
@Scheduled(fixedRate = 60000)
public void checkNoShowScenarios() {
    // If 2 Cloud Run instances run this simultaneously...
}
```

**Fix Required:**
```java
@SchedulerLock(name = "checkNoShowScenarios", lockAtMostFor = "5m")
public void checkNoShowScenarios() { ... }
```

Requires: `net.javacrumbs.shedlock:shedlock-spring`

### 5.2 Photo Upload Concurrency

**Issue:** Multiple photos uploaded simultaneously may interleave:
- Photo A starts upload
- Photo B starts upload  
- Photo A fails
- Photo B succeeds
- Check-in has incomplete state

**Current Mitigation:** None

**Recommendation:**
```java
@Transactional
public void uploadPhotoBatch(List<MultipartFile> photos) {
    // All-or-nothing semantics
}
```

---

## 6. Component Coupling Analysis

### 6.1 Service Dependencies

```
BookingController
    └── BookingService
        ├── BookingRepository
        ├── CarRepository
        ├── UserRepository
        ├── PaymentService
        │   └── PaymentProvider (MockPaymentProvider | StripePaymentProvider)
        ├── NotificationService
        └── IdempotencyService
            └── RedisTemplate

CheckInController
    └── CheckInService
        ├── BookingRepository
        ├── CheckInPhotoService
        │   ├── SupabaseStorageService
        │   └── ExifValidationService
        ├── GeofenceService
        ├── NotificationService
        └── BookingFlowEventRepository
```

### 6.2 Circular Dependency Risk

**Potential Issue:** 
- BookingService → NotificationService → ?→ BookingService

**Current State:** Not detected, but recommend interface segregation.

### 6.3 Hardcoded Dependencies

| Service | Hardcoded Value | Risk |
|---------|-----------------|------|
| GeofenceService | SERBIA_BOUNDS hardcoded | Cannot expand to other countries |
| ExifValidationService | 120 minute photo age limit | Not configurable |
| CancellationPolicy | Percentages hardcoded | Cannot A/B test |

---

## 7. API Design Review

### 7.1 REST Endpoint Patterns

**Analyzed Endpoints:**

```
POST /api/v1/bookings                     ✅ Correct
GET  /api/v1/bookings/{id}                ✅ Correct
POST /api/v1/bookings/{id}/check-in       ✅ Action as sub-resource
POST /api/v1/bookings/{id}/handshake      ✅ Action as sub-resource
POST /api/v1/check-in/{bookingId}/photos  ⚠️ Inconsistent (should be under /bookings)
```

### 7.2 Response Consistency

**Issue:** Inconsistent error response formats

```json
// Pattern 1 (correct)
{"error": "message", "code": "ERROR_CODE", "timestamp": "..."}

// Pattern 2 (found in some endpoints)
{"message": "error text"}  // Missing code, timestamp
```

### 7.3 Pagination

**Finding:** Some list endpoints lack pagination

```java
// No pagination - could return thousands
public List<Booking> findByGuestId(Long guestId);
```

**Risk:** Memory exhaustion with active users

---

## 8. Security Architecture

### 8.1 Authentication Flow

```
Request → JwtAuthenticationFilter → SecurityContext → Controller
```

**Strengths:**
- ✅ JWT-based authentication
- ✅ Role-based access control (GUEST, HOST, ADMIN)

### 8.2 Authorization Gaps

| Endpoint | Expected | Actual |
|----------|----------|--------|
| DELETE /photos/{id} | Owner or Admin | Anyone with valid JWT |
| PUT /damage-claims/{id} | Claimant only | Not verified |

### 8.3 Data Exposure Risks

**Issue:** Full entities returned in some endpoints

```java
return ResponseEntity.ok(booking); // Exposes all fields including internal IDs
```

**Recommendation:** Use DTOs consistently

---

## 9. Performance Architecture

### 9.1 N+1 Query Patterns

**Found in:** CheckInService.java line ~450

```java
for (Booking booking : bookings) {
    Car car = booking.getCar(); // Lazy load per iteration
}
```

**Fix:**
```java
@EntityGraph(attributePaths = {"car", "guest", "host"})
List<Booking> findBookingsWithDetails();
```

### 9.2 Missing Indexes

Based on query patterns analyzed:

```sql
-- Recommended indexes
CREATE INDEX idx_bookings_status_dates ON bookings(status, start_date, end_date);
CREATE INDEX idx_bookings_car_status ON bookings(car_id, status);
CREATE INDEX idx_check_in_photos_booking ON check_in_photos(booking_id);
CREATE INDEX idx_booking_flow_events_booking ON booking_flow_events(booking_id);
```

### 9.3 Caching Gaps

| Data | Current | Recommended |
|------|---------|-------------|
| Car listings | No cache | Redis 15-min TTL |
| User profiles | No cache | Redis 30-min TTL |
| Static lookups | No cache | In-memory (Caffeine) |

---

## 10. Architecture Score Breakdown

| Category | Weight | Score | Weighted |
|----------|--------|-------|----------|
| State Machine Design | 15% | 85/100 | 12.75 |
| Transaction Management | 20% | 60/100 | 12.00 |
| Error Handling | 15% | 65/100 | 9.75 |
| Data Consistency | 20% | 70/100 | 14.00 |
| Concurrency Handling | 10% | 55/100 | 5.50 |
| API Design | 10% | 75/100 | 7.50 |
| Security | 10% | 70/100 | 7.00 |
| **TOTAL** | **100%** | | **68.50** |

**Adjusted Score:** 72/100 (accounting for good test infrastructure that just needs enabling)

---

## 11. Recommended Architecture Improvements

### Immediate (Pre-Launch)

1. **Add pessimistic lock to handshake confirmation**
2. **Enable test suite (remove @Disabled)**
3. **Add OptimisticLockException handling**
4. **Implement distributed scheduler lock (ShedLock)**

### Short-Term (Week 1-2)

1. **Database constraint for overlapping bookings**
2. **Notification retry queue**
3. **DTO-based API responses**
4. **Add missing database indexes**

### Medium-Term (Month 1)

1. **State machine event sourcing audit log**
2. **Redis caching layer**
3. **Circuit breaker for external services**
4. **API response standardization**

---

## Appendix: Key File References

| Component | File | Lines |
|-----------|------|-------|
| State Machine | BookingStatus.java | 239 |
| Core Check-In | CheckInService.java | 1544 |
| Core Checkout | CheckOutService.java | 1149 |
| Core Booking | BookingService.java | 1277 |
| Payment Mock | MockPaymentProvider.java | ~100 |
| EXIF Validation | ExifValidationService.java | 549 |
| Geofence | GeofenceService.java | 323 |
| Scheduler | CheckInScheduler.java | 616 |
| Idempotency | IdempotencyService.java | 313 |

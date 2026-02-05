# EDGE CASE TEST SUITE

**Audit Date:** February 5, 2026  
**Platform:** Rentoza P2P Car Rental Platform  
**Target Coverage:** 50+ comprehensive test scenarios

---

## Test Categories

1. **Happy Paths** - Ideal user behavior
2. **Failure Scenarios** - Network errors, timeouts, invalid data
3. **Malicious Behavior** - Fraud attempts, data manipulation
4. **Concurrent Operations** - Race conditions
5. **Time-Based Edge Cases** - Timezones, DST, leap years
6. **Data Boundary Conditions** - Null values, empty strings, max limits

---

## 1. CHECK-IN FLOW TESTS (20 scenarios)

### 1.1 Happy Path Tests

```java
@Test
@DisplayName("TC-001: Complete host check-in with all 8 photos")
void testHostCheckInWithAllPhotos() {
    // GIVEN: Booking in CHECK_IN_OPEN status
    // AND: 8 required photo types uploaded
    // WHEN: Host submits check-in with odometer=45230, fuelLevel=75
    // THEN: Status transitions to CHECK_IN_HOST_COMPLETE
    // AND: Car location derived from first photo EXIF GPS
    // AND: Guest receives notification
}

@Test
@DisplayName("TC-002: Complete guest condition acknowledgment")
void testGuestConditionAcknowledgment() {
    // GIVEN: Booking in CHECK_IN_HOST_COMPLETE status
    // WHEN: Guest acknowledges condition with no hotspots
    // THEN: Status transitions to CHECK_IN_COMPLETE
    // AND: Handshake ready notifications sent
}

@Test
@DisplayName("TC-003: Successful handshake starts trip")
void testHandshakeStartsTrip() {
    // GIVEN: Booking in CHECK_IN_COMPLETE status
    // AND: Both parties ready to confirm
    // WHEN: Host confirms, then guest confirms
    // THEN: Status transitions to IN_TRIP
    // AND: Trip started notifications sent
}

@Test
@DisplayName("TC-004: Remote handoff with lockbox code reveal")
void testRemoteHandoffLockboxReveal() {
    // GIVEN: Booking with lockbox code encrypted
    // AND: Guest within geofence radius (50m)
    // WHEN: Guest confirms handshake with GPS coordinates
    // THEN: Lockbox code decrypted and returned in response
    // AND: Trip started
}
```

### 1.2 Failure Scenario Tests

```java
@Test
@DisplayName("TC-005: Host check-in with insufficient photos")
void testHostCheckInInsufficientPhotos() {
    // GIVEN: Booking in CHECK_IN_OPEN status
    // AND: Only 5 photos uploaded (need 8)
    // WHEN: Host attempts to submit check-in
    // THEN: IllegalStateException thrown
    // AND: Message: "Potrebno je minimum 8 tipova fotografija. Pronađeno: 5"
}

@Test
@DisplayName("TC-006: Check-in attempted before window opens")
void testCheckInBeforeWindowOpens() {
    // GIVEN: Booking with startTime = NOW + 48 hours
    // AND: Status is ACTIVE (not CHECK_IN_OPEN)
    // WHEN: Host attempts to upload photo
    // THEN: IllegalStateException thrown
    // AND: Message: "Prijem nije otvoren za otpremanje fotografija"
}

@Test
@DisplayName("TC-007: Check-in blocked for early completion (Phase 4A)")
void testCheckInBlockedTooEarly() {
    // GIVEN: Booking with startTime = NOW + 3 hours
    // AND: maxEarlyCheckInHours = 1
    // WHEN: Host attempts to complete check-in
    // THEN: IllegalStateException thrown
    // AND: EARLY_CHECK_IN_BLOCKED event recorded
}

@Test
@DisplayName("TC-008: Photo upload with network timeout")
void testPhotoUploadNetworkTimeout() {
    // GIVEN: Photo upload in progress
    // WHEN: Network times out after 30 seconds
    // THEN: SupabaseStorageService throws timeout exception
    // AND: Photo not saved to database (transaction rolled back)
    // AND: Client can retry with same idempotency key
}

@Test
@DisplayName("TC-009: Guest check-in with expired license")
void testGuestCheckInExpiredLicense() {
    // GIVEN: Guest's license expired yesterday
    // AND: Strict check-in mode enabled
    // WHEN: Guest attempts handshake confirmation
    // THEN: ValidationException thrown
    // AND: Message: "Vaša vozačka dozvola je istekla"
}
```

### 1.3 Concurrent Operation Tests

```java
@Test
@DisplayName("TC-010: Simultaneous handshake confirmations")
void testSimultaneousHandshakeConfirmations() {
    // GIVEN: Booking in CHECK_IN_COMPLETE status
    // WHEN: Host and guest confirm within 10ms of each other
    // THEN: Only one IN_TRIP transition occurs (pessimistic lock)
    // AND: Second request returns idempotent success
}

@Test
@DisplayName("TC-011: Two renters check-in to same car (double booking)")
void testDoubleBookingCheckIn() {
    // GIVEN: System error caused double booking
    // AND: Both bookings in CHECK_IN_OPEN status
    // WHEN: Both renters attempt check-in
    // THEN: Second check-in detects conflict
    // AND: Admin notified of data integrity issue
}

@Test
@DisplayName("TC-012: Owner cancels during guest check-in")
void testOwnerCancelsDuringGuestCheckIn() {
    // GIVEN: Booking in CHECK_IN_HOST_COMPLETE
    // AND: Guest is mid-check-in (acknowledging condition)
    // WHEN: Owner attempts to cancel
    // THEN: Cancellation blocked
    // AND: Message: "Cannot cancel during active check-in"
}
```

### 1.4 Malicious Behavior Tests

```java
@Test
@DisplayName("TC-013: Photo with EXIF timestamp in future")
void testPhotoFutureTimestamp() {
    // GIVEN: Photo with EXIF timestamp = NOW + 1 hour
    // WHEN: Uploaded via CheckInPhotoService
    // THEN: ExifValidationStatus = REJECTED_FUTURE_TIMESTAMP
    // AND: Photo rejected (zero-storage policy)
}

@Test
@DisplayName("TC-014: Photo reuse from previous trip")
void testPhotoReuseDetection() {
    // GIVEN: Photo from 3 days ago (different booking)
    // WHEN: Uploaded to current check-in
    // THEN: ExifValidationStatus = REJECTED_TOO_OLD
    // AND: Rejection event logged
}

@Test
@DisplayName("TC-015: GPS spoofing with mock location")
void testGpsSpoofingMockLocation() {
    // GIVEN: Guest using mock GPS app
    // AND: isMockLocation = true in request
    // WHEN: Guest attempts handshake
    // THEN: GeofenceViolationException thrown
    // AND: MOCK_LOCATION_DETECTED event logged
}

@Test
@DisplayName("TC-016: Bypass check-in by calling trip start directly")
void testBypassCheckInAttempt() {
    // GIVEN: Booking in CHECK_IN_OPEN status (not CHECK_IN_COMPLETE)
    // WHEN: Attacker calls handshake endpoint directly
    // THEN: IllegalStateException thrown
    // AND: Message: "Prijem nije završen"
}

@Test
@DisplayName("TC-017: Flood system with check-in attempts (DoS)")
void testDosPreventionRateLimiting() {
    // GIVEN: Rate limit = 10 requests/minute
    // WHEN: 100 check-in attempts in 1 minute
    // THEN: After 10, remaining requests return 429 Too Many Requests
    // AND: Rate limit resets after 1 minute
}
```

### 1.5 Time-Based Edge Cases

```java
@Test
@DisplayName("TC-018: Check-in during DST transition")
void testCheckInDuringDstTransition() {
    // GIVEN: Trip starts at 2:30 AM on March 31 (DST spring forward)
    // AND: 2:00-3:00 AM doesn't exist
    // WHEN: Check-in window calculated
    // THEN: Window opens at 1:30 AM (correct adjustment)
    // AND: No invalid date exceptions
}

@Test
@DisplayName("TC-019: Timezone confusion (client PST, server CET)")
void testTimezoneConfusion() {
    // GIVEN: Guest's phone in PST (-8 hours)
    // AND: Booking shows 10:00 AM (which timezone?)
    // WHEN: Guest arrives at Belgrade 10:00 AM
    // THEN: All times displayed in Europe/Belgrade
    // AND: Check-in allowed (times stored in Belgrade local)
}

@Test
@DisplayName("TC-020: Check-in at leap second")
void testCheckInAtLeapSecond() {
    // GIVEN: Check-in submitted at 23:59:60 (leap second)
    // WHEN: Timestamp stored
    // THEN: Java Instant handles gracefully
    // AND: No millisecond overflow
}
```

---

## 2. CHECK-OUT FLOW TESTS (15 scenarios)

```java
@Test
@DisplayName("TC-021: Normal checkout with no damage")
void testNormalCheckoutNoDamage() {
    // GIVEN: Trip in IN_TRIP status
    // WHEN: Guest completes checkout with photos, odometer, fuel
    // AND: Host confirms with conditionAccepted=true
    // THEN: Status transitions to COMPLETED
    // AND: Deposit released
}

@Test
@DisplayName("TC-022: Checkout with damage reported")
void testCheckoutWithDamageReported() {
    // GIVEN: Trip returning to owner
    // WHEN: Host reports damage with photos and estimate
    // THEN: Status transitions to CHECKOUT_DAMAGE_DISPUTE
    // AND: Deposit held for 7 days
    // AND: Guest notified
}

@Test
@DisplayName("TC-023: Late return with 59 minutes overage")
void testLateReturn59Minutes() {
    // GIVEN: Trip scheduled to end at 10:00 AM
    // AND: Grace period = 15 minutes
    // WHEN: Guest returns at 10:59 AM
    // THEN: lateMinutes = 59
    // AND: Late fee = 1 hour rate (ceiling)
}

@Test
@DisplayName("TC-024: Very late return (12 hours)")
void testVeryLateReturn() {
    // GIVEN: Trip ends at 10:00 AM
    // WHEN: Guest returns at 10:00 PM (12 hours late)
    // THEN: Late fee calculated for 12 hours
    // AND: Next booking (if any) potentially affected
}

@Test
@DisplayName("TC-025: Early return (2 days early)")
void testEarlyReturn() {
    // GIVEN: 5-day booking ending Friday
    // WHEN: Guest initiates checkout on Wednesday
    // THEN: lateReturnMinutes = negative (early indicator)
    // AND: Refund calculated per cancellation policy
}

@Test
@DisplayName("TC-026: Checkout without check-in record (system glitch)")
void testCheckoutWithoutCheckIn() {
    // GIVEN: Database corruption - check-in data missing
    // AND: Booking in IN_TRIP status
    // WHEN: Guest attempts checkout
    // THEN: Checkout allowed with warning
    // AND: Admin flagged for data review
}

@Test
@DisplayName("TC-027: End odometer less than start (rollback)")
void testOdometerRollback() {
    // GIVEN: Start odometer = 50,000 km
    // WHEN: Guest enters end odometer = 49,000 km
    // THEN: IllegalArgumentException thrown
    // AND: Message: "Završna kilometraža ne može biti manja od početne"
}

@Test
@DisplayName("TC-028: Fuel returned empty when full-to-full policy")
void testFuelReturnedEmpty() {
    // GIVEN: Car picked up at 75% fuel
    // WHEN: Returned at 10% fuel
    // THEN: IMPROPER_RETURN flagged (>25% difference)
    // AND: Fuel fee calculated
}

@Test
@DisplayName("TC-029: Checkout to different location")
void testCheckoutDifferentLocation() {
    // GIVEN: Pickup location = Belgrade
    // WHEN: Return GPS = Novi Sad (80km away)
    // THEN: WRONG_LOCATION flag set
    // AND: Admin review required
}

@Test
@DisplayName("TC-030: Keys not returned at checkout")
void testKeysNotReturned() {
    // GIVEN: Host inspecting returned car
    // WHEN: Host marks keysReturned = false
    // THEN: Checkout blocked
    // AND: Message: "Cannot complete checkout: confirm keys have been returned"
}

@Test
@DisplayName("TC-031: Consecutive booking buffer violation")
void testConsecutiveBookingBuffer() {
    // GIVEN: Booking A ends 5:00 PM
    // AND: Booking B starts 5:15 PM (within 30-min buffer)
    // WHEN: Guest A returns at 5:10 PM
    // THEN: Warning sent to Guest B about potential delay
    // AND: Buffer violation logged
}

@Test
@DisplayName("TC-032: Catastrophic damage (totaled car)")
void testCatastrophicDamage() {
    // GIVEN: Car returned totaled after accident
    // WHEN: Host reports with police report upload
    // THEN: Status = CHECKOUT_DAMAGE_DISPUTE
    // AND: Insurance claim flow triggered
    // AND: Platform admin immediately notified
}

@Test
@DisplayName("TC-033: Ghost trip (no check-in, no return)")
void testGhostTrip() {
    // GIVEN: Booking in CHECK_IN_OPEN status
    // AND: 48 hours past trip end time
    // WHEN: Scheduler runs ghost trip detection
    // THEN: Status = NO_SHOW_GUEST
    // AND: Potential theft investigation flag
}

@Test
@DisplayName("TC-034: Checkout during damage dispute timeout")
void testCheckoutDuringDisputeTimeout() {
    // GIVEN: Booking in CHECKOUT_DAMAGE_DISPUTE
    // AND: 7 days passed, no resolution
    // WHEN: Scheduler runs
    // THEN: Dispute auto-escalated to admin
    // AND: Deposit remains held
}

@Test
@DisplayName("TC-035: Concurrent checkout and damage claim")
void testConcurrentCheckoutAndDamageClaim() {
    // GIVEN: Guest submitting checkout
    // WHEN: Host files damage claim simultaneously
    // THEN: Race condition prevented by transaction
    // AND: Damage claim attached to checkout
}
```

---

## 3. BOOKING FLOW TESTS (15 scenarios)

```java
@Test
@DisplayName("TC-036: Book in the past")
void testBookInThePast() {
    // GIVEN: Current time = Feb 5, 2026
    // WHEN: Guest tries to book starting Feb 1, 2026
    // THEN: ValidationException thrown
    // AND: Message: "Cannot book in the past"
}

@Test
@DisplayName("TC-037: Same-day booking within lead time")
void testSameDayBookingWithinLeadTime() {
    // GIVEN: Now = 10:00 AM
    // AND: Car requires 2-hour advance notice
    // WHEN: Guest tries to book starting 11:00 AM
    // THEN: ValidationException thrown
    // AND: Message: "Rezervacija mora biti kreirana najmanje 2 sat(a) pre početka"
}

@Test
@DisplayName("TC-038: Maximum booking duration exceeded")
void testMaxBookingDurationExceeded() {
    // GIVEN: Car max trip = 30 days
    // WHEN: Guest tries to book 60 days
    // THEN: ValidationException thrown
    // AND: Message: "Maksimalno trajanje iznajmljivanja za ovaj automobil je 30 dana"
}

@Test
@DisplayName("TC-039: Concurrent booking race condition")
void testConcurrentBookingRace() {
    // GIVEN: Two users booking same car, same dates
    // WHEN: Both submit within 100ms
    // THEN: First wins (pessimistic lock)
    // AND: Second receives BookingConflictException
}

@Test
@DisplayName("TC-040: User double-booking themselves")
void testUserDoubleBooking() {
    // GIVEN: User has active booking Feb 5-10
    // WHEN: User tries to book Feb 8-12 (overlap)
    // THEN: UserOverlapException thrown
    // AND: Message: "Ne možete rezervisati dva vozila u isto vreme"
}

@Test
@DisplayName("TC-041: Price change during checkout")
void testPriceChangeDuringCheckout() {
    // GIVEN: Guest browsing at €50/day
    // AND: Owner changes price to €70/day
    // WHEN: Guest submits booking
    // THEN: Booking uses €70/day (current price)
    // AND: Guest sees updated total (no price lock - GAP-009)
}

@Test
@DisplayName("TC-042: Booking with expired payment method")
void testBookingExpiredPaymentMethod() {
    // GIVEN: Guest's card expires before trip
    // WHEN: Payment authorization attempted
    // THEN: PaymentResult.success = false
    // AND: Guest notified to update card
}

@Test
@DisplayName("TC-043: Owner deletes car with active booking")
void testOwnerDeletesCarWithBooking() {
    // GIVEN: Active booking exists for car
    // WHEN: Owner attempts to delete car listing
    // THEN: DeletionBlockedException thrown
    // AND: Message: "Cannot delete car with active bookings"
}

@Test
@DisplayName("TC-044: Pending approval timeout (48h)")
void testPendingApprovalTimeout() {
    // GIVEN: Booking in PENDING_APPROVAL
    // AND: 48 hours passed
    // WHEN: Scheduler runs
    // THEN: Status = EXPIRED_SYSTEM
    // AND: Guest refunded, dates freed
}

@Test
@DisplayName("TC-045: Short-notice booking deadline calculation")
void testShortNoticeDeadline() {
    // GIVEN: Booking for trip starting in 6 hours
    // AND: Normal approval window = 48 hours
    // WHEN: Approval deadline calculated
    // THEN: Deadline = MIN(now+48h, tripStart-1h) = now+5h
}

@Test
@DisplayName("TC-046: Leap year booking spanning Feb 28-29")
void testLeapYearBooking() {
    // GIVEN: Booking Feb 28 - Mar 2, 2028 (leap year)
    // WHEN: Duration calculated
    // THEN: Duration = 3 days (includes Feb 29)
    // AND: Price = 3 × daily rate
}

@Test
@DisplayName("TC-047: BigDecimal precision in price calculation")
void testBigDecimalPrecision() {
    // GIVEN: Daily rate = €33.33
    // AND: Insurance multiplier = 1.10
    // WHEN: 7-day booking calculated
    // THEN: Total = 33.33 × 7 × 1.10 = 256.64 (not 256.641)
    // AND: HALF_UP rounding applied
}

@Test
@DisplayName("TC-048: Booking with all optional fields null")
void testBookingNullOptionalFields() {
    // GIVEN: BookingRequestDTO with only required fields
    // AND: insuranceType=null, prepaidRefuel=null, pickupLocation=null
    // WHEN: Booking created
    // THEN: Defaults applied: insuranceType=BASIC, prepaidRefuel=false
    // AND: pickupLocation = car's home location
}

@Test
@DisplayName("TC-049: Delivery outside car's radius")
void testDeliveryOutsideRadius() {
    // GIVEN: Car delivery max radius = 25km
    // WHEN: Guest requests delivery 30km away
    // THEN: ValidationException thrown
    // AND: Message contains max radius and requested distance
}

@Test
@DisplayName("TC-050: Underage renter attempt")
void testUnderageRenter() {
    // GIVEN: User age = 19
    // AND: Minimum age = 21
    // WHEN: User attempts to book
    // THEN: RuntimeException thrown
    // AND: Message: "Drivers must be at least 21 years old"
}
```

---

## 4. DISPUTE FLOW TESTS (10 scenarios)

```java
@Test
@DisplayName("TC-051: Host damage claim within 48-hour window")
void testDamageClaimWithinWindow() {
    // GIVEN: Checkout completed 24 hours ago
    // WHEN: Host files damage claim
    // THEN: Claim created successfully
    // AND: Guest notified with 72-hour response deadline
}

@Test
@DisplayName("TC-052: Host damage claim after 48-hour window")
void testDamageClaimAfterWindow() {
    // GIVEN: Checkout completed 72 hours ago
    // WHEN: Host attempts to file claim
    // THEN: IllegalStateException thrown
    // AND: Message: "Rok za prijavu štete je istekao"
}

@Test
@DisplayName("TC-053: Guest disputes damage claim")
void testGuestDisputesClaim() {
    // GIVEN: Host filed damage claim
    // WHEN: Guest disputes with counter-evidence
    // THEN: Claim status = DISPUTED
    // AND: Admin review triggered
}

@Test
@DisplayName("TC-054: Both parties file claims")
void testBothPartiesFileClaims() {
    // GIVEN: Host claims damage
    // WHEN: Guest files counter-claim (vehicle issues)
    // THEN: Both claims linked to booking
    // AND: Consolidated admin review
}

@Test
@DisplayName("TC-055: Claim after deposit already released")
void testClaimAfterDepositReleased() {
    // GIVEN: Deposit released after 48 hours
    // WHEN: Host discovers damage on day 10
    // THEN: Can file claim but no deposit to capture
    // AND: Alternative recovery options presented
}

@Test
@DisplayName("TC-056: Fraudulent claim detection")
void testFraudulentClaimDetection() {
    // GIVEN: Host uses photo from different car
    // WHEN: Reverse image search or EXIF mismatch detected
    // THEN: Claim flagged as potentially fraudulent
    // AND: Host account under review
}

@Test
@DisplayName("TC-057: Check-in dispute blocks trip")
void testCheckInDisputeBlocksTrip() {
    // GIVEN: Guest disputes pre-existing damage at check-in
    // WHEN: Dispute filed
    // THEN: Status = CHECK_IN_DISPUTE
    // AND: Trip cannot start until resolved
}

@Test
@DisplayName("TC-058: Admin resolves dispute (split liability)")
void testAdminResolvesDisputeSplit() {
    // GIVEN: Damage claim for €1000
    // WHEN: Admin assigns 50/50 split
    // THEN: Guest charged €500
    // AND: Host receives €500
    // AND: Both parties notified
}

@Test
@DisplayName("TC-059: Dispute evidence deadline passed")
void testDisputeEvidenceDeadlinePassed() {
    // GIVEN: Claim filed 4 days ago
    // AND: Evidence deadline was 72 hours
    // WHEN: Party tries to submit evidence
    // THEN: Late evidence flagged
    // AND: Admin discretion to accept or reject
}

@Test
@DisplayName("TC-060: Insurance coverage exceeds deposit")
void testInsuranceCoverageExceedsDeposit() {
    // GIVEN: Damage = €5000
    // AND: Security deposit = €500
    // WHEN: Claim approved
    // THEN: €500 captured from deposit
    // AND: €4500 insurance claim initiated
}
```

---

## 5. PAYMENT FLOW TESTS (5 scenarios)

```java
@Test
@DisplayName("TC-061: Mock payment failure handling")
void testMockPaymentFailure() {
    // GIVEN: MockPaymentProvider with forceFailure=true
    // WHEN: Payment attempted
    // THEN: PaymentResult.success = false
    // AND: Booking not created or rolled back
}

@Test
@DisplayName("TC-062: Deposit authorization for 7 days")
void testDepositAuthorization() {
    // GIVEN: Trip starts in 5 days
    // WHEN: Deposit authorized at check-in
    // THEN: Authorization valid for 7 days
    // AND: If not captured, auto-released
}

@Test
@DisplayName("TC-063: Refund calculation with cancellation fee")
void testRefundWithCancellationFee() {
    // GIVEN: €1000 booking, <24h before trip
    // AND: Short trip (2 days) cancellation policy
    // WHEN: Guest cancels
    // THEN: Penalty = 1 day rate
    // AND: Refund = total - penalty
}

@Test
@DisplayName("TC-064: Idempotent payment retry")
void testIdempotentPaymentRetry() {
    // GIVEN: Payment request with idempotency key
    // AND: First request succeeded
    // WHEN: Network retry with same key
    // THEN: Cached result returned
    // AND: No duplicate charge
}

@Test
@DisplayName("TC-065: Currency handling (RSD to EUR future)")
void testCurrencyHandling() {
    // GIVEN: Booking in RSD
    // WHEN: Payment processed
    // THEN: Currency field = "RSD"
    // AND: All amounts in RSD
    // AND: No conversion errors
}
```

---

## Test Coverage Summary

| Category | Test Count | Coverage |
|----------|------------|----------|
| Check-In Flow | 20 | Happy paths, failures, concurrency, malicious, time |
| Check-Out Flow | 15 | Normal, damage, late return, edge cases |
| Booking Flow | 15 | Validation, race conditions, pricing |
| Dispute Flow | 10 | Claims, counter-claims, resolution |
| Payment Flow | 5 | Mock, authorization, refunds |
| **TOTAL** | **65** | All categories covered |

---

## Implementation Priority

### Must Have Before Launch
- TC-005, TC-010, TC-011 (Critical concurrency/security)
- TC-036-TC-040 (Booking validation)
- TC-051-TC-053 (Dispute basics)

### Should Have Week 1
- TC-013-TC-017 (Fraud prevention)
- TC-022-TC-024 (Checkout damage)
- TC-061-TC-064 (Payment reliability)

### Nice to Have Post-Launch
- TC-018-TC-020 (DST/timezone edge cases)
- TC-046-TC-047 (Calendar edge cases)
- TC-055-TC-060 (Advanced dispute scenarios)

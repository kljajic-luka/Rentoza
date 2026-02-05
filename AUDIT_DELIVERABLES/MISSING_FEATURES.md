# MISSING FEATURES - GAPS IN FUNCTIONALITY

**Audit Date:** February 5, 2026  
**Platform:** Rentoza P2P Car Rental Platform

---

## Overview

This document identifies functional gaps that, while not critical blockers, represent incomplete implementations that could impact user experience and platform reliability at scale.

---

## 1. CHECK-IN FLOW GAPS

### GAP-001: Offline Photo Queue Not Implemented

**Expected Behavior:** Renter in parking garage (no signal) can take photos, queue locally, upload when connection restored.

**Current State:** ❌ Not implemented

**Evidence:** Frontend component does not have offline storage:
```typescript
// host-check-in.component.ts
// No IndexedDB or localStorage photo caching found
```

**Impact:** Users in underground parking lose progress if they close app.

**Recommendation:**
```typescript
// Use IndexedDB via idb library
const photoQueue = await openDB('rentoza-offline', 1, {
  upgrade(db) {
    db.createObjectStore('pendingPhotos', { keyPath: 'id' });
  }
});

async function queuePhoto(photo: Blob, metadata: PhotoMetadata) {
  await photoQueue.add('pendingPhotos', { 
    id: generateUUID(), 
    photo, 
    metadata, 
    queuedAt: Date.now() 
  });
  navigator.serviceWorker.ready.then(sw => sw.sync.register('sync-photos'));
}
```

---

### GAP-002: No Vehicle Diagram for Damage Marking

**Expected Behavior:** Guest can mark damage on visual vehicle diagram (like insurance apps).

**Current State:** ⚠️ Partial - Hotspot marking exists but no diagram overlay

**Evidence:**
```typescript
// guest-check-in.component.ts
// Hotspots are marked on photos, but no standardized vehicle diagram
```

**Current Implementation:**
- ✅ Hotspot marking on photos (x%, y%)
- ✅ Hotspot description field
- ❌ No standardized vehicle diagram
- ❌ No pre-existing damage overlay from previous trips

**Recommendation:** 
1. Create vehicle silhouette SVG (front, rear, left, right, top views)
2. Allow tapping to add damage markers
3. Store damage location as normalized coordinates
4. Overlay previous trip's damage for comparison

---

### GAP-003: No Mileage Limit Exceeded Warning at Check-In

**Expected Behavior:** If car's current odometer already exceeds trip's mileage allowance, warn before trip starts.

**Current State:** ❌ Not implemented

**Scenario:**
- Booking includes 500km limit
- Check-in odometer: 100,500 km
- Car's last trip ended at: 100,400 km (100km used)
- If this trip drives 500km → Total 600km, but allowance is 500km

**Risk:** Guest could exceed mileage before trip even starts due to owner's personal use.

**Recommendation:**
```java
// CheckInService.completeHostCheckIn()
Integer previousEndOdometer = findPreviousTripEndOdometer(booking.getCar().getId());
if (previousEndOdometer != null) {
    int mileageSinceLast = dto.getOdometerReading() - previousEndOdometer;
    if (mileageSinceLast > 0) {
        eventService.recordEvent(booking, ..., 
            CheckInEventType.MILEAGE_GAP_DETECTED,
            Map.of("gap", mileageSinceLast, "previousEndOdometer", previousEndOdometer));
        
        // Notify guest
        notificationService.sendMileageGapWarning(booking, mileageSinceLast);
    }
}
```

---

### GAP-004: No GPS Spoofing Detection

**Expected Behavior:** Detect if user is using fake GPS app (e.g., for remote handoff fraud).

**Current State:** ❌ Not implemented

**Attack Vector:**
1. Guest uses MockGPS app
2. Fakes location to pickup point
3. Claims they're at car location
4. Receives lockbox code
5. Never actually at location

**Evidence:**
```java
// GeofenceService.java
// Only checks distance, not GPS accuracy or mock location flag
```

**Recommendation:**
```java
// Add to handshake confirmation DTO
private Boolean isMockLocation;  // From Android: Location.isFromMockProvider()
private Float horizontalAccuracy; // GPS accuracy in meters

// In GeofenceService
if (Boolean.TRUE.equals(dto.getIsMockLocation())) {
    eventService.recordEvent(..., CheckInEventType.MOCK_LOCATION_DETECTED, ...);
    throw new GeofenceViolationException("Mock location detected. Please disable GPS spoofing apps.");
}

if (dto.getHorizontalAccuracy() != null && dto.getHorizontalAccuracy() > 100) {
    // GPS accuracy too low - could be VPN/proxy
    log.warn("Low GPS accuracy: {} meters", dto.getHorizontalAccuracy());
}
```

---

### GAP-005: No Expired License Re-Check Before Trip Start

**Expected Behavior:** License verified at booking AND re-verified at check-in.

**Current State:** ⚠️ Partial - License checked at booking, but handshake only checks if strict mode enabled

**Evidence:**
```java
// CheckInService.confirmHandshake()
if (featureFlags.isStrictCheckinEnabled()) {  // ⚠️ Feature flag, not default
    BookingEligibilityDTO eligibility = renterVerificationService.checkBookingEligibility(...);
    if (!eligibility.isEligible()) {
        throw new ValidationException("Check-in blocked: " + eligibility.getMessageSr());
    }
}
```

**Risk:** License expires between booking (Feb 1) and trip start (Feb 15).

**Recommendation:** Make license re-verification mandatory at handshake, not just when strict mode enabled.

---

## 2. CHECK-OUT FLOW GAPS

### GAP-006: No AI Damage Detection

**Expected Behavior:** Computer vision compares check-in vs check-out photos for automatic damage detection.

**Current State:** ❌ Not implemented - Manual comparison only

**Competitive Analysis:**
- Turo: Uses Tractable AI for damage assessment
- Getaround: Has photo comparison with diff highlighting

**Recommendation (Phase 2):**
1. Integrate Google Cloud Vision or Tractable API
2. Run comparison job after checkout photos uploaded
3. Flag potential damage areas
4. Reduce false positives with confidence threshold
5. Human review for anything flagged

---

### GAP-007: Keys Return Verification Missing

**Expected Behavior:** System confirms keys returned before checkout completion.

**Current State:** ❌ Not implemented

**Scenario:**
1. Guest returns car
2. Guest completes checkout
3. Guest forgets to return keys
4. Deposit released
5. Host discovers missing keys → No recourse

**Recommendation:**
```java
// HostCheckOutConfirmationDTO
private Boolean keysReturned;  // Required field

// CheckOutService.confirmHostCheckout()
if (dto.getKeysReturned() == null || !dto.getKeysReturned()) {
    throw new IllegalStateException("Cannot complete checkout: confirm keys have been returned");
}
```

---

### GAP-008: No Buffer Time Between Consecutive Bookings

**Expected Behavior:** 30-60 minute buffer between checkout and next check-in for owner inspection.

**Current State:** ❌ Not enforced - Back-to-back bookings allowed

**Evidence:**
```java
// BookingRepository - No buffer check
@Query("SELECT COUNT(b) > 0 FROM Booking b WHERE b.car.id = :carId " +
       "AND b.status IN (...) " +
       "AND b.startTime < :endTime AND b.endTime > :startTime")
boolean existsOverlappingBookingsWithLock(...);
// ⚠️ No buffer enforcement
```

**Scenario:**
1. Booking A: ends 5:00 PM
2. Booking B: starts 5:00 PM (allowed!)
3. Guest A returns at 5:15 PM (late)
4. Guest B arrives at 5:00 PM
5. Car not available → Both guests angry

**Recommendation:**
```java
// Add buffer to overlap check
@Query("SELECT COUNT(b) > 0 FROM Booking b WHERE b.car.id = :carId " +
       "AND b.status IN (...) " +
       "AND (b.startTime - :bufferMinutes) < :endTime " +
       "AND (b.endTime + :bufferMinutes) > :startTime")
boolean existsOverlappingBookingsWithBuffer(
    @Param("carId") Long carId,
    @Param("startTime") LocalDateTime startTime,
    @Param("endTime") LocalDateTime endTime,
    @Param("bufferMinutes") int bufferMinutes
);
```

---

## 3. BOOKING FLOW GAPS

### GAP-009: No Price Lock During Checkout

**Expected Behavior:** Price shown at browse should be guaranteed through checkout.

**Current State:** ⚠️ Price recalculated at booking creation time

**Risk:**
1. Guest sees €50/day
2. Owner changes to €70/day while guest fills booking form
3. Guest submits → Gets €70/day price
4. Guest feels deceived

**Recommendation:**
```java
// Add price quote endpoint with TTL
@PostMapping("/api/cars/{carId}/price-quote")
public PriceQuoteDTO generatePriceQuote(@PathVariable Long carId, @RequestBody QuoteRequestDTO request) {
    PriceQuote quote = priceQuoteService.generate(carId, request);
    // Store in Redis with 15-minute TTL
    // Return quote token
    return new PriceQuoteDTO(quote.getToken(), quote.getTotalPrice(), quote.getExpiresAt());
}

// At booking creation
if (dto.getPriceQuoteToken() != null) {
    PriceQuote quote = priceQuoteService.validate(dto.getPriceQuoteToken());
    booking.setTotalPrice(quote.getTotalPrice()); // Use locked price
}
```

---

### GAP-010: No Counter-Offer for Request-to-Book

**Expected Behavior:** Owner can propose different dates/price instead of just approve/decline.

**Current State:** ❌ Binary approve/decline only

**Evidence:**
```java
// BookingService - Only approve or decline
public Booking approveBooking(Long bookingId, Long ownerId) { ... }
public Booking declineBooking(Long bookingId, Long ownerId, String reason) { ... }
// No counter-offer method
```

**Impact:** Owner must decline and tell guest to rebook manually → Poor UX.

---

### GAP-011: Weekend/Peak Pricing Not Implemented

**Expected Behavior:** Different daily rates for weekends, holidays, peak seasons.

**Current State:** ❌ Single daily rate only

**Evidence:**
```java
// Car.java
@Column(name = "price_per_day", nullable = false, precision = 19, scale = 2)
private BigDecimal pricePerDay;
// No weekend/holiday rate fields
```

**Competitive Gap:** Turo and Getaround both support dynamic pricing.

---

## 4. DISPUTE FLOW GAPS

### GAP-012: No Evidence Expiry for Disputes

**Expected Behavior:** Clear deadline for submitting dispute evidence.

**Current State:** ⚠️ 48-hour claim window, but no evidence submission deadline after claim filed.

**Scenario:**
1. Day 1: Host files claim
2. Day 30: Host finally submits photos
3. Guest cannot dispute effectively (too much time passed)

**Recommendation:**
```java
// DamageClaim.java
@Column(name = "evidence_deadline")
private Instant evidenceDeadline; // 72 hours after claim creation

// DamageClaimService
if (claim.getEvidenceDeadline() != null && 
    Instant.now().isAfter(claim.getEvidenceDeadline())) {
    throw new IllegalStateException("Evidence submission deadline passed");
}
```

---

### GAP-013: No Mediation Call Scheduling

**Expected Behavior:** For complex disputes, schedule video call between parties and admin.

**Current State:** ❌ Text-based resolution only

**Recommendation:** Integrate Calendly or similar for dispute mediation scheduling.

---

### GAP-014: No Automatic Photo Comparison in Dispute View

**Expected Behavior:** Admin sees check-in vs check-out photos side-by-side with diff highlighting.

**Current State:** ❌ Manual photo review only

---

## 5. PAYMENT FLOW GAPS

### GAP-015: Currency Field Missing for Future Multi-Currency

**Expected Behavior:** Support EUR and RSD (Serbian Dinar).

**Current State:** ⚠️ Hardcoded to RSD

**Evidence:**
```java
// BookingPaymentService.java
private static final String DEFAULT_CURRENCY = "RSD";
// No currency field on Booking entity
```

**Recommendation:**
```java
// Add to Booking.java
@Column(name = "currency", length = 3)
private String currency = "RSD";

// Add to PaymentRequest
private String currency;
```

---

### GAP-016: No Split Payment Support

**Expected Behavior:** Guest can pay 50% now, 50% at pickup.

**Current State:** ❌ Full payment at booking only

---

### GAP-017: No Refund Status Tracking

**Expected Behavior:** Track refund processing status (pending, completed, failed).

**Current State:** ⚠️ Refund initiated but no status tracking

**Evidence:**
```java
// MockPaymentProvider.refund() always returns success immediately
// No database tracking of refund status
```

---

## 6. NOTIFICATION GAPS

### GAP-018: No SMS Fallback for Critical Notifications

**Expected Behavior:** SMS sent for critical events if push fails.

**Current State:** ❌ Push/email only

**Critical events needing SMS:
- No-show triggered
- Damage claim filed
- Account suspended

---

### GAP-019: No Notification Preferences

**Expected Behavior:** User can configure which notifications they receive.

**Current State:** ❌ All or nothing

---

## Priority Matrix

| Gap | Impact | Effort | Priority |
|-----|--------|--------|----------|
| GAP-001 (Offline) | High | High | P2 |
| GAP-004 (GPS Spoof) | Critical | Medium | P1 |
| GAP-005 (License Re-check) | High | Low | P1 |
| GAP-007 (Keys Return) | High | Low | P1 |
| GAP-008 (Buffer Time) | High | Low | P1 |
| GAP-009 (Price Lock) | Medium | Medium | P2 |
| GAP-015 (Multi-Currency) | Medium | Medium | P2 |
| GAP-018 (SMS Fallback) | High | Medium | P1 |

---

## Recommended Implementation Order

### Phase 1 (Pre-Launch - 2 weeks)
1. GAP-005: License re-verification at handshake
2. GAP-007: Keys return confirmation
3. GAP-008: Buffer time enforcement
4. GAP-004: GPS spoofing detection

### Phase 2 (Post-Launch - 1 month)
1. GAP-018: SMS fallback
2. GAP-001: Offline photo queue
3. GAP-009: Price lock quotes

### Phase 3 (Roadmap)
1. GAP-006: AI damage detection
2. GAP-002: Vehicle damage diagram
3. GAP-015: Multi-currency support

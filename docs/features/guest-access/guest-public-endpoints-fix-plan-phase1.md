# Phase 1: Guest Public Endpoints Fix Plan

> **Status:** ✅ COMPLETED
> **Date:** 2025-01-23
> **Scope:** Backend SecurityConfig + Controller annotation only

---

## Pre-Implementation Scan Summary

### Files Scanned
| File | Status | Notes |
|------|--------|-------|
| `SecurityConfig.java` | Scanned | Missing permitAll() for 4 public endpoints |
| `BookingController.java` | Scanned | Has correct @PreAuthorize("permitAll()") on line 133 |
| `CarController.java` | Scanned | Has correct @PreAuthorize("permitAll()") on line 200 |
| `OwnerProfileController.java` | Scanned | Has correct @PreAuthorize("permitAll()") on line 31 |
| `BlockedDateController.java` | Scanned | **MISSING** @PreAuthorize annotation on getBlockedDates() |
| `JwtAuthFilter.java` | Scanned | No changes needed - passes through when no token |

---

## Root Cause Confirmed

**SecurityConfig.java lines 124-163** has these public rules:
```java
.requestMatchers(HttpMethod.GET, "/api/cars", "/api/cars/{id}").permitAll()
.requestMatchers(HttpMethod.GET, "/api/reviews/car/**").permitAll()
```

**Missing from permitAll():**
- `GET /api/bookings/car/{carId}/public`
- `GET /api/availability/{carId}`
- `GET /api/owners/{id}/public-profile`
- `GET /api/cars/availability-search`
- `GET /api/cars/search`
- `GET /api/cars/features`
- `GET /api/cars/makes`
- `GET /api/cars/location/{location}`

---

## Phase 1 Implementation Plan

### Step 1: Update SecurityConfig.java

**Location:** Lines 124-163 in `authorizeHttpRequests()` block

**Action:** Add permitAll() rules for all public GET endpoints BEFORE the authenticated rules.

**New Rules to Add:**
```java
// PUBLIC CAR MARKETPLACE (expanded)
.requestMatchers(HttpMethod.GET,
    "/api/cars",
    "/api/cars/search",
    "/api/cars/availability-search",
    "/api/cars/features",
    "/api/cars/makes"
).permitAll()
.requestMatchers(HttpMethod.GET, "/api/cars/{id}").permitAll()
.requestMatchers(HttpMethod.GET, "/api/cars/location/**").permitAll()

// PUBLIC AVAILABILITY DATA (NEW)
.requestMatchers(HttpMethod.GET, "/api/bookings/car/*/public").permitAll()
.requestMatchers(HttpMethod.GET, "/api/availability/*").permitAll()

// PUBLIC OWNER PROFILES (NEW)
.requestMatchers(HttpMethod.GET, "/api/owners/*/public-profile").permitAll()
```

**Rule Ordering (Critical):**
1. Auth endpoints (permitAll)
2. OAuth2 endpoints (permitAll)
3. Static resources (permitAll)
4. Public car marketplace (permitAll) ← EXPANDED
5. Public reviews (permitAll)
6. **Public availability data (permitAll) ← NEW**
7. **Public owner profiles (permitAll) ← NEW**
8. User endpoints (authenticated)
9. Booking user endpoints (authenticated)
10. Internal service endpoints (INTERNAL_SERVICE)
11. Favorites (authenticated)
12. Catch-all (authenticated)

### Step 2: Update BlockedDateController.java

**Location:** Line 37, `getBlockedDates()` method

**Action:** Add `@PreAuthorize("permitAll()")` annotation for defense-in-depth

**Before:**
```java
@GetMapping("/{carId}")
public ResponseEntity<?> getBlockedDates(@PathVariable Long carId) {
```

**After:**
```java
@GetMapping("/{carId}")
@PreAuthorize("permitAll()")
public ResponseEntity<?> getBlockedDates(@PathVariable Long carId) {
```

---

## Security Verification Checklist

| Check | Status |
|-------|--------|
| Only GET endpoints made public | ✅ Confirmed |
| No PII exposed in public DTOs | ✅ Verified (BookingSlotDTO, BlockedDateResponseDTO) |
| No mutation endpoints affected | ✅ POST/PUT/DELETE unchanged |
| Internal service endpoints preserved | ✅ Rule ordering maintained |
| Owner-only endpoints preserved | ✅ RLS rules intact |

---

## Validation Plan

After implementation:
1. `./mvnw compile` - Verify compilation
2. `curl http://localhost:8080/api/bookings/car/1/public` - Should return 200
3. `curl http://localhost:8080/api/availability/1` - Should return 200
4. `curl http://localhost:8080/api/owners/1/public-profile` - Should return 200
5. `curl http://localhost:8080/api/cars/availability-search?...` - Should return 200

---

## ✅ IMPLEMENTATION COMPLETE

**Changes Made:**

1. **SecurityConfig.java** - Added `permitAll()` rules for:
   - `GET /api/cars/search`
   - `GET /api/cars/availability-search`
   - `GET /api/cars/features`
   - `GET /api/cars/makes`
   - `GET /api/cars/location/**`
   - `GET /api/bookings/car/*/public`
   - `GET /api/availability/*`
   - `GET /api/owners/*/public-profile`

2. **BlockedDateController.java** - Added `@PreAuthorize("permitAll()")` annotation

**Compilation:** ✅ Successful (`./mvnw compile` passed)

**Next Steps:**
- Start backend server and test endpoints manually
- Verify guest access works in browser (clear cookies, navigate to car detail page)

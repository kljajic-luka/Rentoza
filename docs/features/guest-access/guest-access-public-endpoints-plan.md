# Guest Access Public Endpoints Fix Plan

> **Status:** Pending Approval
> **Date:** 2025-01-23
> **Issue:** Non-logged-in users (guests) receive 401 Unauthorized when accessing public resources

---

## A. Root Cause Report

### Summary

The issue stems from **Spring Security's `HttpSecurity` configuration not including public-facing endpoints in its `permitAll()` rules**, while controllers have `@PreAuthorize("permitAll()")` annotations that never execute because requests are rejected at the filter chain level before reaching the controller.

### Detailed Analysis

#### 1. SecurityConfig.java - Missing permitAll() Rules

**File:** `Rentoza/src/main/java/org/example/rentoza/security/SecurityConfig.java`

Current `permitAll()` rules (lines 124-142):
```java
.authorizeHttpRequests(auth -> auth
    // Auth endpoints
    .requestMatchers("/api/auth/**").permitAll()
    .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
    // Static resources
    .requestMatchers(HttpMethod.GET, "/uploads/**").permitAll()
    // Car marketplace (PUBLIC)
    .requestMatchers(HttpMethod.GET, "/api/cars", "/api/cars/{id}").permitAll()
    // Reviews (PUBLIC)
    .requestMatchers(HttpMethod.GET, "/api/reviews/car/**").permitAll()
    // ... authenticated rules ...
    .anyRequest().authenticated()  // <-- CATCH-ALL
)
```

**Missing from permitAll():**

| Endpoint | Controller Annotation | In SecurityConfig? | Result |
|----------|----------------------|-------------------|--------|
| `GET /api/bookings/car/{carId}/public` | `@PreAuthorize("permitAll()")` | ❌ NO | 401 |
| `GET /api/availability/{carId}` | None | ❌ NO | 401 |
| `GET /api/owners/{id}/public-profile` | `@PreAuthorize("permitAll()")` | ❌ NO | 401 |
| `GET /api/cars/availability-search` | `@PreAuthorize("permitAll()")` | ❌ NO | 401 |

#### 2. Why @PreAuthorize("permitAll()") Doesn't Work Alone

Spring Security evaluates authorization in this order:

```
Request → Security Filters → HttpSecurity Rules → Controller → @PreAuthorize
```

When a request reaches `.anyRequest().authenticated()`:
1. Spring Security checks if user is authenticated
2. If not authenticated → **401 Unauthorized** response
3. Controller method is **NEVER invoked**
4. `@PreAuthorize("permitAll()")` is **NEVER evaluated**

The `@PreAuthorize("permitAll()")` annotation only provides defense-in-depth for endpoints already allowed by `HttpSecurity`. It cannot override `HttpSecurity` rules.

#### 3. Problematic Pattern in SecurityConfig (Line 160)

```java
.requestMatchers(HttpMethod.GET, "/api/bookings/*").hasAuthority("INTERNAL_SERVICE")
```

This pattern uses a single `*` which in Spring Security's path matching:
- `/*` = matches single path segment only
- `/api/bookings/*` matches `/api/bookings/123` but NOT `/api/bookings/car/123`

So `/api/bookings/car/{carId}/public` is NOT matched by this rule and falls through to `.anyRequest().authenticated()`.

#### 4. Frontend Is NOT The Problem

**Token Interceptor Analysis** (`rentoza-frontend/src/app/core/auth/token.interceptor.ts`):

```typescript
const enrichRequest = (request, token, skipAuth, markRetried) => {
  // ...
  if (!skipAuth && token) {  // <-- Only adds header if token EXISTS
    cloned = cloned.clone({
      setHeaders: { Authorization: `Bearer ${token}` }
    });
  }
  return cloned;
};
```

The interceptor correctly:
- Only adds `Authorization` header when a token exists
- Does NOT send empty or invalid tokens for guests
- Gracefully handles unauthenticated users

**Service Calls** (`car-detail.component.ts` lines 219-244):
- Calls use `catchError` to gracefully handle failures
- But the 401 is still thrown and logged in console

### Affected Components

| Layer | Component | File | Impact |
|-------|-----------|------|--------|
| Backend | SecurityConfig | `SecurityConfig.java:124-163` | Missing permitAll rules |
| Backend | BookingController | `BookingController.java:132-142` | Has correct annotation, blocked by SecurityConfig |
| Backend | BlockedDateController | `BlockedDateController.java:37-47` | Missing @PreAuthorize annotation |
| Backend | OwnerProfileController | `OwnerProfileController.java:30-53` | Has correct annotation, blocked by SecurityConfig |
| Backend | CarController | `CarController.java:199-268` | Has correct annotation, blocked by SecurityConfig |
| Frontend | car-detail.component | `car-detail.component.ts:219-244` | Receives 401, gracefully handles with catchError |

---

## B. Security Matrix

### Public Endpoints (No Authentication Required)

| Method | Endpoint | Purpose | Current Status | Required Status |
|--------|----------|---------|----------------|-----------------|
| GET | `/api/cars` | List all cars | ✅ Public | ✅ Public |
| GET | `/api/cars/{id}` | Car details | ✅ Public | ✅ Public |
| GET | `/api/cars/search` | Search cars | ⚠️ Implicit permitAll | ✅ Explicit Public |
| GET | `/api/cars/availability-search` | Time-aware search | ❌ 401 | ✅ Public |
| GET | `/api/cars/features` | Feature list | ⚠️ Implicit permitAll | ✅ Explicit Public |
| GET | `/api/cars/makes` | Make list | ⚠️ Implicit permitAll | ✅ Explicit Public |
| GET | `/api/cars/location/{location}` | Cars by location | ⚠️ Implicit permitAll | ✅ Explicit Public |
| GET | `/api/reviews/car/**` | Car reviews | ✅ Public | ✅ Public |
| GET | `/api/bookings/car/{carId}/public` | Public booking slots | ❌ 401 | ✅ Public |
| GET | `/api/availability/{carId}` | Blocked dates | ❌ 401 | ✅ Public |
| GET | `/api/owners/{id}/public-profile` | Owner profile | ❌ 401 | ✅ Public |

### Private Endpoints (Authentication Required)

| Method | Endpoint | Purpose | Role Restriction |
|--------|----------|---------|------------------|
| GET | `/api/bookings/me` | User's bookings | Authenticated |
| POST | `/api/bookings` | Create booking | Authenticated |
| PUT | `/api/bookings/cancel/{id}` | Cancel booking | Authenticated (RLS: renter) |
| GET | `/api/bookings/car/{carId}` | Full booking details | OWNER/ADMIN |
| GET | `/api/bookings/pending` | Pending approvals | OWNER/ADMIN |
| PUT | `/api/bookings/{id}/approve` | Approve booking | OWNER/ADMIN (RLS) |
| PUT | `/api/bookings/{id}/decline` | Decline booking | OWNER/ADMIN (RLS) |
| GET | `/api/bookings/{id}` | Booking by ID | INTERNAL_SERVICE |
| POST | `/api/availability/block` | Block dates | OWNER (RLS) |
| DELETE | `/api/availability/block/{id}` | Unblock dates | OWNER (RLS) |

### Internal Service Endpoints

| Method | Endpoint | Purpose | Authority |
|--------|----------|---------|-----------|
| GET | `/api/bookings/*` | Internal lookup | INTERNAL_SERVICE |
| GET | `/api/bookings/debug/**` | Debug endpoints | INTERNAL_SERVICE |
| GET | `/api/users/profile/*` | User profile lookup | INTERNAL_SERVICE |

---

## C. Recommended Fix Strategy

### Phase 1: SecurityConfig permitAll() Update (Backend)

**File:** `Rentoza/src/main/java/org/example/rentoza/security/SecurityConfig.java`

**Changes Required:**

```java
.authorizeHttpRequests(auth -> auth
    // ============ PUBLIC AUTH ENDPOINTS ============
    .requestMatchers(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/api/auth/logout",
            "/api/auth/google/**"
    ).permitAll()
    .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()

    // ============ PUBLIC STATIC RESOURCES ============
    .requestMatchers(HttpMethod.GET, "/uploads/**").permitAll()

    // ============ PUBLIC CAR MARKETPLACE ============
    // Car browsing, search, and availability (guest-accessible)
    .requestMatchers(HttpMethod.GET,
            "/api/cars",
            "/api/cars/search",
            "/api/cars/availability-search",
            "/api/cars/features",
            "/api/cars/makes",
            "/api/cars/location/**"
    ).permitAll()
    .requestMatchers(HttpMethod.GET, "/api/cars/{id}").permitAll()

    // ============ PUBLIC REVIEWS ============
    .requestMatchers(HttpMethod.GET, "/api/reviews/car/**").permitAll()

    // ============ PUBLIC AVAILABILITY DATA ============
    // Calendar availability for booking UI (no PII exposure)
    .requestMatchers(HttpMethod.GET, "/api/bookings/car/*/public").permitAll()
    .requestMatchers(HttpMethod.GET, "/api/availability/*").permitAll()

    // ============ PUBLIC OWNER PROFILES ============
    .requestMatchers(HttpMethod.GET, "/api/owners/*/public-profile").permitAll()

    // ... rest of authenticated/role-restricted rules ...
)
```

**Important Notes:**
- Use `*` (single segment) carefully - `/api/bookings/car/*/public` matches `/api/bookings/car/123/public`
- Patterns are evaluated top-to-bottom; more specific rules should come before general ones
- The `/api/bookings/*` INTERNAL_SERVICE rule must come AFTER the public `/api/bookings/car/*/public` rule

### Phase 2: Controller Annotation Alignment (Backend)

Ensure all public endpoints have consistent `@PreAuthorize("permitAll()")` annotations for defense-in-depth:

**File:** `BlockedDateController.java`
```java
@GetMapping("/{carId}")
@PreAuthorize("permitAll()")  // <-- ADD THIS
public ResponseEntity<?> getBlockedDates(@PathVariable Long carId) { ... }
```

**Already Correct:**
- `BookingController.getPublicBookingsForCar()` - Has `@PreAuthorize("permitAll()")`
- `CarController.searchAvailableCars()` - Has `@PreAuthorize("permitAll()")`
- `OwnerProfileController.getOwnerPublicProfile()` - Has `@PreAuthorize("permitAll()")`

### Phase 3: Frontend Verification (No Changes Expected)

The frontend code is already correct:
- `car-detail.component.ts` uses `catchError` for graceful degradation
- `token.interceptor.ts` only sends tokens when they exist
- Services make standard HTTP calls without forcing authentication

**Verification Steps:**
1. Clear browser storage and cookies
2. Navigate to car detail page as guest
3. Verify no 401 errors in Network tab
4. Verify calendar shows unavailable dates
5. Verify owner profile link works

### Phase 4: DTO Security Audit (No Changes Expected)

Verify public DTOs don't leak PII:

| DTO | Exposes PII? | Assessment |
|-----|--------------|------------|
| `BookingSlotDTO` | ❌ No | Only carId, startDate, endDate |
| `BlockedDateResponseDTO` | ❌ No | Only id, carId, startDate, endDate |
| `OwnerPublicProfileDTO` | ❌ No | firstName, profileImageUrl, rating, carCount |
| `CarResponseDTO` | ❌ No | Public car details, no license plates |

---

## D. Regression Impact Analysis

### 1. PII Leakage Risk Assessment

| Endpoint | Risk | Mitigation |
|----------|------|------------|
| `/api/bookings/car/{id}/public` | LOW | Returns only date ranges, no renter/owner info |
| `/api/availability/{carId}` | LOW | Returns only blocked date ranges |
| `/api/owners/{id}/public-profile` | LOW | Returns public-safe profile (no email, phone) |
| `/api/cars/availability-search` | LOW | Returns car list, no booking details |

**Conclusion:** No PII leakage risk from proposed changes.

### 2. Endpoint Logic Sharing Analysis

| Public Endpoint | Shares Logic With | Risk |
|-----------------|-------------------|------|
| `/api/bookings/car/{id}/public` | `/api/bookings/car/{id}` (OWNER only) | NONE - Different service methods |
| `/api/availability/{carId}` | POST/DELETE `/api/availability/block` | NONE - Read vs. Write separation |
| `/api/owners/{id}/public-profile` | Internal profile endpoints | NONE - Different DTO projection |

**Conclusion:** No private endpoint logic is accidentally exposed.

### 3. Caching Considerations

| Endpoint | Current Caching | Guest Access Impact |
|----------|-----------------|---------------------|
| `/api/bookings/car/{id}/public` | No caching | ✅ Safe - fresh data always |
| `/api/availability/{carId}` | No caching | ✅ Safe - fresh data always |
| `/api/owners/{id}/public-profile` | 5 min (no filter), no-store (with filter) | ✅ Safe - already public-safe |

**Conclusion:** No caching issues introduced.

### 4. Rate Limiting Impact

Current rate limits apply regardless of authentication status:
- IP-based rate limiting for anonymous requests
- User-based rate limiting for authenticated requests

**Recommendation:** Consider adding specific rate limits for public endpoints:
```yaml
app:
  rate-limit:
    endpoints:
      GET:/api/bookings/car/*/public: 60/minute
      GET:/api/availability/*: 60/minute
      GET:/api/owners/*/public-profile: 60/minute
```

---

## E. Final Multi-Phase Implementation Roadmap

### Phase 1: Backend Security Config (HIGH PRIORITY)

**Objective:** Add public endpoints to SecurityConfig permitAll() rules

**Files to Modify:**
- `Rentoza/src/main/java/org/example/rentoza/security/SecurityConfig.java`

**Changes:**
1. Add permitAll() rules for:
   - `GET /api/bookings/car/*/public`
   - `GET /api/availability/*`
   - `GET /api/owners/*/public-profile`
   - `GET /api/cars/availability-search`
   - `GET /api/cars/search`
   - `GET /api/cars/features`
   - `GET /api/cars/makes`
   - `GET /api/cars/location/**`

2. Ensure rule ordering:
   - Public rules BEFORE private rules
   - Specific paths BEFORE wildcard patterns
   - Internal service rules AFTER public rules

**Validation:**
- Run existing tests: `./mvnw test`
- Manual test: curl requests without Authorization header

### Phase 2: Controller Annotation Update (MEDIUM PRIORITY)

**Objective:** Add @PreAuthorize("permitAll()") for defense-in-depth

**Files to Modify:**
- `Rentoza/src/main/java/org/example/rentoza/availability/BlockedDateController.java`

**Changes:**
1. Add `@PreAuthorize("permitAll()")` to `getBlockedDates()` method

**Validation:**
- Compile: `./mvnw compile`
- Verify annotation presence via code review

### Phase 3: Frontend Verification (LOW PRIORITY)

**Objective:** Confirm frontend gracefully handles public access

**Files to Verify (No Changes):**
- `car-detail.component.ts`
- `booking.service.ts`
- `availability.service.ts`
- `token.interceptor.ts`

**Validation Steps:**
1. Clear all browser storage
2. Navigate to `/cars/{id}` as guest
3. Verify:
   - Car details load successfully
   - Calendar shows unavailable dates
   - Owner profile link works
   - No 401 errors in console
   - No authentication prompts

### Phase 4: Regression QA (CRITICAL)

**Objective:** Ensure changes don't break existing flows

**Test Matrix:**

| Scenario | Expected Result |
|----------|-----------------|
| Guest views car detail | ✅ 200 OK, all data loads |
| Guest views owner profile | ✅ 200 OK, public profile loads |
| Guest tries to book | ✅ Redirected to login |
| User views car detail | ✅ 200 OK, all data loads |
| User creates booking | ✅ 200 OK, booking created |
| Owner views car bookings | ✅ 200 OK, full booking list |
| Owner blocks dates | ✅ 200 OK, dates blocked |
| Internal service accesses bookings | ✅ 200 OK with service token |
| Invalid token on public endpoint | ✅ 200 OK (public access) |
| Valid token on private endpoint | ✅ 200 OK (authenticated) |
| No token on private endpoint | ✅ 401 Unauthorized |

---

## Appendix: File Reference

### Backend Files to Modify
1. `Rentoza/src/main/java/org/example/rentoza/security/SecurityConfig.java` - Add permitAll rules
2. `Rentoza/src/main/java/org/example/rentoza/availability/BlockedDateController.java` - Add @PreAuthorize

### Backend Files to Verify (No Changes)
- `Rentoza/src/main/java/org/example/rentoza/booking/BookingController.java`
- `Rentoza/src/main/java/org/example/rentoza/car/CarController.java`
- `Rentoza/src/main/java/org/example/rentoza/user/OwnerProfileController.java`
- `Rentoza/src/main/java/org/example/rentoza/security/JwtAuthFilter.java`

### Frontend Files to Verify (No Changes)
- `rentoza-frontend/src/app/features/cars/pages/car-detail/car-detail.component.ts`
- `rentoza-frontend/src/app/core/services/booking.service.ts`
- `rentoza-frontend/src/app/core/services/availability.service.ts`
- `rentoza-frontend/src/app/core/auth/token.interceptor.ts`

---

## Awaiting Approval

Please review this plan and confirm approval before proceeding with Phase 1 implementation.

**Estimated Implementation Time:**
- Phase 1: ~15 minutes
- Phase 2: ~5 minutes
- Phase 3: ~10 minutes (verification only)
- Phase 4: ~20 minutes (manual QA)

**Total: ~50 minutes**

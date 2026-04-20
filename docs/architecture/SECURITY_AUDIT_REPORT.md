# Security Audit Report: 401 Unauthorized on Check-In Exit Flow

**Date:** November 29, 2025  
**Auditor:** Principal Software Architect  
**Issue:** Owner receives 401 Unauthorized when navigating back from Check-In Dialog  
**Severity:** High (Breaks core workflow)

---

## 1. Endpoint Signature & Security Annotations

### Target Endpoint
```java
// BookingController.java:143-163
@GetMapping("/{id}")
@PreAuthorize("@bookingSecurity.canAccessBooking(#id, authentication.principal.id) or hasRole('ADMIN')")
public ResponseEntity<?> getBookingById(@PathVariable Long id)
```

### Security Expression Implementation
```java
// BookingSecurityService.java:50-70
public boolean canAccessBooking(Long bookingId, Long userId) {
    // Admin bypass
    if (currentUser.isAdmin()) {
        return true;
    }

    Booking booking = bookingRepository.findByIdWithRelations(bookingId).orElse(null);
    if (booking == null) {
        return false;  // Returns false, NOT throws exception
    }

    // Check ownership: user is renter OR car owner
    boolean isRenter = booking.getRenter().getId().equals(userId);
    boolean isOwner = booking.getCar().getOwner().getId().equals(userId);

    return isRenter || isOwner;
}
```

---

## 2. Access Control Matrix

| Role | Booking State | `isRenter` | `isOwner` | Access Granted | Expected HTTP |
|------|---------------|------------|-----------|----------------|---------------|
| Guest (Renter) | Any | ✅ | ❌ | ✅ | 200 |
| Host (Owner) | Any | ❌ | ✅ | ✅ | 200 |
| Admin | Any | N/A | N/A | ✅ (bypass) | 200 |
| Other User | Any | ❌ | ❌ | ❌ | **403 Forbidden** |
| Unauthenticated | Any | N/A | N/A | ❌ | **401 Unauthorized** |

**Key Observation:** The security expression logic is **state-agnostic**. The booking status (`CHECK_IN_OPEN`, `HOST_SUBMITTED`, etc.) has **zero impact** on access control.

---

## 3. Root Cause Analysis

### 3.1 The 401 vs 403 Distinction

| HTTP Status | Meaning | Trigger Condition |
|-------------|---------|-------------------|
| **401 Unauthorized** | No valid authentication | Missing/expired/invalid JWT |
| **403 Forbidden** | Authenticated but not authorized | `@PreAuthorize` returns `false` |

**Critical Finding:** The client receives **401**, not 403. This means **the user is not authenticated at all** when the retry request is made.

### 3.2 Root Cause: Cross-Origin Cookie Synchronization Failure

The issue is a **cross-origin cookie handling problem** in modern browsers:

#### Technical Details

1. **Frontend Origin:** `http://localhost:4200` (Angular dev server)
2. **Backend Origin:** `http://localhost:8080` (Spring Boot)
3. **Cookie Scope:** Backend sets `access_token` cookie on `localhost:8080`

#### The Problem Sequence

```
┌─────────────────────────────────────────────────────────────────────┐
│ 1. Initial Request: GET /api/bookings/60                            │
│    → Access token cookie EXPIRED                                    │
│    → Backend returns 401 "JWT token expired"                        │
├─────────────────────────────────────────────────────────────────────┤
│ 2. Token Refresh: POST /api/auth/refresh                            │
│    → Refresh cookie sent (path=/api/auth/refresh)                   │
│    → Backend rotates tokens, sets NEW access_token cookie           │
│    → Response: 200 OK with Set-Cookie headers                       │
├─────────────────────────────────────────────────────────────────────┤
│ 3. Retry Request: GET /api/bookings/60                              │
│    → NEW access_token cookie NOT SENT by browser                    │
│    → Backend sees NO access_token                                   │
│    → Backend returns 401                                            │
└─────────────────────────────────────────────────────────────────────┘
```

#### Why Cookies Aren't Sent on Retry

| Factor | Current State | Problem |
|--------|---------------|---------|
| **Cross-Origin Request** | `localhost:4200` → `localhost:8080` | Different ports = different origins |
| **SameSite=Lax** | Cookie set with `SameSite=Lax` | Lax doesn't guarantee XHR cookie sending cross-origin |
| **Cookie Domain** | Empty (defaults to `localhost:8080`) | Cookie scoped to backend origin, not frontend |
| **Timing** | 300ms delay added | Insufficient; browser cookie jar not synced |

### 3.3 Evidence from Logs

```
✅ Token refreshed, waiting 300ms for cookie sync...
🔄 Retrying /api/bookings/60 after cookie sync delay
❌ GET /api/bookings/60 401 (Unauthorized)
❌ Retry still got 401 after refresh - cookie issue
```

The refresh **succeeds** (200 OK), but the retry **fails** (401) because the browser doesn't send the new cookie.

---

## 4. Frontend Check-In Wizard Analysis

### 4.1 Navigation Logic (Check-In Exit)

```typescript
// check-in-wizard.component.ts:518-520
navigateBack(): void {
    this.router.navigate(['/bookings', this.bookingId()]);
}
```

**Finding:** ✅ No logout, no token clearing, no session destruction. Simple router navigation.

### 4.2 Component Destruction

```typescript
// check-in-wizard.component.ts:511-515
ngOnDestroy(): void {
    this.checkInService.stopPolling();
    this.geolocationService.stopWatching();
}
```

**Finding:** ✅ Only stops polling and GPS tracking. No auth-related cleanup.

---

## 5. Verification Steps

### 5.1 Prove Cookie Not Sent (Network Tab)

1. Open DevTools → Network tab
2. Trigger the 401 flow (click Back on check-in)
3. Find the **retry** request to `/api/bookings/60`
4. Check **Request Headers** → Look for `Cookie: access_token=...`

**Expected Result:** Cookie header is MISSING or contains OLD/EXPIRED token.

### 5.2 Prove with cURL (Same-Origin Simulation)

```bash
# 1. Login and capture cookies
curl -v -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"owner@test.com","password":"password"}' \
  -c cookies.txt

# 2. Wait for token to expire (or manually expire in DB)
# 3. Make request with expired token - should get 401
curl -v http://localhost:8080/api/bookings/60 -b cookies.txt

# 4. Refresh token
curl -v -X POST http://localhost:8080/api/auth/refresh \
  -b cookies.txt -c cookies.txt

# 5. Retry with new cookies - should get 200
curl -v http://localhost:8080/api/bookings/60 -b cookies.txt
```

**Expected Result:** cURL works because it's same-origin (direct to 8080). Browser fails because of cross-origin.

---

## 6. Solution Architecture

### Recommended Fix: Angular Proxy for Same-Origin Cookies

**Configuration Changes:**

1. **`proxy.conf.json`** - Add API proxy:
```json
{
  "/api": {
    "target": "http://localhost:8080",
    "secure": false,
    "changeOrigin": true,
    "logLevel": "debug"
  },
  "/uploads": {
    "target": "http://localhost:8080",
    "secure": false,
    "changeOrigin": true,
    "logLevel": "debug"
  }
}
```

2. **`environment.development.ts`** - Use relative URLs:
```typescript
baseApiUrl: '/api',  // Relative URL → goes through Angular proxy
baseUrl: 'http://localhost:8080',  // For static resources only
```

**Why This Works:**
- All API requests go to `localhost:4200/api/*`
- Angular dev server proxies to `localhost:8080/api/*`
- Cookies are set on `localhost:4200` (same origin as frontend)
- Browser sends cookies on all requests (no cross-origin issues)

---

## 7. Summary

| Category | Finding |
|----------|---------|
| **Root Cause** | Cross-origin cookie synchronization failure |
| **401 Reason** | Browser doesn't send new access_token cookie to different origin |
| **Security Logic** | ✅ Correct (no bugs in `@PreAuthorize` or `BookingSecurityService`) |
| **Frontend Logic** | ✅ Correct (no accidental logout or token clearing) |
| **Fix Required** | Configure Angular proxy for same-origin API requests |

---

## 8. Risk Assessment

| Without Fix | With Fix |
|-------------|----------|
| Token refresh works, retry fails | Token refresh works, retry works |
| User logged out on every token expiry | Seamless token rotation |
| Critical workflow broken | Workflow restored |

**Recommendation:** Implement proxy configuration as immediate fix. Consider `SameSite=None; Secure` for production HTTPS deployment.

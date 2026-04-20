# Diagnostic Report: Inconsistent Authentication Routing

**Date:** December 1, 2025
**Subject:** Root Cause Analysis of Owner Redirect Divergence (OAuth2 vs. Local Login)
**Status:** 🔴 Root Cause Identified

---

## 1. Executive Summary

The divergence in routing behavior is caused by a **data normalization failure** in the `AuthService.login()` method, combined with a **missing architectural guard** on the root route.

*   **Scenario A (OAuth2):** Works because `AuthCallbackComponent` uses `verifySession()`, which fetches and **normalizes** the user profile (ensuring `roles` array exists) before redirecting.
*   **Scenario B (Local Login):** Fails because `LoginComponent` receives the **raw, un-normalized** user object from the backend response. If the backend returns `role` (singular) instead of `roles` (plural), `RedirectService` sees `user.roles` as undefined and defaults to `/`.

---

## 2. Root Cause Analysis

### 2.1 The "Data Hydration" Gap

The core issue lies in `AuthService.ts`.

**The Bug:**
`AuthService.login()` normalizes the user data for the *internal* state (`currentUser$`) via `persistSession()`, but it returns the **raw** `response.user` to the subscriber (`LoginComponent`).

```typescript
// src/app/core/auth/auth.service.ts

login(payload: LoginRequest): Observable<UserProfile> {
  return this.http.post<AuthResponse>(...)
    .pipe(
      // 1. Normalizes data for internal state (CORRECT)
      tap((response) => this.persistSession(response)), 
      
      // 2. Returns RAW data to component (BUG)
      // If response.user has 'role' but not 'roles', this returns an object without 'roles'
      map((response) => response.user as UserProfile) 
    );
}
```

**The Consequence:**
In `LoginComponent.ts`:
```typescript
this.authService.login(payload).pipe(
  tap((user) => {
    // 'user' is the RAW object. If it lacks 'roles' array:
    this.redirectService.redirectAfterLogin(user); 
  })
)
```

In `RedirectService.ts`:
```typescript
getDefaultRoute(user: UserProfile | null): string {
  // user.roles is undefined -> condition fails -> returns '/'
  if (!user || !user.roles || user.roles.length === 0) {
    return '/';
  }
  // ...
}
```

### 2.2 The "Unprotected Root" Gap

Even if the redirect logic fails, the application should prevent an Owner from viewing the Renter Home page (`/`).

**Current State (`app.routes.ts`):**
```typescript
{
  path: '',
  loadComponent: () => import(...).then(m => m.HomeComponent),
  // ❌ MISSING: canActivate: [RoleRedirectGuard]
},
```

Because the root path has no guards, when `RedirectService` mistakenly sends the Owner to `/`, the router allows it, and the Owner sees the Renter view.

---

## 3. Comparison of Flows

| Feature | OAuth2 Flow (Scenario A) | Local Login Flow (Scenario B) |
|:---|:---|:---|
| **Method** | `verifySession()` | `login()` |
| **Data Source** | `/api/users/me` | `/api/auth/login` response |
| **Normalization** | Explicit via `mapBackendUserResponse` | **Skipped** in return value |
| **Roles Field** | Guaranteed `roles[]` | Likely `role` (singular) or missing |
| **Redirect Logic** | Local logic in `AuthCallback` | Delegates to `RedirectService` |
| **Outcome** | ✅ `/owner/dashboard` | ❌ `/` (Default fallback) |

---

## 4. Recommended Fixes

### Fix 1: Normalize Return Value in `AuthService` (Architectural Fix)

Modify `AuthService.login()` to return the normalized user object that `persistSession` creates, ensuring the API contract with components is consistent.

### Fix 2: Protect the Root Route (Defense in Depth)

Apply `RoleRedirectGuard` to the root path in `app.routes.ts`. This ensures that even if a user manually navigates to `/`, they are bounced to their dashboard if they are an Owner.

```typescript
// src/app/app.routes.ts
{
  path: '',
  canActivate: [RoleRedirectGuard], // Add this
  loadComponent: ...
}
```

---

## 5. Next Steps

System is ready for implementation. Please confirm if you want me to apply these fixes.

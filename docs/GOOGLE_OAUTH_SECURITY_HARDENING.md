# Google OAuth Security Hardening — Implementation Report

**Date:** 2026-02-13  
**Scope:** 7 security-critical auth fixes for Google OAuth sign-up flow  
**Status:** ✅ All changes implemented, compiled, 27/27 tests pass

---

## Summary of Changes

### 1. Lock Legacy PATCH /profile Endpoint
**File:** `apps/backend/src/main/java/org/example/rentoza/user/UserController.java`  
**Change:** Replaced body of `PATCH /api/users/profile` with HTTP 410 GONE response.  
**Why:** This endpoint allowed unauthenticated mutation of `firstName`, `lastName`, and `password` via the legacy `UpdateUserProfileRequest` DTO. The secure replacement is `PATCH /api/users/me` which uses `@AuthenticationPrincipal`.

### 2. Remove Client-Trusted Role from OAuth Callbacks
**Files:**
- `apps/backend/src/main/java/org/example/rentoza/auth/SupabaseAuthController.java`
- `apps/backend/src/main/java/org/example/rentoza/security/supabase/SupabaseAuthService.java`

**Change:** All 3 callback endpoints (GET, POST, token-callback) no longer accept or use a client-provided `role` parameter. The `handleGoogleCallback()` method now takes a single `String code` arg and hardcodes `Role.USER`. The `handleImplicitFlowTokens()` method no longer accepts a role. Owner upgrade is only possible through the validated profile completion flow.  
**Why:** Attacker could sign up as OWNER by sending `role=OWNER` in the callback parameters.

### 3. Harden Account Linking — Refuse LOCAL Auto-Link
**File:** `apps/backend/src/main/java/org/example/rentoza/security/supabase/SupabaseAuthService.java`  
**Change:** `syncGoogleUserToLocalDatabase()` now refuses to auto-link an email-matched user if their `authProvider == AuthProvider.LOCAL`. Only `AuthProvider.SUPABASE` users with `authUid == null` can be linked. LOCAL users get a 409 error instructing them to log in with password and link Google from profile settings.  
**Why:** Prevents account takeover where attacker creates a Google account matching a victim's email.

### 4. Enforce Provider Evidence & Email Verification
**File:** `apps/backend/src/main/java/org/example/rentoza/security/supabase/SupabaseAuthService.java`  
**Changes:**
- `syncGoogleUserToLocalDatabase()` now checks `supabaseUser.isEmailVerified()` — rejects unverified emails
- Added `extractGoogleSubjectId()` helper — iterates Supabase `identities` array for `provider=google` and extracts the `id` (Google's `sub` claim)
- Persists `googleId` on both new and existing users
- Added `identities` (List<Map<String,Object>>) and `appMetadata` (Map<String,Object>) fields to `SupabaseUser` inner class in `SupabaseAuthClient.java`
- Updated `parseUser()` method to parse `identities` and `app_metadata` from Supabase JSON responses

### 5. Stop Minting Legacy JWT on OAuth Profile Completion
**File:** `apps/backend/src/main/java/org/example/rentoza/auth/EnhancedAuthController.java`  
**Change:** `completeOAuth2Registration()` no longer calls `issueTokensAndRespond()` (which used `jwtUtil.generateToken()`). Instead it returns the user response DTO directly — the existing Supabase session cookies are preserved.  
**Why:** The user already has a valid Supabase session from Google OAuth; minting a legacy JWT creates a parallel auth session.

### 6. Remove Dead State Storage/Validation from Frontend
**Files:**
- `apps/frontend/src/app/core/auth/auth.service.ts`
- `apps/frontend/src/app/features/auth/pages/supabase-google-callback/supabase-google-callback.component.ts`

**Changes:**
- Removed `sessionStorage.setItem('supabase_google_oauth_state', response.state)` — backend never returned a custom state value
- Removed client-side state comparison logic (was `null === null` — always passed)
- Made `state` parameter optional in `handleSupabaseGoogleCallback(code, state?)`
- Relaxed `if (!state)` error gate in callback component — PKCE flow doesn't require custom state; CSRF protection is server-side via PKCE code verifier
- `processGoogleCallback(code, state?)` made state optional

### 7. Enforce Redirect URI Allowlist
**Files:**
- `apps/backend/src/main/java/org/example/rentoza/security/supabase/SupabaseAuthService.java`
- `apps/backend/src/main/resources/application.properties`

**Changes:**
- Added `app.oauth2-redirect-allowed-uris` configuration property
- `resolveRedirectUri()` now validates the effective redirect URI against the allowlist
- `isRedirectUriAllowed()` builds the allowlist from both `oauth2RedirectAllowedUrisRaw` (comma-separated) and the default `oauth2RedirectUri`
- Non-matching URIs throw `SupabaseAuthException("Invalid redirect URI")`
- Default allowlist: `https://rentoza.rs/...`, `https://www.rentoza.rs/...`, `http://localhost:4200/...`, `https://localhost:4200/...`

---

## Test Results

**27 tests executed, 27 passed, 0 failed**

Tests updated in:
- `SupabaseAuthControllerGoogleOAuthTest.java` — updated mock signatures (single-arg `handleGoogleCallback`), updated POST state test
- `SupabaseAuthServiceGoogleOAuthTest.java` — updated to single-arg callbacks, added tests for:
  - LOCAL account linking refusal (expects 409)
  - SUPABASE user linking with null authUid (allowed)
  - Email verification enforcement (unverified → exception)
  - google_id persistence from Supabase identities
  - Always-USER role assignment (OWNER via profile completion only)

---

## Rollback Procedure

If any of these changes need to be reverted:

### Per-file rollback via git:
```bash
cd /Users/kljaja01/Developer/Rentoza

# Revert ALL changes:
git checkout HEAD -- \
  apps/backend/src/main/java/org/example/rentoza/user/UserController.java \
  apps/backend/src/main/java/org/example/rentoza/auth/SupabaseAuthController.java \
  apps/backend/src/main/java/org/example/rentoza/security/supabase/SupabaseAuthService.java \
  apps/backend/src/main/java/org/example/rentoza/security/supabase/SupabaseAuthClient.java \
  apps/backend/src/main/java/org/example/rentoza/auth/EnhancedAuthController.java \
  apps/backend/src/main/resources/application.properties \
  apps/frontend/src/app/core/auth/auth.service.ts \
  apps/frontend/src/app/features/auth/pages/supabase-google-callback/supabase-google-callback.component.ts \
  apps/backend/src/test/java/org/example/rentoza/auth/SupabaseAuthControllerGoogleOAuthTest.java \
  apps/backend/src/test/java/org/example/rentoza/auth/SupabaseAuthServiceGoogleOAuthTest.java
```

### Selective rollback (by scope item):

| Scope | Files to revert |
|-------|----------------|
| 1. Lock PATCH /profile | `UserController.java` |
| 2. Remove client role | `SupabaseAuthController.java`, `SupabaseAuthService.java` (handleGoogleCallback, handleImplicitFlowTokens) |
| 3. Harden linking | `SupabaseAuthService.java` (syncGoogleUserToLocalDatabase) |
| 4. Email/provider checks | `SupabaseAuthService.java`, `SupabaseAuthClient.java` |
| 5. OAuth JWT fix | `EnhancedAuthController.java` |
| 6. Frontend state | `auth.service.ts`, `supabase-google-callback.component.ts` |
| 7. Redirect allowlist | `SupabaseAuthService.java`, `application.properties` |

### Risk notes for rollback:
- Reverting scope 2 (client role) without reverting scope 5 (JWT) will create an inconsistent state
- Reverting scope 4 (identities) requires also reverting scope 3 (linking hardening uses `extractGoogleSubjectId`)
- Frontend changes (scope 6) are safe to revert independently
- **Always revert tests alongside their corresponding scope changes**

---

## Files Modified

| File | Lines Changed | Scope |
|------|--------------|-------|
| `UserController.java` | ~10 | 1 |
| `SupabaseAuthController.java` | ~15 | 2 |
| `SupabaseAuthService.java` | ~120 | 2, 3, 4, 7 |
| `SupabaseAuthClient.java` | ~30 | 4 |
| `EnhancedAuthController.java` | ~8 | 5 |
| `auth.service.ts` | ~20 | 6 |
| `supabase-google-callback.component.ts` | ~10 | 6 |
| `application.properties` | ~2 | 7 |
| `SupabaseAuthControllerGoogleOAuthTest.java` | ~30 | tests |
| `SupabaseAuthServiceGoogleOAuthTest.java` | ~80 | tests |

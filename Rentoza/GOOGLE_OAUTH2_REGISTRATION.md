# Google OAuth2 Registration Extension

## Overview

This document describes the "Register with Google" extension to the existing Google OAuth2 integration in the Rentoza application. This feature allows users to explicitly register using Google as their identity provider, providing a first-class registration experience alongside manual email/password signup.

## Background

The initial Google OAuth2 integration (documented in [OAUTH2_INTEGRATION_GUIDE.md](OAUTH2_INTEGRATION_GUIDE.md)) supported login-only mode, where new users were automatically created when they signed in with Google. This extension adds **explicit registration mode**, giving users a clear registration option when creating new accounts.

## What Was Extended

### 1. OAuth2Mode Enum

**Location**: `org.example.rentoza.auth.oauth2.OAuth2Mode`

**Purpose**: Distinguishes between LOGIN and REGISTER flows

```java
public enum OAuth2Mode {
    LOGIN,    // Standard login flow - creates user if doesn't exist
    REGISTER  // Registration flow - throws error if user already exists
}
```

### 2. GoogleAuthController Extensions

**Location**: `org.example.rentoza.auth.oauth2.GoogleAuthController`

**New Endpoints**:

#### GET /api/auth/google/register-url
Returns the Google registration URL for frontend integration.

**Response**:
```json
{
  "url": "http://localhost:8080/api/auth/google/register",
  "provider": "google",
  "mode": "register"
}
```

#### GET /api/auth/google/register
Initiates the Google OAuth2 registration flow.

**Functionality**:
1. Creates a new HTTP session (or uses existing)
2. Stores `OAuth2Mode.REGISTER` in session attribute `oauth2_mode`
3. Redirects to standard OAuth2 authorization endpoint: `/oauth2/authorization/google`

**Session Attribute**:
- Key: `oauth2_mode` (defined in `GoogleAuthController.OAUTH2_MODE_SESSION_KEY`)
- Value: `OAuth2Mode.REGISTER`
- Lifetime: Removed after being read by `CustomOAuth2UserService`

### 3. CustomOAuth2UserService Extensions

**Location**: `org.example.rentoza.auth.oauth2.CustomOAuth2UserService`

**Updated Class Documentation**:
```
Supports both LOGIN and REGISTER modes:

LOGIN mode (default):
1. New user → create with GOOGLE provider
2. Existing GOOGLE user → update profile if changed
3. Existing LOCAL user → throw exception (email already registered with password)

REGISTER mode (explicit registration):
1. New user → create with GOOGLE provider
2. Existing GOOGLE user → throw exception (already registered)
3. Existing LOCAL user → throw exception (email already registered with password)
```

**New Method**: `getOAuth2Mode()`
- Retrieves `OAuth2Mode` from HTTP session
- Defaults to `LOGIN` if not set
- Clears the mode from session after reading (single-use)
- Handles `IllegalStateException` gracefully if session unavailable

**Updated Logic in `processOAuth2User()`**:
- Retrieves OAuth2 mode before processing user
- If mode is `REGISTER` and user already exists with GOOGLE provider:
  - Logs warning
  - Throws `OAuth2AuthenticationException` with error code `user_already_exists`
  - Error message: "An account with this email already exists. Please log in instead."
- All other scenarios remain unchanged

**Error Codes**:
- `user_already_exists` - User tried to register but account already exists (REGISTER mode only)
- `email_already_registered` - Email registered with LOCAL provider
- `missing_email` - Email not provided by Google
- `missing_user_id` - Google user ID not provided
- `unverified_email` - Email not verified by Google

### 4. Security Configuration

**Location**: `org.example.rentoza.security.SecurityConfig`

**Existing Permit Rule Covers New Endpoints**:
```java
.requestMatchers("/api/auth/google/**").permitAll()
```

This pattern already permits:
- `/api/auth/google/url` (login URL)
- `/api/auth/google/register-url` (registration URL)
- `/api/auth/google/register` (registration initiation)
- `/api/auth/google/me` (OAuth2 user info)
- `/api/auth/google/status` (OAuth2 status)

**No changes required to SecurityConfig**.

## OAuth2 Registration Flow

### Backend Flow

```
User clicks "Registruj se sa Google nalogom"
    ↓
Frontend → http://localhost:8080/api/auth/google/register
    ↓
GoogleAuthController:
  - Creates/gets HTTP session
  - Sets session.setAttribute("oauth2_mode", OAuth2Mode.REGISTER)
  - Redirects to: /oauth2/authorization/google
    ↓
Spring Security → Google authorization page
    ↓
User approves access in Google
    ↓
Google → http://localhost:8080/login/oauth2/code/google?code=...
    ↓
CustomOAuth2UserService.loadUser():
  - Retrieves mode from session (REGISTER)
  - Clears mode from session
  - Processes OAuth2 user in REGISTER mode
    ↓
If user exists (GOOGLE or LOCAL provider):
  → Throws OAuth2AuthenticationException("user_already_exists")
  → Backend redirects to: http://localhost:4200/auth/callback?error=user_already_exists
    ↓
If user doesn't exist:
  → Creates new user with GOOGLE provider
  → OAuth2AuthenticationSuccessHandler generates JWT + refresh token
  → Backend redirects to: http://localhost:4200/auth/callback?token=JWT_TOKEN
    ↓
Angular AuthCallbackComponent processes token
    ↓
User is registered and logged in
```

### Frontend Flow

**Registration Page** (`/auth/register`):
1. User sees "Registruj se sa Google nalogom" button
2. Click triggers `registerWithGoogle()` method
3. Method redirects to: `${baseApiUrl}/auth/google/register`

**OAuth2 Callback** (`/auth/callback`):
- Same `AuthCallbackComponent` handles both login and registration
- No distinction needed - token processing is identical
- Error handling covers registration-specific errors

## Frontend Integration

### Registration Component

**Location**: `rentoza-frontend/src/app/features/auth/pages/register/`

**Added Method**:
```typescript
registerWithGoogle(): void {
  const googleRegisterUrl = `${environment.baseApiUrl}/auth/google/register`;

  // Preserve role if registering as owner
  if (this.isOwnerRegistration) {
    sessionStorage.setItem('oauth2_register_role', 'OWNER');
  }

  window.location.href = googleRegisterUrl;
}
```

**HTML Template**:
- Added "OR" divider after manual registration form
- Added "Registruj se sa Google nalogom" button
- Styled consistently with login page Google button
- Disabled during form submission

**SCSS Styles**:
- `.auth-divider` - Horizontal divider with "ILI" text
- `.google-signin-btn` - Google Material Design styling
- Hover and active states for better UX

### AuthCallbackComponent

**No changes required** - existing component handles both login and registration callbacks identically.

## Testing

### Prerequisites

1. **Backend**: Running on `http://localhost:8080` with Google OAuth2 configured
2. **Frontend**: Running on `http://localhost:4200`
3. **Google Cloud Console**: OAuth 2.0 Client ID configured with redirect URI

### Test Scenario 1: New User Registration

1. Navigate to `http://localhost:4200/auth/register`
2. Click "Registruj se sa Google nalogom"
3. Redirected to: `http://localhost:8080/api/auth/google/register`
4. Session mode set to REGISTER, redirect to Google
5. Sign in with Google account (new to Rentoza)
6. Redirected to: `http://localhost:4200/auth/callback?token=...`
7. User registered and logged in
8. Verify in database:
   ```sql
   SELECT id, email, auth_provider, google_id, enabled
   FROM users
   WHERE email = 'newuser@gmail.com';
   ```
   - `auth_provider` = 'GOOGLE'
   - `enabled` = true
   - `google_id` populated

**Expected Result**: ✅ New user created, logged in, redirected to home/dashboard

### Test Scenario 2: Existing Google User Registration

1. User with `test@gmail.com` already registered via Google
2. Navigate to `http://localhost:4200/auth/register`
3. Click "Registruj se sa Google nalogom"
4. Sign in with Google using `test@gmail.com`
5. Backend detects user already exists in REGISTER mode
6. Redirected to: `http://localhost:4200/auth/callback?error=user_already_exists`
7. AuthCallbackComponent shows error
8. User redirected to `/auth/login` with error message

**Expected Result**: ✅ Error shown, user instructed to log in instead

### Test Scenario 3: Existing LOCAL User Registration

1. User with `local@example.com` registered via email/password
2. Navigate to `http://localhost:4200/auth/register`
3. Click "Registruj se sa Google nalogom"
4. Sign in with Google using `local@example.com`
5. Backend detects email registered with LOCAL provider
6. Redirected to: `http://localhost:4200/auth/callback?error=email_already_registered`
7. AuthCallbackComponent shows error
8. User redirected to `/auth/login` with error message

**Expected Result**: ✅ Error shown, user instructed to log in with password

### Test Scenario 4: Login After Registration

1. User registers via Google (Test Scenario 1)
2. Log out
3. Navigate to `http://localhost:4200/auth/login`
4. Click "Prijavi se sa Google nalogom" (LOGIN mode)
5. Sign in with same Google account
6. Backend processes in LOGIN mode (default)
7. User exists with GOOGLE provider → profile updated
8. Redirected with token, logged in successfully

**Expected Result**: ✅ User logs in successfully

### Test Scenario 5: Owner Registration

1. Navigate to `http://localhost:4200/auth/register?role=OWNER`
2. Click "Registruj se sa Google nalogom"
3. Complete Google registration
4. Backend creates user with `role=USER` (default)
5. Frontend would need to apply role upgrade logic

**Note**: Current implementation creates users with USER role by default. Owner role assignment would require additional backend logic to check session storage or query parameters.

## Backend API Reference

### New/Updated Endpoints

#### GET /api/auth/google/url
**Purpose**: Get Google OAuth2 login URL
**Response**:
```json
{
  "url": "http://localhost:8080/oauth2/authorization/google",
  "provider": "google",
  "mode": "login"
}
```
**Mode**: LOGIN

#### GET /api/auth/google/register-url
**Purpose**: Get Google OAuth2 registration URL
**Response**:
```json
{
  "url": "http://localhost:8080/api/auth/google/register",
  "provider": "google",
  "mode": "register"
}
```
**Mode**: REGISTER

#### GET /api/auth/google/register
**Purpose**: Initiate Google OAuth2 registration
**Side Effects**:
- Creates HTTP session
- Sets `session.oauth2_mode = REGISTER`
- Redirects to `/oauth2/authorization/google`
**Mode**: REGISTER

### OAuth2 Error Codes

| Error Code | Mode | Scenario | Frontend Redirect |
|------------|------|----------|-------------------|
| `user_already_exists` | REGISTER | User exists with GOOGLE provider | `/auth/callback?error=user_already_exists` |
| `email_already_registered` | BOTH | User exists with LOCAL provider | `/auth/callback?error=email_already_registered` |
| `missing_email` | BOTH | Email not provided by Google | `/auth/callback?error=oauth2_processing_error` |
| `missing_user_id` | BOTH | Google user ID not provided | `/auth/callback?error=oauth2_processing_error` |
| `unverified_email` | BOTH | Email not verified by Google | `/auth/callback?error=oauth2_processing_error` |
| `authentication_failed` | BOTH | General OAuth2 error | `/auth/callback?error=authentication_failed` |

## Security Considerations

### Session Management

1. **Session Attribute Lifetime**:
   - Created: When user hits `/api/auth/google/register`
   - Read: In `CustomOAuth2UserService.processOAuth2User()`
   - Cleared: Immediately after reading (single-use)

2. **Session Security**:
   - HttpOnly session cookie
   - Same-origin policy enforced
   - CSRF protection disabled (stateless JWT auth)

3. **Mode Isolation**:
   - LOGIN and REGISTER modes processed separately
   - No mode leakage between requests
   - Default to LOGIN if mode not set (fail-safe)

### Email Conflict Prevention

1. **LOCAL Provider Protection**:
   - Users cannot register with Google if email exists with LOCAL provider
   - Prevents account takeover via Google OAuth2
   - Same protection in both LOGIN and REGISTER modes

2. **GOOGLE Provider Protection**:
   - REGISTER mode prevents duplicate accounts
   - LOGIN mode allows returning users
   - Clear error messages guide users

### Token Security

- Same JWT and refresh token mechanism as login
- No distinction in token payload between registered and logged-in users
- Identical security headers and cookie settings

## Troubleshooting

### Issue: User Gets "Already Exists" Error After Clicking Register

**Cause**: User previously logged in via Google (automatic creation)

**Solution**:
- This is expected behavior in REGISTER mode
- User should use "Prijavi se sa Google nalogom" on login page instead
- Frontend should display clear error message directing to login

### Issue: Session Mode Not Detected

**Symptoms**:
- User clicks register button
- Behaves like login (doesn't reject existing users)

**Possible Causes**:
1. Session cookies blocked by browser
2. CORS issues preventing session persistence
3. Session cleared between registration initiation and callback

**Debug Steps**:
1. Check browser cookies - look for `JSESSIONID`
2. Check backend logs for session creation:
   ```
   DEBUG: Processing OAuth2 user in REGISTER mode
   ```
3. Verify CORS allows credentials:
   ```java
   c.setAllowCredentials(true)
   ```

**Solution**:
- Ensure cookies enabled in browser
- Verify CORS configuration
- Check session timeout settings

### Issue: Mode Not Cleared After Registration

**Symptoms**: Subsequent logins behave like registrations

**Cause**: Session mode not cleared after use

**Solution**: Verify `getOAuth2Mode()` removes attribute:
```java
session.removeAttribute(GoogleAuthController.OAUTH2_MODE_SESSION_KEY);
```

## Comparison: LOGIN vs REGISTER Mode

| Aspect | LOGIN Mode | REGISTER Mode |
|--------|------------|---------------|
| **User Initiation** | Click "Prijavi se sa Google nalogom" | Click "Registruj se sa Google nalogom" |
| **Frontend Redirect** | `/oauth2/authorization/google` | `/api/auth/google/register` → `/oauth2/authorization/google` |
| **Session Mode** | Not set (defaults to LOGIN) | Set to REGISTER |
| **New User** | ✅ Create user | ✅ Create user |
| **Existing GOOGLE User** | ✅ Update profile, allow login | ❌ Reject with error |
| **Existing LOCAL User** | ❌ Reject with error | ❌ Reject with error |
| **Error for Existing User** | N/A (allowed) | `user_already_exists` |
| **Use Case** | Returning users | First-time users |

## Production Deployment Notes

### Environment Variables

Same as login configuration (see [OAUTH2_INTEGRATION_GUIDE.md](OAUTH2_INTEGRATION_GUIDE.md)):

```bash
GOOGLE_CLIENT_ID=your-production-client-id
GOOGLE_CLIENT_SECRET=your-production-client-secret
OAUTH2_REDIRECT_URI=https://rentoza.rs/auth/callback
```

### Google Cloud Console

No additional configuration required - same OAuth 2.0 Client ID and redirect URIs.

### Session Configuration

**Development** (`application-dev.properties`):
```properties
server.servlet.session.cookie.secure=false
server.servlet.session.cookie.same-site=lax
server.servlet.session.timeout=30m
```

**Production** (`application-prod.properties`):
```properties
server.servlet.session.cookie.secure=true
server.servlet.session.cookie.same-site=strict
server.servlet.session.timeout=30m
```

## Files Modified Summary

### Backend

**Created**:
1. `OAuth2Mode.java` - Enum for LOGIN/REGISTER modes

**Modified**:
1. `GoogleAuthController.java` - Added registration endpoints
2. `CustomOAuth2UserService.java` - Added mode detection and handling
3. `GOOGLE_OAUTH2_REGISTRATION.md` - This documentation

**Unchanged**:
- `OAuth2AuthenticationSuccessHandler.java` - Same token issuance logic
- `OAuth2UserPrincipal.java` - Same principal wrapper
- `SecurityConfig.java` - Existing permit rules cover new endpoints

### Frontend

**Modified**:
1. `register.component.ts` - Added `registerWithGoogle()` method
2. `register.component.html` - Added Google registration button
3. `register.component.scss` - Added Google button and divider styles

**Unchanged**:
- `auth-callback.component.ts` - Same callback handling
- `auth.service.ts` - Same token management
- `app.routes.ts` - Same callback route

## Summary

This extension provides a first-class Google registration experience while maintaining all existing security features and token management. The implementation reuses the existing OAuth2 infrastructure with minimal changes, using session attributes to distinguish between login and registration intents.

**Key Benefits**:
- ✅ Clear UX distinction between login and registration
- ✅ Prevents duplicate account creation in registration flow
- ✅ Maintains all existing security features
- ✅ No breaking changes to existing login functionality
- ✅ Production-ready with comprehensive error handling
- ✅ Minimal code duplication

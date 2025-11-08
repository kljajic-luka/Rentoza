# Google OAuth2 Frontend Integration

## Overview

This document describes the Angular frontend integration for Google OAuth2 authentication in the Rentoza application. The implementation allows users to sign in with their Google accounts alongside the existing email/password authentication.

## What Was Implemented

### 1. AuthCallbackComponent

**Location**: `src/app/features/auth/pages/auth-callback/`

**Purpose**: Handles the OAuth2 callback from the backend after successful Google authentication.

**Key Features**:
- Extracts JWT token from query parameters (`?token=...`)
- Handles error scenarios with user-friendly messages
- Injects token into AuthService (same flow as local login)
- Fetches user profile using existing `/api/users/profile` endpoint
- Redirects to appropriate page based on user role
- Shows loading spinner during authentication processing

**Files Created**:
- `auth-callback.component.ts` - Component logic
- `auth-callback.component.html` - Loading and error states UI
- `auth-callback.component.scss` - Styling with gradient background

### 2. AuthService Updates

**Location**: `src/app/core/auth/auth.service.ts`

**New Method**:
```typescript
setAccessToken(token: string): void
```

**Purpose**: Allows OAuth2 callback to directly inject the JWT token received from the backend, enabling Google users to use the same authentication state management as local users.

### 3. Login Component Updates

**Location**: `src/app/features/auth/pages/login/`

**Changes Made**:

**HTML** (`login.component.html`):
- Added "OR" divider between email/password form and Google button
- Added Google Sign-In button with official Google branding (SVG logo)
- Button styled to match Google's brand guidelines

**TypeScript** (`login.component.ts`):
- Added `signInWithGoogle()` method
- Constructs backend OAuth2 authorization URL: `${baseApiUrl}/oauth2/authorization/google`
- Preserves `returnUrl` query parameter in session storage if present
- Redirects user to backend OAuth2 endpoint

**SCSS** (`login.component.scss`):
- Styled `.auth-divider` with horizontal lines and "ILI" text
- Styled `.google-signin-btn` following Google's Material Design guidelines
- Added hover and active states for better UX
- Made button responsive and accessible

### 4. Routing Updates

**Location**: `src/app/app.routes.ts`

**New Route**:
```typescript
{
  path: 'callback',
  loadComponent: () =>
    import('@features/auth/pages/auth-callback/auth-callback.component')
      .then((m) => m.AuthCallbackComponent)
}
```

**Full Path**: `/auth/callback`

**Purpose**: Public route (no auth guard) to receive OAuth2 callback from backend.

### 5. Bug Fixes (Pre-existing Issues)

Fixed two import errors in `notification.service.ts`:
- Changed `@env/environment` to `@environments/environment`
- Changed `environment.wsUrl` to `environment.chatWsUrl`

These were blocking Angular compilation.

## OAuth2 Flow Diagram

```
User clicks "Prijavi se sa Google nalogom" button
    ↓
Frontend redirects to: http://localhost:8080/oauth2/authorization/google
    ↓
Backend redirects to Google authorization page
    ↓
User approves access in Google
    ↓
Google redirects to: http://localhost:8080/login/oauth2/code/google?code=...
    ↓
Backend processes OAuth2 callback:
  - CustomOAuth2UserService validates user
  - OAuth2AuthenticationSuccessHandler generates JWT + refresh token
  - Sets refresh token cookie
    ↓
Backend redirects to: http://localhost:4200/auth/callback?token=JWT_TOKEN
    ↓
AuthCallbackComponent:
  - Extracts token from query params
  - Calls authService.setAccessToken(token)
  - Calls authService.refreshUserProfile()
  - Loads user data
    ↓
Angular app redirects to appropriate page based on user role
    ↓
User is now authenticated (same state as email/password login)
```

## Integration Points with Backend

### Backend Endpoints Used

1. **OAuth2 Authorization Endpoint**
   - URL: `/oauth2/authorization/google`
   - Method: GET (redirect)
   - Purpose: Initiates Google OAuth2 flow

2. **User Profile Endpoint**
   - URL: `/api/users/profile`
   - Method: GET
   - Headers: `Authorization: Bearer {token}`
   - Purpose: Fetch authenticated user details after OAuth2 callback

### Backend Configuration Requirements

From [OAUTH2_INTEGRATION_GUIDE.md](../../Rentoza/OAUTH2_INTEGRATION_GUIDE.md):

**Development**:
```properties
spring.security.oauth2.client.registration.google.client-id=${GOOGLE_CLIENT_ID}
spring.security.oauth2.client.registration.google.client-secret=${GOOGLE_CLIENT_SECRET}
oauth2.redirect-uri=http://localhost:4200/auth/callback
```

**Google Console Redirect URI**:
- Development: `http://localhost:8080/login/oauth2/code/google`
- Production: `https://your-domain.com/login/oauth2/code/google`

## Testing Instructions

### Prerequisites

1. **Backend Setup**:
   - Backend must be running on `http://localhost:8080`
   - Google OAuth2 credentials configured in environment variables:
     ```bash
     export GOOGLE_CLIENT_ID="your-client-id.apps.googleusercontent.com"
     export GOOGLE_CLIENT_SECRET="your-client-secret"
     ```
   - Database migration V4 applied (adds `auth_provider` and `google_id` columns)

2. **Google Cloud Console**:
   - OAuth 2.0 Client ID created
   - Authorized redirect URI: `http://localhost:8080/login/oauth2/code/google`
   - Scopes: openid, profile, email

3. **Frontend Setup**:
   - Angular dev server running: `npm start` (http://localhost:4200)
   - Environment configured with correct backend URL

### Test Scenarios

#### Scenario 1: New Google User (Happy Path)

1. Navigate to `http://localhost:4200/auth/login`
2. Click "Prijavi se sa Google nalogom" button
3. You should be redirected to Google's authorization page
4. Select a Google account and approve permissions
5. You should be redirected back to `http://localhost:4200/auth/callback?token=...`
6. AuthCallbackComponent should show loading spinner briefly
7. You should be redirected to the home page or dashboard (role-based)
8. Open browser DevTools → Network tab:
   - Check for `rentoza_refresh` cookie (HttpOnly)
   - Check localStorage for access token (if you store it)
9. Verify user profile is loaded in the app
10. Check backend database:
    ```sql
    SELECT id, email, auth_provider, google_id FROM users WHERE email = 'your-google-email@gmail.com';
    ```
    - `auth_provider` should be 'GOOGLE'
    - `google_id` should contain your Google sub ID

#### Scenario 2: Existing Google User (Return Login)

1. Log out from the application
2. Navigate to `http://localhost:4200/auth/login`
3. Click "Prijavi se sa Google nalogom" button
4. Sign in with the same Google account
5. You should be logged in immediately
6. User profile should be updated if name/email changed in Google

#### Scenario 3: Email Conflict (Error Handling)

1. Create a user with email `test@gmail.com` using local registration
2. Try to sign in with Google using the same email `test@gmail.com`
3. Backend should reject with error
4. AuthCallbackComponent should show error message
5. User should be redirected back to `/auth/login` with error query param
6. Toast notification should display: "Google prijavljivanje nije uspelo"

#### Scenario 4: OAuth2 Callback Error

1. Manually navigate to: `http://localhost:4200/auth/callback?error=access_denied`
2. AuthCallbackComponent should show error icon and message
3. User should be redirected to `/auth/login` after 2 seconds
4. Error toast should appear

#### Scenario 5: Missing Token

1. Manually navigate to: `http://localhost:4200/auth/callback` (no token param)
2. AuthCallbackComponent should show error: "Nije primljen token autentifikacije"
3. User should be redirected to `/auth/login` after 2 seconds

#### Scenario 6: Return URL Preservation

1. Navigate to a protected page: `http://localhost:4200/favorites`
2. You should be redirected to `/auth/login?returnUrl=/favorites`
3. Click "Prijavi se sa Google nalogom"
4. Complete Google authentication
5. You should be redirected to `/favorites` (the original protected page)

### Manual Testing Checklist

- [ ] Google Sign-In button appears on login page
- [ ] Google button has correct styling (Google logo + text)
- [ ] Google button is disabled while form is submitting
- [ ] OAuth2 redirect to Google works
- [ ] Google authorization page displays correctly
- [ ] Successful authentication redirects to `/auth/callback?token=...`
- [ ] Loading spinner shows during token processing
- [ ] User profile loads successfully
- [ ] Role-based redirect works (USER → cars, OWNER → dashboard)
- [ ] Refresh token cookie is set
- [ ] User can access protected routes after login
- [ ] User can logout and login again with Google
- [ ] Error scenarios show appropriate messages
- [ ] CORS is configured correctly (no CORS errors in console)
- [ ] No console errors during OAuth2 flow

### Browser DevTools Verification

**After Successful Google Login**:

1. **Network Tab**:
   - Look for `/oauth2/authorization/google` redirect (302)
   - Look for callback to `/auth/callback?token=...` (200)
   - Look for `/api/users/profile` call (200)
   - Check response headers for `Set-Cookie: rentoza_refresh`

2. **Application Tab → Cookies**:
   - `rentoza_refresh` cookie should be present
   - Domain: `localhost`
   - Path: `/api/auth/refresh`
   - HttpOnly: ✓
   - Secure: ✗ (in development)

3. **Console Tab**:
   - No CORS errors
   - No 401/403 errors
   - Look for successful auth logs

## Security Considerations

### Frontend Security

1. **Token Storage**:
   - Access token stored in AuthService BehaviorSubject (in-memory)
   - Refresh token stored in HttpOnly cookie (managed by backend)
   - No tokens in localStorage (XSS protection)

2. **CORS**:
   - Backend configured with allowed origins: `http://localhost:4200` (dev)
   - Credentials enabled: `withCredentials: true`
   - OAuth2 endpoints permitted in SecurityConfig

3. **Return URL Validation**:
   - Return URLs stored in session storage temporarily
   - Backend should validate redirect URIs

### Google OAuth2 Security

1. **Client Credentials**:
   - Client ID and Client Secret in environment variables (never in code)
   - Production secrets in secrets manager

2. **Redirect URI Whitelist**:
   - Only authorized redirect URIs work
   - Google Console configured with exact URIs

3. **Scope Limitation**:
   - Only request necessary scopes: openid, profile, email
   - No write access to Google account

## Troubleshooting

### Issue: "redirect_uri_mismatch" Error

**Cause**: Google Console redirect URI doesn't match backend redirect URI

**Solution**:
1. Go to Google Cloud Console → Credentials
2. Edit OAuth 2.0 Client ID
3. Add exact redirect URI: `http://localhost:8080/login/oauth2/code/google`
4. Save and wait a few minutes for changes to propagate

### Issue: CORS Errors in Browser Console

**Cause**: Backend CORS configuration missing or incorrect

**Solution**:
1. Check backend `application-dev.properties`:
   ```properties
   app.cors.allowed-origins=http://localhost:4200
   ```
2. Verify SecurityConfig permits OAuth2 endpoints
3. Restart backend

### Issue: "Email already registered" Error

**Cause**: User tried to sign in with Google using email already registered with password

**Solution**:
- This is expected behavior (security feature)
- User should log in with password
- Or implement account linking feature (future enhancement)

### Issue: Token Not Received in Callback

**Cause**: Backend OAuth2 flow failed or redirect URI incorrect

**Solution**:
1. Check backend logs for OAuth2 errors
2. Verify backend `oauth2.redirect-uri` property:
   ```properties
   oauth2.redirect-uri=http://localhost:4200/auth/callback
   ```
3. Ensure OAuth2AuthenticationSuccessHandler is working
4. Check if Google credentials are valid

### Issue: Profile Loading Failed After Token

**Cause**: JWT token invalid or `/api/users/profile` endpoint error

**Solution**:
1. Check browser DevTools → Network → `/api/users/profile` response
2. Verify JWT token format (should be Bearer token)
3. Check backend logs for authentication errors
4. Ensure JwtAuthFilter is working

## Production Deployment

### Frontend Changes

1. **Update environment.prod.ts**:
   ```typescript
   export const environment = {
     production: true,
     baseApiUrl: 'https://api.rentoza.rs/api',
     chatApiUrl: 'https://chat.rentoza.rs/api',
     chatWsUrl: 'https://chat.rentoza.rs/ws',
   };
   ```

2. **Build for production**:
   ```bash
   npm run build
   ```

### Backend Changes

1. **Update application-prod.properties**:
   ```properties
   oauth2.redirect-uri=https://rentoza.rs/auth/callback
   app.cors.allowed-origins=https://rentoza.rs
   app.cookie.secure=true
   app.cookie.domain=rentoza.rs
   app.cookie.same-site=Strict
   ```

2. **Google Console**:
   - Add production redirect URI: `https://api.rentoza.rs/login/oauth2/code/google`
   - Add authorized JavaScript origins: `https://rentoza.rs`

3. **SSL/TLS**:
   - HTTPS required for production
   - Valid SSL certificate

## Files Modified/Created Summary

### Created Files
1. `src/app/features/auth/pages/auth-callback/auth-callback.component.ts`
2. `src/app/features/auth/pages/auth-callback/auth-callback.component.html`
3. `src/app/features/auth/pages/auth-callback/auth-callback.component.scss`
4. `GOOGLE_OAUTH2_FRONTEND_INTEGRATION.md` (this file)

### Modified Files
1. `src/app/core/auth/auth.service.ts` - Added `setAccessToken()` method
2. `src/app/app.routes.ts` - Added `/auth/callback` route
3. `src/app/features/auth/pages/login/login.component.ts` - Added `signInWithGoogle()` method
4. `src/app/features/auth/pages/login/login.component.html` - Added Google button and divider
5. `src/app/features/auth/pages/login/login.component.scss` - Added Google button styles
6. `src/app/core/services/notification.service.ts` - Fixed import paths (bug fix)

## Verification Checklist

Before marking this integration as complete, verify:

- [x] All TypeScript files compile without errors
- [x] AuthCallbackComponent created with proper error handling
- [x] AuthService has `setAccessToken()` method
- [x] Login component has Google Sign-In button
- [x] Google button styled according to brand guidelines
- [x] Route `/auth/callback` added to routing
- [x] Environment configuration verified
- [x] Pre-existing bugs fixed (notification service imports)
- [ ] Backend running with Google OAuth2 configured
- [ ] Manual testing completed (all scenarios above)
- [ ] CORS working (no errors in console)
- [ ] Refresh token cookie set correctly
- [ ] User profile loads after Google login
- [ ] Role-based redirect working

## Next Steps

After successful manual testing:

1. **UI/UX Polish**:
   - Add loading indicators to Google button
   - Consistent toast messages
   - Better error messages (localized)

2. **Token Refresh Synchronization**:
   - Ensure Google users can auto-refresh tokens
   - Test refresh token rotation with Google auth

3. **Account Linking** (Optional Future Enhancement):
   - Allow users with LOCAL accounts to link Google account
   - Add "Link Google Account" option in user settings

4. **Analytics**:
   - Track Google sign-in usage
   - Monitor OAuth2 errors

5. **E2E Testing**:
   - Cypress or Playwright tests for OAuth2 flow
   - Mock Google OAuth2 responses

## Support

For issues or questions:
1. Check backend guide: `Rentoza/OAUTH2_INTEGRATION_GUIDE.md`
2. Review backend logs for OAuth2 errors
3. Check browser console for frontend errors
4. Verify Google Console configuration

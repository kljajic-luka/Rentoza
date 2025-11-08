# Google OAuth2 Integration Guide

## Overview

This guide documents the Google OAuth2 integration added to the Rentoza application. The implementation allows users to sign in with Google alongside the existing email/password authentication, maintaining backward compatibility with all existing features.

## What Was Implemented

### 1. Backend Changes

#### Dependencies Added
- `spring-boot-starter-oauth2-client` - Spring Security OAuth2 client support

#### New Domain Objects

**AuthProvider Enum** (`org.example.rentoza.user.AuthProvider`)
```java
public enum AuthProvider {
    LOCAL,   // Email/password authentication
    GOOGLE   // Google OAuth2 authentication
}
```

**User Entity Updates** (`org.example.rentoza.user.User`)
- Added `authProvider` field (defaults to LOCAL for existing users)
- Added `googleId` field (unique identifier from Google)
- Added index on `google_id` for performance

#### Database Migration

**File**: `V4__add_oauth2_authentication_support.sql`
- Adds `auth_provider` column (VARCHAR(20), defaults to 'LOCAL')
- Adds `google_id` column (VARCHAR(100), unique, nullable)
- Creates index on `google_id`
- **Backward compatible**: All existing users automatically get `auth_provider='LOCAL'`

#### OAuth2 Service Classes

**CustomOAuth2UserService** (`org.example.rentoza.auth.oauth2.CustomOAuth2UserService`)
- Processes Google user information after successful OAuth2 authentication
- Handles three scenarios:
  1. **New user** → Creates user with GOOGLE provider, placeholder password
  2. **Existing GOOGLE user** → Updates profile if changed
  3. **Existing LOCAL user** → Throws exception with clear message

**OAuth2UserPrincipal** (`org.example.rentoza.auth.oauth2.OAuth2UserPrincipal`)
- Wraps User entity to implement Spring Security's OAuth2User interface
- Provides seamless integration with Spring Security

**OAuth2AuthenticationSuccessHandler** (`org.example.rentoza.auth.oauth2.OAuth2AuthenticationSuccessHandler`)
- Handles successful OAuth2 authentication
- Generates JWT access token (same format as local login)
- Generates refresh token with IP/user-agent fingerprinting
- Sets refresh token as HttpOnly cookie
- Redirects to Angular app with access token in URL parameter

**GoogleAuthController** (`org.example.rentoza.auth.oauth2.GoogleAuthController`)
- Helper endpoints for frontend integration:
  - `GET /api/auth/google/url` - Returns Google login URL
  - `GET /api/auth/google/me` - Test OAuth2 authentication
  - `GET /api/auth/google/status` - OAuth2 configuration status

#### Security Configuration Updates

**SecurityConfig** (`org.example.rentoza.security.SecurityConfig`)
- Added OAuth2 login configuration
- Integrated CustomOAuth2UserService for user processing
- Integrated OAuth2AuthenticationSuccessHandler for success handling
- Permitted OAuth2 endpoints: `/oauth2/**`, `/login/oauth2/**`, `/api/auth/google/**`
- **No changes to existing authentication flow**

#### Configuration Properties

**application-dev.properties**
```properties
# Google OAuth2 Client Configuration
spring.security.oauth2.client.registration.google.client-id=${GOOGLE_CLIENT_ID:your-google-client-id}
spring.security.oauth2.client.registration.google.client-secret=${GOOGLE_CLIENT_SECRET:your-google-client-secret}
spring.security.oauth2.client.registration.google.scope=openid,profile,email
spring.security.oauth2.client.registration.google.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}

# Frontend redirect URI after successful OAuth2 authentication
oauth2.redirect-uri=http://localhost:4200/auth/callback
```

**application-prod.properties**
- Same structure with environment variable requirements
- Production redirect URI: `oauth2.redirect-uri=${OAUTH2_REDIRECT_URI:https://rentoza.rs/auth/callback}`

### 2. How It Works

#### OAuth2 Flow Diagram

```
User clicks "Sign in with Google"
    ↓
Frontend redirects to: /oauth2/authorization/google
    ↓
Spring Security redirects to Google's authorization page
    ↓
User approves access in Google
    ↓
Google redirects back to: /login/oauth2/code/google?code=...
    ↓
CustomOAuth2UserService processes user info:
  - Checks if email exists
  - If LOCAL user exists → Error (email already registered)
  - If GOOGLE user exists → Update profile
  - If new user → Create with GOOGLE provider
    ↓
OAuth2AuthenticationSuccessHandler:
  - Generates JWT access token
  - Generates refresh token with fingerprinting
  - Sets refresh token cookie
  - Redirects to: http://localhost:4200/auth/callback?token=JWT_TOKEN
    ↓
Angular app receives token and continues normal flow
```

#### Security Features

1. **Email Verification Required**: Only verified Google emails are accepted
2. **Email Conflict Prevention**: Users with existing LOCAL accounts cannot sign in with Google
3. **Same JWT Format**: Google users get the same JWT structure as local users
4. **Refresh Token Support**: Full refresh token rotation with fingerprinting
5. **CORS Compatible**: Works with Angular at localhost:4200

## Setup Instructions

### 1. Get Google OAuth2 Credentials

1. Go to [Google Cloud Console](https://console.cloud.google.com/apis/credentials)
2. Create a new project or select existing one
3. Navigate to "Credentials" → "Create Credentials" → "OAuth 2.0 Client ID"
4. Choose "Web application"
5. Add authorized redirect URI:
   - Development: `http://localhost:8080/login/oauth2/code/google`
   - Production: `https://your-domain.com/login/oauth2/code/google`
6. Copy the Client ID and Client Secret

### 2. Configure Environment Variables

**Development** (.env or shell):
```bash
export GOOGLE_CLIENT_ID="your-google-client-id.apps.googleusercontent.com"
export GOOGLE_CLIENT_SECRET="your-google-client-secret"
```

**Production** (environment variables or secrets manager):
```bash
GOOGLE_CLIENT_ID=your-production-client-id
GOOGLE_CLIENT_SECRET=your-production-client-secret
OAUTH2_REDIRECT_URI=https://rentoza.rs/auth/callback
```

### 3. Run Database Migration

The migration will run automatically on startup if using Flyway/Liquibase, or run manually:

```bash
mysql -u root -p rentoza < src/main/resources/db/migration/V4__add_oauth2_authentication_support.sql
```

### 4. Start the Backend

```bash
./mvnw spring-boot:run
```

Verify OAuth2 is working:
```bash
curl http://localhost:8080/api/auth/google/status
```

Expected response:
```json
{
  "oauth2Enabled": true,
  "provider": "google",
  "authorizationUrl": "/oauth2/authorization/google",
  "redirectUri": "/login/oauth2/code/google"
}
```

## Frontend Integration (Angular)

### 1. Add Google Sign-In Button to Login Component

**login.component.html**:
```html
<div class="login-form">
  <!-- Existing email/password form -->
  <form [formGroup]="loginForm" (ngSubmit)="onSubmit()">
    <!-- ... existing fields ... -->
  </form>

  <!-- OR divider -->
  <div class="divider">
    <span>OR</span>
  </div>

  <!-- Google Sign-In Button -->
  <button type="button" class="google-signin-btn" (click)="signInWithGoogle()">
    <img src="assets/google-icon.svg" alt="Google">
    Sign in with Google
  </button>
</div>
```

**login.component.ts**:
```typescript
import { Component } from '@angular/core';
import { HttpClient } from '@angular/common/http';

@Component({
  selector: 'app-login',
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.css']
})
export class LoginComponent {
  constructor(private http: HttpClient) {}

  // Existing onSubmit() method stays unchanged

  signInWithGoogle(): void {
    // Option 1: Direct redirect (recommended)
    window.location.href = 'http://localhost:8080/oauth2/authorization/google';

    // Option 2: Fetch URL from backend first
    // this.http.get<{url: string}>('http://localhost:8080/api/auth/google/url')
    //   .subscribe(response => {
    //     window.location.href = response.url;
    //   });
  }
}
```

### 2. Create OAuth2 Callback Route

**app-routing.module.ts**:
```typescript
import { AuthCallbackComponent } from './auth/auth-callback/auth-callback.component';

const routes: Routes = [
  // ... existing routes ...
  { path: 'auth/callback', component: AuthCallbackComponent },
];
```

**auth-callback.component.ts**:
```typescript
import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

@Component({
  selector: 'app-auth-callback',
  template: '<div>Processing authentication...</div>'
})
export class AuthCallbackComponent implements OnInit {
  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private authService: AuthService
  ) {}

  ngOnInit(): void {
    this.route.queryParams.subscribe(params => {
      const token = params['token'];
      const error = params['error'];

      if (error) {
        // Handle OAuth2 error
        console.error('OAuth2 authentication failed:', error);
        this.router.navigate(['/login'], {
          queryParams: { error: 'Google sign-in failed. Please try again.' }
        });
        return;
      }

      if (token) {
        // Store JWT token (same as local login)
        localStorage.setItem('accessToken', token);

        // Fetch user profile to populate auth state
        this.authService.getCurrentUser().subscribe({
          next: (user) => {
            // Navigate to home/dashboard
            this.router.navigate(['/home']);
          },
          error: (err) => {
            console.error('Failed to fetch user profile:', err);
            this.router.navigate(['/login'], {
              queryParams: { error: 'Authentication failed' }
            });
          }
        });
      } else {
        // No token provided
        this.router.navigate(['/login'], {
          queryParams: { error: 'No authentication token received' }
        });
      }
    });
  }
}
```

### 3. Update Auth Service (if needed)

The existing `AuthService` should work without changes, as Google users receive the same JWT format. However, you can add a method to check the auth provider:

**auth.service.ts**:
```typescript
getCurrentUser(): Observable<User> {
  return this.http.get<User>('/api/users/me');
}

// Optional: Check if user is OAuth2 authenticated
isOAuth2User(user: User): boolean {
  return user.authProvider === 'GOOGLE';
}
```

## Testing the Integration

### 1. Test Backend Endpoints

**Get Google Auth URL**:
```bash
curl http://localhost:8080/api/auth/google/url
```

**Check OAuth2 Status**:
```bash
curl http://localhost:8080/api/auth/google/status
```

### 2. Test Full OAuth2 Flow

1. Start backend: `./mvnw spring-boot:run`
2. Start Angular app: `npm start`
3. Navigate to login page
4. Click "Sign in with Google"
5. Approve access in Google popup
6. Verify redirect to Angular with token
7. Verify user is logged in
8. Check database for new user with `auth_provider='GOOGLE'`

### 3. Test Error Scenarios

**Email Already Registered with Password**:
1. Create user with email: `test@example.com` using local registration
2. Try to sign in with Google using same email
3. Should see error: "This email is already registered with password authentication"

**Unverified Google Email**:
- Google typically only returns verified emails, but if not verified, login will be blocked

## Security Considerations

### 1. Secrets Management

**Development**:
- Use `.env` file (gitignored)
- Or set environment variables in shell

**Production**:
- Use secrets manager (AWS Secrets Manager, Azure Key Vault, etc.)
- **NEVER** commit credentials to version control
- Rotate secrets regularly

### 2. HTTPS Requirements

- **Development**: HTTP is OK for localhost
- **Production**: MUST use HTTPS
  - Google requires HTTPS for OAuth2 redirect URIs
  - Cookies with `secure=true` only work over HTTPS

### 3. CORS Configuration

- Ensure production CORS origins include your actual domain
- Keep credentials enabled: `c.setAllowCredentials(true)`

### 4. Cookie Security

**Development** (`application-dev.properties`):
```properties
app.cookie.secure=false
app.cookie.domain=localhost
app.cookie.same-site=Lax
```

**Production** (`application-prod.properties`):
```properties
app.cookie.secure=true
app.cookie.domain=rentoza.rs
app.cookie.same-site=Strict
```

## Troubleshooting

### Issue: "redirect_uri_mismatch" Error

**Cause**: Redirect URI in Google Console doesn't match the one in your app

**Solution**:
1. Check Google Console authorized redirect URIs
2. Ensure it matches: `http://localhost:8080/login/oauth2/code/google` (dev)
3. For production: `https://yourdomain.com/login/oauth2/code/google`

### Issue: "Email already registered" Error

**Cause**: User tried to sign in with Google using email already registered with password

**Solution**:
- This is expected behavior for security
- User should log in with their password
- Or delete the LOCAL account first (if you implement account deletion)

### Issue: Refresh Token Not Working

**Cause**: Cookie domain mismatch or CORS issues

**Solution**:
1. Check cookie domain matches your app domain
2. Verify CORS credentials are enabled
3. Check browser developer tools for cookie being set

### Issue: CORS Errors

**Cause**: Frontend origin not in allowed origins list

**Solution**:
1. Check `app.cors.allowed-origins` in properties
2. Ensure Angular dev server origin (http://localhost:4200) is included
3. Verify CORS configuration includes OAuth2 paths

## Migration from Previous Failed Attempt

If you had a previous OAuth2 implementation that was rolled back:

1. **Clean up any orphaned database columns**: The migration handles this
2. **Remove any hardcoded credentials**: Use environment variables
3. **Verify no conflicting routes**: New routes are properly scoped under `/api/auth/google/`
4. **Test existing auth flow**: Ensure email/password login still works

## Production Deployment Checklist

- [ ] Google OAuth2 Client ID and Secret configured in secrets manager
- [ ] Production redirect URI added to Google Console
- [ ] `OAUTH2_REDIRECT_URI` environment variable set to production domain
- [ ] `CORS_ORIGINS` includes production frontend domain
- [ ] Database migration V4 applied successfully
- [ ] HTTPS enabled (SSL certificates configured)
- [ ] Cookie security settings updated (secure=true, SameSite=Strict)
- [ ] Test OAuth2 flow on production
- [ ] Test existing email/password flow still works
- [ ] Monitor logs for OAuth2 errors

## API Reference

### OAuth2 Helper Endpoints

**GET /api/auth/google/url**
- Returns: `{"url": "http://localhost:8080/oauth2/authorization/google", "provider": "google"}`
- Public endpoint

**GET /api/auth/google/me**
- Returns: OAuth2 user profile information
- Requires OAuth2 authentication

**GET /api/auth/google/status**
- Returns: OAuth2 configuration status
- Public endpoint

### OAuth2 Flow Endpoints (Spring Security)

**GET /oauth2/authorization/google**
- Initiates OAuth2 flow
- Redirects to Google authorization page
- Public endpoint

**GET /login/oauth2/code/google**
- OAuth2 callback endpoint
- Receives authorization code from Google
- Processes authentication
- Redirects to Angular app with token
- Public endpoint

## Support

For issues or questions:
1. Check this guide's troubleshooting section
2. Review backend logs for detailed error messages
3. Check browser console for frontend errors
4. Verify Google Console configuration

## Summary

This integration provides:
- ✅ Google OAuth2 authentication alongside existing local auth
- ✅ Same JWT and refresh token mechanism for both auth methods
- ✅ Backward compatibility with existing users
- ✅ Security best practices (email verification, conflict prevention)
- ✅ Production-ready configuration
- ✅ Comprehensive error handling
- ✅ Full CORS support
- ✅ No breaking changes to existing authentication flow

# Supabase Google OAuth Implementation Summary

**Document Version:** 1.0  
**Date:** January 2025  
**Status:** Phase 1 Complete - Ready for Testing

---

## Executive Summary

This document describes the implementation of enterprise-grade Google OAuth authentication using Supabase Auth for the Rentoza peer-to-peer car rental platform. The implementation replaces the legacy Spring Security OAuth2 configuration with Supabase-managed Google OAuth, providing improved security, simplified maintenance, and better integration with the existing Supabase ecosystem.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Implementation Components](#implementation-components)
3. [Authentication Flow](#authentication-flow)
4. [API Endpoints](#api-endpoints)
5. [Security Considerations](#security-considerations)
6. [Configuration Guide](#configuration-guide)
7. [Testing Guide](#testing-guide)
8. [Deployment Checklist](#deployment-checklist)
9. [Phase 2: Legacy Cleanup](#phase-2-legacy-cleanup)
10. [Troubleshooting](#troubleshooting)

---

## 1. Architecture Overview

### High-Level Architecture

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   Angular App   │────▶│  Spring Boot    │────▶│   Supabase      │
│    Frontend     │     │    Backend      │     │   Auth API      │
└─────────────────┘     └─────────────────┘     └─────────────────┘
         │                       │                       │
         │                       │                       │
         ▼                       ▼                       ▼
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  sessionStorage │     │  PostgreSQL     │     │   Google OAuth  │
│  (state token)  │     │  (users table)  │     │    Provider     │
└─────────────────┘     └─────────────────┘     └─────────────────┘
```

### Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Cookie-only authentication** | HttpOnly cookies prevent XSS attacks; tokens never exposed to JavaScript |
| **Server-side state management** | ConcurrentHashMap with SHA-256 hashing prevents CSRF attacks |
| **Idempotent user sync** | Handles repeat logins, account linking, and new user creation safely |
| **ES256 JWT validation** | Asymmetric keys via JWKS; no shared secrets between services |
| **INCOMPLETE registration status** | Allows OAuth users to complete profile before full access |

---

## 2. Implementation Components

### Backend Components

| File | Purpose | Lines Added |
|------|---------|-------------|
| `SupabaseAuthService.java` | Core OAuth business logic | ~300 lines |
| `SupabaseAuthController.java` | REST endpoints for OAuth | ~100 lines |
| `SupabaseAuthClient.java` | Token exchange methods | ~50 lines |
| `AuthProvider.java` | Added SUPABASE enum value | 1 line |
| `UserRepository.java` | Added findByAuthUid method | 2 lines |
| `SecurityConfig.java` | Permit OAuth endpoints | 5 lines |

### Frontend Components

| File | Purpose | Lines Added |
|------|---------|-------------|
| `auth.service.ts` | OAuth methods (initiate, callback) | ~60 lines |
| `auth.model.ts` | TypeScript interfaces | ~15 lines |
| `login.component.ts` | Updated Google sign-in button | ~5 lines |
| `supabase-google-callback.component.ts` | New callback handler | ~200 lines |
| `app.routes.ts` | New route for callback | ~10 lines |

### Test Components

| File | Purpose | Coverage |
|------|---------|----------|
| `SupabaseAuthServiceGoogleOAuthTest.java` | Service unit tests | >80% |
| `SupabaseAuthControllerGoogleOAuthTest.java` | Controller integration tests | >80% |

---

## 3. Authentication Flow

### Complete OAuth Flow Diagram

```
┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐
│  User    │     │ Angular  │     │  Spring  │     │ Supabase │     │  Google  │
│  Browser │     │ Frontend │     │  Backend │     │   Auth   │     │   OAuth  │
└────┬─────┘     └────┬─────┘     └────┬─────┘     └────┬─────┘     └────┬─────┘
     │                │                │                │                │
     │ 1. Click       │                │                │                │
     │ "Sign in       │                │                │                │
     │ with Google"   │                │                │                │
     ├───────────────▶│                │                │                │
     │                │ 2. GET         │                │                │
     │                │ /authorize     │                │                │
     │                │ ?role=USER     │                │                │
     │                ├───────────────▶│                │                │
     │                │                │                │                │
     │                │ 3. Generate    │                │                │
     │                │ state token    │                │                │
     │                │ (SHA-256)      │                │                │
     │                │◀───────────────┤                │                │
     │                │ authUrl,state  │                │                │
     │                │                │                │                │
     │                │ 4. Store state │                │                │
     │◀───────────────┤ sessionStorage │                │                │
     │ 5. Redirect    │                │                │                │
     │ to Supabase    │                │                │                │
     ├────────────────────────────────────────────────▶│                │
     │                │                │                │                │
     │                │                │                │ 6. Redirect   │
     │                │                │                │ to Google     │
     │◀────────────────────────────────────────────────┤───────────────▶│
     │                │                │                │                │
     │ 7. User        │                │                │                │
     │ authenticates  │                │                │                │
     │ with Google    │                │                │                │
     │────────────────────────────────────────────────────────────────▶│
     │                │                │                │                │
     │ 8. Google      │                │                │                │
     │ redirect to    │                │                │                │
     │ Supabase       │                │                │                │
     │◀───────────────────────────────────────────────────────────────│
     │                │                │                │                │
     │                │                │                │ 9. Supabase  │
     │                │                │                │ exchanges    │
     │                │                │                │ code         │
     │                │                │                │                │
     │ 10. Redirect   │                │                │                │
     │ to Angular     │                │                │                │
     │ callback       │                │                │                │
     │◀───────────────────────────────────────────────│                │
     │ ?code=xxx      │                │                │                │
     │ &state=yyy     │                │                │                │
     │                │                │                │                │
     │───────────────▶│ 11. Validate   │                │                │
     │                │ state          │                │                │
     │                │ (sessionStorage)│               │                │
     │                │                │                │                │
     │                │ 12. POST       │                │                │
     │                │ /callback      │                │                │
     │                │ {code, state}  │                │                │
     │                ├───────────────▶│                │                │
     │                │                │                │                │
     │                │                │ 13. Validate   │                │
     │                │                │ state (server) │                │
     │                │                │                │                │
     │                │                │ 14. Exchange   │                │
     │                │                │ code for token │                │
     │                │                ├───────────────▶│                │
     │                │                │                │                │
     │                │                │◀───────────────┤                │
     │                │                │ access_token   │                │
     │                │                │ refresh_token  │                │
     │                │                │ user_info      │                │
     │                │                │                │                │
     │                │                │ 15. Sync user  │                │
     │                │                │ to local DB    │                │
     │                │                │                │                │
     │                │◀───────────────┤ Set HttpOnly   │                │
     │                │                │ cookies        │                │
     │◀───────────────┤ user profile   │                │                │
     │                │                │                │                │
     │ 16. Redirect   │                │                │                │
     │ based on role  │                │                │                │
     │ or INCOMPLETE  │                │                │                │
     │ → complete     │                │                │                │
     │    profile     │                │                │                │
     │                │                │                │                │
     ▼                ▼                ▼                ▼                ▼
```

### State Token Flow

1. **Generation** (Backend): `SecureRandom` generates 32-byte token
2. **Storage** (Backend): SHA-256 hash stored in `ConcurrentHashMap` with role + expiry
3. **Return** (Frontend): Original token sent to frontend
4. **Store** (Frontend): Token stored in `sessionStorage` 
5. **Validate** (Frontend): Token compared on callback
6. **Verify** (Backend): Hash compared on callback
7. **Consume** (Backend): Token removed after use (one-time)
8. **Cleanup** (Backend): Expired tokens cleaned every 10 minutes

---

## 4. API Endpoints

### GET /api/auth/supabase/google/authorize

**Purpose:** Initiate Google OAuth flow

**Parameters:**
| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `role` | string | No | USER | Target role (USER or OWNER) |
| `redirectUri` | string | No | configured | Custom redirect URI |

**Response:**
```json
{
  "authorizationUrl": "https://xxx.supabase.co/auth/v1/authorize?provider=google&...",
  "state": "abc123xyz..."
}
```

**Error Responses:**
| Code | Condition |
|------|-----------|
| 400 | role=ADMIN (not allowed) |
| 500 | Supabase configuration error |

---

### GET /api/auth/supabase/google/callback

**Purpose:** Handle OAuth callback from Supabase redirect

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `code` | string | Yes | Authorization code from Supabase |
| `state` | string | Yes | State token for CSRF validation |
| `error` | string | No | OAuth error code |
| `error_description` | string | No | OAuth error description |

**Response (Success):**
```json
{
  "user": {
    "email": "user@gmail.com",
    "firstName": "John",
    "lastName": "Doe",
    "role": "USER",
    "registrationStatus": "INCOMPLETE"
  }
}
```

**Cookies Set:**
| Cookie | HttpOnly | Secure | SameSite | Max-Age |
|--------|----------|--------|----------|---------|
| `access_token` | ✅ | ✅ | Lax | 1 hour |
| `refresh_token` | ✅ | ✅ | Lax | 7 days |

**Error Responses:**
| Code | Condition |
|------|-----------|
| 400 | Missing code or state |
| 401 | Invalid/expired state (CSRF), OAuth error |
| 500 | Token exchange failed |

---

### POST /api/auth/supabase/google/callback

**Purpose:** Alternative callback for AJAX requests

**Request Body:**
```json
{
  "code": "authorization_code_here",
  "state": "state_token_here"
}
```

**Response:** Same as GET endpoint

---

## 5. Security Considerations

### CSRF Protection

- **State tokens:** 32-byte cryptographically secure random values
- **SHA-256 hashing:** State stored as hash, original sent to client
- **One-time use:** State consumed after verification
- **Expiration:** Tokens expire after 10 minutes
- **Cleanup:** Expired tokens purged periodically

### Token Security

- **HttpOnly cookies:** Tokens inaccessible to JavaScript
- **Secure flag:** Cookies only sent over HTTPS
- **SameSite=Lax:** Protection against cross-site requests
- **ES256 JWT:** Asymmetric signatures via JWKS

### User Synchronization

- **Idempotent operations:** Safe for repeat calls
- **auth_uid linking:** Connects Supabase identity to local user
- **Email-based fallback:** Links existing accounts by email
- **INCOMPLETE status:** Requires profile completion for new users

---

## 6. Configuration Guide

### Backend Configuration

Add to `application.properties`:

```properties
# Supabase configuration (existing)
supabase.url=${SUPABASE_URL}
supabase.anon-key=${SUPABASE_ANON_KEY}
supabase.service-role-key=${SUPABASE_SERVICE_ROLE_KEY}

# OAuth2 redirect URI (new)
app.oauth2-redirect-uri=${OAUTH2_REDIRECT_URI:https://localhost:4200/auth/supabase/google/callback}
```

### Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `SUPABASE_URL` | ✅ | Supabase project URL |
| `SUPABASE_ANON_KEY` | ✅ | Supabase anonymous key |
| `SUPABASE_SERVICE_ROLE_KEY` | ✅ | Supabase service role key |
| `OAUTH2_REDIRECT_URI` | No | OAuth callback URL (default: localhost) |

### Supabase Dashboard Configuration

1. Navigate to **Authentication** → **Providers** → **Google**
2. Enable Google provider
3. Configure Google OAuth credentials:
   - Client ID
   - Client Secret
4. Add redirect URLs:
   - Development: `https://localhost:4200/auth/supabase/google/callback`
   - Production: `https://yourdomain.com/auth/supabase/google/callback`

### Google Cloud Console Configuration

1. Go to [Google Cloud Console](https://console.cloud.google.com)
2. Select your project → **APIs & Services** → **Credentials**
3. Edit OAuth 2.0 Client ID
4. Add authorized redirect URIs:
   - `https://your-supabase-project.supabase.co/auth/v1/callback`

---

## 7. Testing Guide

### Running Unit Tests

```bash
# Run all Google OAuth tests
./mvnw test -Dtest="*GoogleOAuth*"

# Run specific test class
./mvnw test -Dtest="SupabaseAuthServiceGoogleOAuthTest"
./mvnw test -Dtest="SupabaseAuthControllerGoogleOAuthTest"
```

### Manual Testing Checklist

| # | Test Case | Expected Result |
|---|-----------|-----------------|
| 1 | Click "Sign in with Google" as USER | Redirect to Google, then back to app |
| 2 | Click "Sign in with Google" as OWNER | Redirect to Google, then back to app |
| 3 | Complete OAuth flow for new user | User created with INCOMPLETE status |
| 4 | Complete OAuth flow for existing email | Account linked (auth_uid set) |
| 5 | Repeat OAuth flow for same user | Login successful (no duplicate) |
| 6 | Cancel OAuth at Google | Error message shown |
| 7 | Tamper with state parameter | "Invalid state" error |
| 8 | Use expired state (>10 min) | "Expired state" error |
| 9 | Reuse state parameter | "Invalid state" error |
| 10 | Check cookie security | HttpOnly, Secure, SameSite=Lax |

### Frontend Testing

```bash
# Navigate to frontend directory
cd rentoza-frontend

# Run unit tests
npm run test

# Run e2e tests (if configured)
npm run e2e
```

---

## 8. Deployment Checklist

### Pre-Deployment

- [ ] All unit tests passing
- [ ] All integration tests passing
- [ ] Manual testing completed on staging
- [ ] Supabase Google provider configured
- [ ] Google Cloud Console redirect URIs configured
- [ ] Environment variables set in deployment environment
- [ ] HTTPS configured for all environments

### Deployment Steps

1. **Deploy Backend:**
   ```bash
   ./mvnw clean package -DskipTests
   # Deploy JAR to your hosting environment
   ```

2. **Deploy Frontend:**
   ```bash
   cd rentoza-frontend
   npm run build -- --configuration=production
   # Deploy dist/ to your hosting environment
   ```

3. **Verify Deployment:**
   - [ ] `/api/auth/supabase/google/authorize` returns 200
   - [ ] OAuth flow completes successfully
   - [ ] Cookies are set correctly
   - [ ] User sync creates/links users correctly

### Post-Deployment

- [ ] Monitor error logs for OAuth failures
- [ ] Verify user creation/linking in database
- [ ] Test complete OAuth flow in production
- [ ] Document any issues encountered

---

## 9. Phase 2: Legacy Cleanup

### Files to Delete (After Staging Verification)

```
src/main/java/org/example/rentoza/
├── deprecated/
│   ├── oauth/
│   │   ├── OAuth2AuthenticationSuccessHandler.java
│   │   ├── OAuth2UserInfoResponse.java
│   │   ├── OAuth2UserService.java
│   │   └── CustomOAuth2User.java
│   ├── auth/
│   │   ├── GoogleUserInfo.java
│   │   └── UserLoginInfo.java
│   └── jwt/
│       └── JwtUtil.java
└── security/
    └── JwtAuthFilter.java (legacy HS256 version)
```

### Configuration to Remove

```properties
# Remove from application.properties:
spring.security.oauth2.client.registration.google.client-id
spring.security.oauth2.client.registration.google.client-secret
spring.security.oauth2.client.registration.google.scope
spring.security.oauth2.client.registration.google.redirect-uri
spring.security.oauth2.client.provider.google.authorization-uri
spring.security.oauth2.client.provider.google.token-uri
spring.security.oauth2.client.provider.google.user-info-uri
```

### Dependencies to Remove (pom.xml)

```xml
<!-- Remove after Phase 2 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
```

---

## 10. Troubleshooting

### Common Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| "Invalid or expired OAuth state" | State token expired or tampered | Retry login from beginning |
| "Missing authorization code" | User cancelled at Google | User should complete OAuth |
| Cookie not set | HTTPS not configured | Ensure Secure cookie works over HTTPS |
| User created multiple times | Race condition | auth_uid uniqueness constraint handles this |
| 401 on API calls after OAuth | Cookie not sent | Check SameSite and domain settings |

### Debug Logging

Enable detailed logging for troubleshooting:

```properties
logging.level.org.example.rentoza.auth.supabase=DEBUG
logging.level.org.springframework.security=DEBUG
```

### Support Contacts

- **Backend Issues:** Check [SupabaseAuthService.java](../Rentoza/src/main/java/org/example/rentoza/auth/supabase/SupabaseAuthService.java)
- **Frontend Issues:** Check [auth.service.ts](../rentoza-frontend/src/app/core/auth/auth.service.ts)
- **Supabase Dashboard:** [https://app.supabase.com](https://app.supabase.com)

---

## Appendix: Files Modified

### Backend Files

| File | Changes |
|------|---------|
| `SupabaseAuthService.java` | Added `initiateGoogleAuth()`, `handleGoogleCallback()`, `syncGoogleUserToLocalDatabase()`, helper methods |
| `SupabaseAuthController.java` | Added 3 endpoints for Google OAuth |
| `SupabaseAuthClient.java` | Added `exchangeCodeForToken()`, `exchangeCodeForTokenWithRedirect()` |
| `AuthProvider.java` | Added `SUPABASE` enum value |
| `UserRepository.java` | Added `findByAuthUid(UUID)` method |
| `SecurityConfig.java` | Updated permit list and CSRF exemptions |
| `application.properties` | Added `app.oauth2-redirect-uri` |

### Frontend Files

| File | Changes |
|------|---------|
| `auth.service.ts` | Added `initiateSupabaseGoogleAuth()`, `loginWithSupabaseGoogle()`, `handleSupabaseGoogleCallback()` |
| `auth.model.ts` | Added `GoogleAuthInitResponse`, `SupabaseGoogleCallbackResponse` interfaces |
| `login.component.ts` | Updated `signInWithGoogle()` to use new Supabase method |
| `supabase-google-callback.component.ts` | New component for handling OAuth callback |
| `app.routes.ts` | Added route for `/auth/supabase/google/callback` |

### Test Files

| File | Coverage |
|------|----------|
| `SupabaseAuthServiceGoogleOAuthTest.java` | Service unit tests |
| `SupabaseAuthControllerGoogleOAuthTest.java` | Controller integration tests |

---

**Document maintained by:** Development Team  
**Last updated:** January 2025

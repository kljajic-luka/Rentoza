# JWT & Refresh Token Security Testing Guide

This guide provides step-by-step instructions for testing the enhanced JWT and refresh token security implementation.

## Prerequisites

- Backend running: `cd Rentoza && ./mvnw spring-boot:run`
- Frontend running: `cd rentoza-frontend && npm start`
- Test user account (or register a new one)
- Browser DevTools open (Network tab + Application/Storage tab)
- Postman or curl for API testing (optional)

---

## Test Scenarios

### ✅ Scenario 1: Normal Token Rotation

**Purpose**: Verify that tokens rotate correctly and old tokens are deleted

**Steps**:
1. Login via frontend (`http://localhost:4200/auth/login`)
2. Open DevTools → Application → Cookies
3. Verify `rentoza_refresh` cookie exists (HttpOnly, SameSite=Lax, Secure=false)
4. Copy the access token from Network tab (check login response)
5. Wait 15+ minutes (or manually trigger refresh)
6. Make an API call that requires authentication
7. Check Network tab - should see `/api/auth/refresh` call with 200 response
8. Verify NEW access token returned
9. Check backend logs for: "Token rotated successfully"
10. Database check: Old token should be DELETED

**Expected Results**:
- ✅ Refresh returns 200 with new access token
- ✅ Old refresh token deleted from database
- ✅ New refresh token stored with new hash
- ✅ User remains authenticated without interruption

**Backend Logs to Check**:
```
Token rotated successfully: user=<email>
Issued new refresh token for user: <email>
```

---

### ✅ Scenario 2: Token Reuse Detection (Security Alert)

**Purpose**: Verify that reused tokens trigger security measures

**Steps**:
1. Login and obtain refresh token cookie
2. Using browser DevTools or Postman, manually copy the `rentoza_refresh` cookie value
3. Trigger a normal refresh (wait 15 min or force expired token)
4. **CRITICAL**: After successful refresh, try to use the OLD refresh token again
5. Make a POST request to `/api/auth/refresh` with the old cookie value

**Expected Results**:
- ✅ Second refresh attempt returns 401 Unauthorized
- ✅ Error message: "Token reuse detected - all sessions invalidated for security"
- ✅ ALL refresh tokens for that user are revoked
- ✅ User is logged out on frontend
- ✅ SECURITY_AUDIT log shows: "SECURITY ALERT: Possible token theft detected"

**Backend Logs to Check**:
```
SECURITY ALERT: Token reuse detected for user: <email> from IP: <ip>
SECURITY ALERT: Possible token theft detected - user: <email>, IP: <ip>, previousUse: <timestamp>
Revoked all refresh tokens for user: <email> (count: X)
Token revocation: user=<email>, tokenCount=X
```

**Security Note**: This simulates a token theft scenario where an attacker tries to replay a stolen token.

---

### ✅ Scenario 3: Expired Token Handling

**Purpose**: Verify that expired refresh tokens are rejected

**Steps**:
1. Login and obtain refresh token
2. **Option A** (Recommended for testing):
   - Temporarily change `refresh-token.expiration-days=0` in `application-dev.properties`
   - Restart backend
   - Login again
   - Wait 1+ days
3. **Option B** (Manual DB manipulation):
   - Update `expires_at` in database to a past date
4. Try to refresh the access token

**Expected Results**:
- ✅ Refresh returns 401 Unauthorized
- ✅ Error message: "Refresh token expired"
- ✅ All tokens for user are revoked
- ✅ Cookie is cleared
- ✅ User redirected to login

**Backend Logs to Check**:
```
Token rotation failed: token expired for user <email>
Revoked all refresh tokens for user: <email>
```

---

### ✅ Scenario 4: Logout Flow

**Purpose**: Verify that logout properly revokes all tokens

**Steps**:
1. Login successfully
2. Verify refresh token cookie exists
3. Click "Logout" button in frontend
4. Check Network tab for `/api/auth/logout` request
5. Verify cookie is cleared
6. Try to use the old refresh token (should fail)
7. Check database - no active tokens for user

**Expected Results**:
- ✅ Logout returns 200 with `{"status": "logged_out"}`
- ✅ Cookie cleared (Set-Cookie header with maxAge=0)
- ✅ All refresh tokens deleted from database
- ✅ Subsequent refresh attempts return 401

**Backend Logs to Check**:
```
User logged out successfully: email=<email>
Revoked all refresh tokens for user: <email> (reason: USER_LOGOUT, count: 1)
Token revocation: user=<email>, reason=USER_LOGOUT, tokenCount=1
```

---

### ✅ Scenario 5: Password Change Invalidation

**Purpose**: Verify that password change revokes all sessions

**Steps**:
1. Login from 2 different browsers/devices (or use incognito mode)
2. Verify both have valid refresh tokens
3. Change password from one browser
4. Try to refresh token from the other browser
5. Verify both sessions are invalidated

**Expected Results**:
- ✅ All refresh tokens revoked
- ✅ Both sessions logged out
- ✅ User must re-login with new password

**Implementation Note**: Password change endpoint needs to call:
```java
refreshTokenService.revokeAll(user.getEmail(), "PASSWORD_CHANGE");
```

**Backend Logs to Check**:
```
Revoked all refresh tokens for user: <email> (reason: PASSWORD_CHANGE, count: 2)
```

---

### ✅ Scenario 6: IP Fingerprinting (Production Simulation)

**Purpose**: Test IP fingerprint validation in production mode

**Steps**:
1. Change `application-dev.properties`:
   ```properties
   refresh-token.fingerprint.enabled=true
   ```
2. Restart backend
3. Login from localhost
4. Token is fingerprinted with IP (likely 127.0.0.1 or ::1)
5. **Simulate IP change** (use proxy or VPN, or modify X-Forwarded-For header)
6. Try to refresh token

**Expected Results** (Lenient Mode - Current Implementation):
- ✅ Refresh succeeds (lenient mode for mobile users)
- ✅ Security warning logged: "SECURITY: IP mismatch during token rotation"
- ✅ SECURITY_AUDIT log shows possible session hijacking

**Expected Results** (Strict Mode - If Enabled):
- ❌ Refresh fails with 401
- ✅ Session invalidated
- ✅ User must re-login

**Backend Logs to Check**:
```
SECURITY: IP mismatch - user: <email>, expected: <ip1>, got: <ip2>
SECURITY: Possible session hijacking - IP changed for user: <email>
```

**Configuration for Strict Mode** (Future Enhancement):
Add this property and modify RefreshTokenServiceEnhanced.java line 206:
```properties
refresh-token.fingerprint.strict=true
```

---

### ✅ Scenario 7: Scheduled Cleanup Validation

**Purpose**: Verify that expired tokens are automatically cleaned up

**Steps**:
1. Create several expired tokens in database:
   ```sql
   UPDATE refresh_tokens
   SET expires_at = NOW() - INTERVAL '1 day'
   WHERE user_email = 'test@example.com';
   ```
2. Wait for scheduled cleanup (2 AM by default) OR manually trigger:
   - Set `refresh-token.cleanup.frequent.enabled=true`
   - Set `refresh-token.cleanup.frequent.cron=0 * * * * ?` (every minute)
   - Restart backend
   - Wait 1 minute
3. Check database - expired tokens should be deleted
4. Check backend logs for cleanup confirmation

**Expected Results**:
- ✅ Expired tokens deleted from database
- ✅ Active tokens remain untouched
- ✅ Logs show deletion count

**Backend Logs to Check**:
```
Starting scheduled cleanup of expired refresh tokens
Cleanup completed: X expired refresh tokens removed
```

**Manual Testing via H2 Console** (if using H2):
1. Open http://localhost:8080/h2-console
2. Execute:
   ```sql
   SELECT COUNT(*) FROM refresh_tokens WHERE expires_at < NOW();
   ```
3. Trigger cleanup
4. Verify count reduced to 0

---

## Database Verification Queries

### Check active tokens for a user
```sql
SELECT id, user_email, expires_at, created_at, used, ip_address
FROM refresh_tokens
WHERE user_email = 'test@example.com'
  AND expires_at > NOW()
ORDER BY created_at DESC;
```

### Check token reuse status
```sql
SELECT id, user_email, used, used_at, created_at
FROM refresh_tokens
WHERE user_email = 'test@example.com'
  AND used = TRUE;
```

### Check expired tokens
```sql
SELECT COUNT(*) as expired_count
FROM refresh_tokens
WHERE expires_at < NOW();
```

### Check IP fingerprints
```sql
SELECT user_email, ip_address, user_agent, created_at
FROM refresh_tokens
WHERE ip_address IS NOT NULL
ORDER BY created_at DESC
LIMIT 10;
```

---

## Security Audit Log Monitoring

The enhanced service uses a separate security audit logger. Check for these events:

### Log File Location (if configured)
`logs/security-audit.log` or check console output with prefix `SECURITY_AUDIT`

### Key Security Events:
1. **Token reuse detection**: `SECURITY ALERT: Possible token theft detected`
2. **IP mismatch**: `SECURITY: Possible session hijacking`
3. **Token revocation**: `Token revocation: user=<email>, reason=<reason>`
4. **Failed rotations**: `Token rotation attempt with non-existent token`

---

## Common Issues & Troubleshooting

### Issue 1: Tokens not rotating
**Symptom**: Same token used repeatedly
**Cause**: Old RefreshTokenService still injected
**Fix**: Verify AuthController uses `RefreshTokenServiceEnhanced`

### Issue 2: 401 on every request after login
**Symptom**: Immediate logout after successful login
**Cause**: Cookie domain/path mismatch
**Fix**: Check `application-dev.properties` cookie settings

### Issue 3: Reuse detection not working
**Symptom**: Old tokens work multiple times
**Cause**: Token not marked as `used` before deletion
**Fix**: Verify RefreshTokenServiceEnhanced line 212-213

### Issue 4: Cleanup not running
**Symptom**: Expired tokens remain in database
**Cause**: Scheduling not enabled or cron syntax wrong
**Fix**: Verify `@EnableScheduling` in RentozaApplication.java

---

## Production Deployment Checklist

Before deploying to production, verify:

- [ ] `refresh-token.fingerprint.enabled=true` in application-prod.properties
- [ ] `refresh-token.expiration-days=7` (or less) in production
- [ ] `app.cookie.secure=true` in production
- [ ] `app.cookie.sameSite=Strict` in production
- [ ] Database migration applied (V2__add_refresh_token_security_fields.sql)
- [ ] Security audit logging configured to separate file
- [ ] Monitoring/alerting set up for token reuse events
- [ ] All 7 test scenarios passed in staging environment
- [ ] Load testing with concurrent token refreshes completed

---

## Performance Considerations

### Expected Performance:
- Token rotation: < 50ms (SERIALIZABLE isolation overhead)
- Cleanup job: < 1s for 10k expired tokens (bulk DELETE)
- Reuse detection: < 10ms (indexed lookup on `token_hash`)

### Load Testing Recommendations:
1. Simulate 100+ concurrent refresh requests for same user
2. Verify only ONE succeeds (race condition test)
3. Monitor database lock contention
4. Check for deadlocks in transaction logs

---

## Security Best Practices

1. **Never log raw tokens** - Only log hashed tokens (first 10 chars)
2. **Monitor security audit logs** - Set up alerts for token reuse
3. **Regular token rotation** - Keep access tokens short-lived (15 min)
4. **Revoke on security events** - Password change, suspicious activity
5. **Enable fingerprinting in production** - Adds extra layer of security
6. **Test in staging first** - Verify all scenarios before production

---

## Contact & Support

For issues or questions regarding the security implementation:
- Check: `JWT_REFRESH_TOKEN_SECURITY_IMPLEMENTATION.md`
- Review: Backend logs in `logs/` directory
- Contact: Rentoza Security Team

---

**Last Updated**: 2025-11-04
**Version**: 2.0.0
**Author**: Rentoza Security Team

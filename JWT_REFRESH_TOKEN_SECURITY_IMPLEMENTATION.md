# JWT and Refresh Token Security - Production-Ready Implementation

## Overview

This document describes the enhanced JWT and refresh token system implemented for the Rentoza application. The system is now production-ready with comprehensive security features while maintaining compatibility with the development environment.

---

## 🔒 Security Enhancements Implemented

### 1. **Token Reuse Detection**
- **Problem**: Attackers could replay stolen refresh tokens
- **Solution**: Token marked as "used" after rotation; reuse triggers automatic revocation of ALL user tokens
- **Impact**: Prevents replay attacks and detects potential token theft

### 2. **Automatic Scheduled Cleanup**
- **Problem**: Expired tokens accumulate in database
- **Solution**: Scheduled job runs daily at 2 AM to purge expired tokens
- **Impact**: Reduces database bloat, improves query performance

### 3. **IP/UserAgent Fingerprinting** (Production Mode)
- **Problem**: Session hijacking attacks
- **Solution**: Optional binding of refresh tokens to IP address and User-Agent
- **Impact**: Detects and logs suspicious activity when IP changes
- **Configuration**: Disabled in dev (localhost issues), enabled in production

### 4. **Atomic Token Rotation**
- **Problem**: Race conditions during concurrent refresh requests
- **Solution**: `SERIALIZABLE` transaction isolation for rotation
- **Impact**: Prevents duplicate token generation, ensures consistency

### 5. **Comprehensive Security Logging**
- **Problem**: Difficult to audit security events
- **Solution**: Separate security audit log for token events
- **Impact**: Easier compliance, faster incident response

### 6. **Token Revocation on Security Events**
- **Problem**: Compromised accounts remain vulnerable
- **Solution**: Automatic revocation on password change, suspicious activity
- **Impact**: Limits damage from compromised credentials

---

## 📁 Files Modified/Created

### Backend Files Modified
1. **RefreshToken.java** - Enhanced entity with new fields
   - `createdAt` - Token creation timestamp
   - `used` - Flag for rotation tracking
   - `usedAt` - When token was used for rotation
   - `ipAddress` - Optional IP fingerprint
   - `userAgent` - Optional User-Agent fingerprint
   - Helper methods for expiration/reuse detection

2. **RefreshTokenRepository.java** - Added query methods
   - `findAllExpired()` - Find expired tokens
   - `deleteAllExpired()` - Bulk delete expired tokens
   - `countActiveTokensByUser()` - Count active tokens per user
   - `findAllByUserEmail()` - Get all tokens for debugging

3. **application-dev.properties** - Development configuration
   - `refresh-token.expiration-days=14`
   - `refresh-token.fingerprint.enabled=false` (disabled for localhost)
   - `refresh-token.cleanup.cron=0 0 2 * * ?`
   - `refresh-token.cleanup.frequent.enabled=false`

4. **application-prod.properties** - Production configuration
   - `refresh-token.expiration-days=7` (shorter for production)
   - `refresh-token.fingerprint.enabled=true` (RECOMMENDED)
   - Cleanup schedules configured

5. **RentozaApplication.java** - Enabled scheduling
   - Added `@EnableScheduling` annotation

### Backend Files Created
1. **TokenCleanupScheduler.java** (NEW)
   - Scheduled task for token cleanup
   - Runs daily at 2 AM
   - Optional frequent cleanup every 6 hours
   - Comprehensive logging

2. **RefreshTokenServiceEnhanced.java** (NEW)
   - Enhanced token service with all security features
   - Token reuse detection
   - IP/UserAgent fingerprinting
   - Atomic rotation with `SERIALIZABLE` isolation
   - Security audit logging
   - Helper methods for IP/UA extraction

3. **InvalidRefreshTokenException.java** (NEW)
   - Custom exception for token validation failures

---

## 🔧 Configuration Guide

### Development (localhost)

```properties
# JWT Settings
jwt.secret=<base64-encoded-secret>
jwt.expiration=900000  # 15 minutes

# Refresh Token Settings
refresh-token.expiration-days=14  # 2 weeks
refresh-token.fingerprint.enabled=false  # Disabled for localhost
refresh-token.cleanup.cron=0 0 2 * * ?  # Daily at 2 AM
refresh-token.cleanup.frequent.enabled=false  # Disabled

# Cookie Settings (HTTP for localhost)
app.cookie.secure=false
app.cookie.domain=localhost
app.cookie.same-site=Lax
```

### Production

```properties
# JWT Settings
jwt.secret=${JWT_SECRET}  # MUST be from env/secrets manager
jwt.expiration=900000  # 15 minutes

# Refresh Token Settings
refresh-token.expiration-days=7  # 1 week (recommended)
refresh-token.fingerprint.enabled=true  # ENABLED for security
refresh-token.cleanup.cron=0 0 2 * * ?
refresh-token.cleanup.frequent.enabled=true  # Every 6 hours

# Cookie Settings (HTTPS required)
app.cookie.secure=true
app.cookie.domain=${COOKIE_DOMAIN}  # e.g., rentoza.rs
app.cookie.same-site=Strict
```

---

## 🔄 Token Lifecycle

### 1. **Token Issuance** (Login/Register)

```
User logs in
  ├─> Generate 512-bit random token
  ├─> Hash with SHA-256
  ├─> Store hash in database
  │   ├─> userEmail
  │   ├─> tokenHash
  │   ├─> expiresAt (now + 14 days)
  │   ├─> createdAt (now)
  │   ├─> used = false
  │   ├─> ipAddress (if fingerprinting enabled)
  │   └─> userAgent (if fingerprinting enabled)
  ├─> Return raw token to client
  └─> Client receives HttpOnly cookie
```

### 2. **Token Rotation** (Refresh)

```
Client sends refresh cookie
  ├─> Extract and hash token
  ├─> Find in database
  ├─> Validate:
  │   ├─> Token exists?
  │   ├─> Not expired?
  │   ├─> Not already used? ⚠️ If used → REVOKE ALL TOKENS
  │   ├─> Not revoked?
  │   └─> IP matches? (if fingerprinting enabled)
  ├─> Mark old token as used
  ├─> Delete old token
  ├─> Issue new token
  └─> Return new access token + new refresh cookie
```

### 3. **Token Revocation** (Logout/Security Event)

```
Revocation triggered
  ├─> Find all tokens for user
  ├─> Delete all tokens
  ├─> Log revocation event
  └─> User must re-authenticate
```

### 4. **Scheduled Cleanup**

```
Daily at 2 AM (server time)
  ├─> Query expired tokens
  ├─> Bulk delete
  ├─> Log count deleted
  └─> Free database resources
```

---

## 🛡️ Security Scenarios

### Scenario 1: Token Theft & Reuse

**Attack**: Attacker steals refresh token and attempts to use it

```
1. User's token is stolen (network interception, malware, etc.)
2. Attacker sends refresh request with stolen token
3. System rotates token and gives attacker new tokens
4. Real user later tries to refresh with original token
5. System detects reuse (token marked as "used")
6. System immediately revokes ALL tokens for that user
7. Both attacker and real user logged out
8. Real user re-authenticates
9. Security team alerted via audit log
```

**Mitigation**: Automatic detection and revocation limits exposure window

### Scenario 2: Session Hijacking (IP Change)

**Attack**: Attacker uses stolen token from different IP

```
1. Token stolen and used from different IP
2. If fingerprinting enabled:
   ├─> System detects IP mismatch
   ├─> Logs security warning
   ├─> Can reject or allow based on strictness
   └─> Security team alerted
3. If repeated mismatches:
   └─> Consider revoking all tokens
```

**Mitigation**: IP fingerprinting detects suspicious activity

### Scenario 3: Password Change

**Attack**: Attacker has old refresh tokens after password change

```
1. User suspects compromise and changes password
2. Password change endpoint calls: refreshTokenService.revokeAll(email, "PASSWORD_CHANGE")
3. All refresh tokens invalidated
4. Attacker's stolen tokens now useless
5. User forced to re-authenticate
```

**Mitigation**: Automatic revocation on password change

### Scenario 4: Expired Tokens

**Attack**: Attacker tries to use old expired token

```
1. Attacker has token from 15+ days ago
2. Attempts to refresh
3. System checks expiration
4. Token expired
5. System auto-revokes all user tokens
6. Returns 401 Unauthorized
```

**Mitigation**: Short expiration + cleanup prevents long-term exposure

---

## 📊 Database Schema Changes

### Before (Old Schema)
```sql
CREATE TABLE refresh_tokens (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_email VARCHAR(255) NOT NULL,
  token_hash VARCHAR(255) NOT NULL UNIQUE,
  expires_at TIMESTAMP NOT NULL,
  revoked BOOLEAN NOT NULL DEFAULT FALSE,
  previous_token_hash VARCHAR(255),
  INDEX idx_rt_user (user_email),
  INDEX idx_rt_token (token_hash)
);
```

### After (New Schema)
```sql
CREATE TABLE refresh_tokens (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_email VARCHAR(255) NOT NULL,
  token_hash VARCHAR(255) NOT NULL UNIQUE,
  expires_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL,          -- NEW
  revoked BOOLEAN NOT NULL DEFAULT FALSE,
  used BOOLEAN NOT NULL DEFAULT FALSE,    -- NEW (reuse detection)
  used_at TIMESTAMP,                      -- NEW
  ip_address VARCHAR(45),                 -- NEW (fingerprinting)
  user_agent VARCHAR(500),                -- NEW (fingerprinting)
  previous_token_hash VARCHAR(255),
  INDEX idx_rt_user (user_email),
  INDEX idx_rt_token (token_hash)
);
```

**Migration Notes**:
- Spring Boot with `ddl-auto=update` will add new columns automatically in dev
- Production should use Flyway/Liquibase migration scripts
- Existing tokens remain valid (new columns nullable/default)

---

## 🧪 Testing Scenarios

### Test 1: Normal Token Rotation
```
1. User logs in
2. Access token expires after 15 minutes
3. Frontend auto-refreshes
4. New access token received
5. Old refresh token deleted
6. New refresh token issued
✅ Expected: Seamless rotation
```

### Test 2: Token Reuse Detection
```
1. User logs in
2. Capture refresh token
3. Use token once (get new token)
4. Try to reuse original token
✅ Expected: 401 error, all tokens revoked
```

### Test 3: Expired Token Handling
```
1. User logs in
2. Wait 14+ days (or modify expiration)
3. Try to refresh
✅ Expected: 401 error, must re-login
```

### Test 4: Logout
```
1. User logs in on multiple devices
2. User logs out on one device
3. Try to refresh on other devices
✅ Expected: All devices logged out
```

### Test 5: Password Change
```
1. User logged in on Device A
2. User changes password on Device B
3. Try to refresh on Device A
✅ Expected: 401 error, must re-login
```

### Test 6: IP Fingerprinting (Production Only)
```
1. User logs in from IP 1.2.3.4
2. Refresh token bound to 1.2.3.4
3. Attacker uses token from IP 5.6.7.8
✅ Expected: Security warning logged, possibly rejected
```

### Test 7: Scheduled Cleanup
```
1. Create tokens with past expiration dates
2. Wait for scheduled job (or trigger manually)
3. Check database
✅ Expected: Expired tokens deleted
```

---

## 🚀 Migration Steps

### From Old System to New System

#### Phase 1: Deploy Code (Zero Downtime)
```
1. Deploy new code with both old and new services
2. Keep using old RefreshTokenService (no breaking changes)
3. New columns added to database automatically
4. Test in production with monitoring
```

#### Phase 2: Enable New Features (Gradual Rollout)
```
1. Enable scheduled cleanup (low risk)
2. Monitor cleanup logs
3. Enable fingerprinting in production (medium risk)
4. Monitor for IP mismatch warnings
5. Adjust fingerprinting strictness based on logs
```

#### Phase 3: Full Migration (Replace Old Service)
```
1. Switch AuthController to use RefreshTokenServiceEnhanced
2. Monitor error rates
3. Check security audit logs
4. Verify token rotation working
5. Test logout and password change flows
```

---

## 📈 Monitoring & Alerts

### Key Metrics to Monitor

1. **Token Rotation Success Rate**
   - Metric: `refresh_token_rotation_success_count` / `refresh_token_rotation_total_count`
   - Alert: < 95% success rate

2. **Token Reuse Detection**
   - Metric: `token_reuse_detected_count`
   - Alert: > 0 (immediate investigation)

3. **IP Mismatch Warnings**
   - Metric: `ip_mismatch_warning_count`
   - Alert: > threshold (e.g., 100/hour)

4. **Cleanup Effectiveness**
   - Metric: `expired_tokens_deleted_per_run`
   - Alert: Unusual spike or 0 for too long

5. **Token Revocation Events**
   - Metric: `token_revocation_count` by reason
   - Alert: Unusual patterns

### Log Patterns to Watch

```log
# Normal Operation
INFO  - Token rotated successfully: user=user@example.com
INFO  - Cleanup completed: 45 expired refresh tokens removed

# Suspicious Activity
WARN  - SECURITY: IP mismatch during token rotation
ERROR - SECURITY ALERT: Token reuse detected for user: user@example.com

# Configuration Issues
ERROR - Token hashing failed
WARN  - Token rotation failed: token expired for user: user@example.com
```

---

## 🔗 Integration Points

### AuthController Changes (Future Work)

To use the new enhanced service:

```java
// Replace old service injection
private final RefreshTokenServiceEnhanced refreshTokenService;

// In refresh endpoint
@PostMapping("/refresh")
public ResponseEntity<?> refresh(
        @CookieValue(value = REFRESH_COOKIE, required = false) String refreshCookie,
        HttpServletRequest request,
        HttpServletResponse res) {

    if (refreshCookie == null || refreshCookie.isBlank()) {
        return ResponseEntity.status(401).body(Map.of("message", "No session"));
    }

    try {
        // Extract fingerprints if enabled
        String ip = RefreshTokenServiceEnhanced.extractIpAddress(request);
        String ua = RefreshTokenServiceEnhanced.extractUserAgent(request);

        // Rotate with fingerprint validation
        var result = refreshTokenService.rotate(refreshCookie, ip, ua);

        // ... rest of logic
    } catch (InvalidRefreshTokenException e) {
        // Standardized error handling
        return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
    }
}

// In login/register
String refreshToken = refreshTokenService.issue(
    user.getEmail(),
    RefreshTokenServiceEnhanced.extractIpAddress(request),
    RefreshTokenServiceEnhanced.extractUserAgent(request)
);
```

### User Password Change Integration

```java
@PostMapping("/change-password")
public ResponseEntity<?> changePassword(@RequestBody PasswordChangeRequest req) {
    // Validate old password
    // Update password
    // Revoke all refresh tokens
    refreshTokenService.revokeAll(user.getEmail(), "PASSWORD_CHANGE");
    // Return success
}
```

---

## 📋 Production Deployment Checklist

### Pre-Deployment
- [ ] JWT_SECRET stored in secrets manager (NOT in code)
- [ ] Database backup completed
- [ ] Rollback plan documented
- [ ] Monitoring dashboards configured
- [ ] Alert rules set up

### Deployment
- [ ] Deploy to staging first
- [ ] Run integration tests
- [ ] Check scheduled task execution
- [ ] Verify token rotation works
- [ ] Test logout and password change

### Post-Deployment
- [ ] Monitor error rates (< 1%)
- [ ] Check security audit logs
- [ ] Verify cleanup job runs
- [ ] Test from multiple devices
- [ ] Validate IP fingerprinting (if enabled)

### 24-Hour Validation
- [ ] No token reuse alerts
- [ ] Cleanup job ran successfully
- [ ] Token rotation success rate > 99%
- [ ] No database performance degradation
- [ ] User experience unaffected

---

## ⚠️ Known Limitations & Considerations

### 1. **IP Fingerprinting and Mobile Users**
- **Issue**: Mobile users frequently change IPs (cellular networks, WiFi switching)
- **Solution**: Currently logs warning but allows rotation
- **Future**: Implement "trust score" based on multiple factors

### 2. **Database Performance**
- **Issue**: Large number of tokens can slow queries
- **Solution**: Scheduled cleanup + proper indexing
- **Monitoring**: Track query performance, add indexes if needed

### 3. **Clock Skew**
- **Issue**: Server time differences can cause premature expiration
- **Solution**: Use NTP for time synchronization
- **Monitoring**: Alert on large time drifts

### 4. **Concurrent Rotation Requests**
- **Issue**: Multiple refresh requests at exact same time
- **Solution**: SERIALIZABLE isolation prevents race conditions
- **Trade-off**: Slightly higher latency on rotation

### 5. **Token Revocation Latency**
- **Issue**: Tokens already in use remain valid until expiration
- **Solution**: Short access token lifespan (15 minutes)
- **Improvement**: Consider Redis-based token blacklist for instant revocation

---

## 🎓 Best Practices

### For Developers

1. **Never Log Raw Tokens**
   - ✅ Log token hash prefix: `hash.substring(0, 10)`
   - ❌ Log full token: `SECURITY VIOLATION`

2. **Always Use Transactions**
   - Token operations should be atomic
   - Use `@Transactional` with appropriate isolation

3. **Handle Exceptions Gracefully**
   - Return 401 for invalid tokens
   - Never expose internal errors to client
   - Log security events separately

4. **Test Edge Cases**
   - Expired tokens
   - Reused tokens
   - Missing tokens
   - Corrupted tokens

### For Operations

1. **Monitor Security Logs Daily**
   - Check for token reuse alerts
   - Review IP mismatch warnings
   - Investigate unusual patterns

2. **Backup Before Changes**
   - Always backup before schema changes
   - Test rollback procedure

3. **Rotate Secrets Regularly**
   - Change JWT_SECRET every 90 days
   - Coordinate with token expiration

4. **Document Incidents**
   - Track security events
   - Update runbooks based on incidents

---

## 📚 References

- [RFC 6749 - OAuth 2.0](https://datatracker.ietf.org/doc/html/rfc6749)
- [RFC 6819 - OAuth 2.0 Threat Model](https://datatracker.ietf.org/doc/html/rfc6819)
- [OWASP Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)
- [OWASP Session Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)

---

## 📝 Changelog

### Version 2.0.0 (Current)
- ✅ Added token reuse detection
- ✅ Implemented scheduled cleanup
- ✅ Added IP/UserAgent fingerprinting
- ✅ Enhanced security logging
- ✅ Atomic rotation with SERIALIZABLE isolation
- ✅ Configurable token expiration
- ✅ Production-ready configuration

### Version 1.0.0 (Previous)
- Basic refresh token functionality
- SHA-256 hashing
- Simple rotation
- Manual cleanup required

---

## 🤝 Support

For questions or issues:
1. Check logs: `/var/log/rentoza/application.log`
2. Review security audit log entries
3. Consult this documentation
4. Contact development team

---

**Document Version**: 2.0.0
**Last Updated**: 2025-01-04
**Author**: Rentoza Development Team
**Status**: Production-Ready ✅

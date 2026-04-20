# Rentoza Platform - On-Call Runbook
# ===================================
# Version: 1.0.0
# Last Updated: Phase 5 - Reliability & Monitoring
# 
# This runbook provides step-by-step guidance for responding to
# production incidents on the Rentoza P2P car rental platform.

## Table of Contents

1. [Overview & Contact Info](#overview--contact-info)
2. [Incident Response Process](#incident-response-process)
3. [Common Alerts & Responses](#common-alerts--responses)
   - [High Error Rate](#high-error-rate)
   - [High Latency](#high-latency)
   - [Circuit Breaker Open](#circuit-breaker-open)
   - [High CPU Usage](#high-cpu-usage)
   - [Database Connection Pool](#database-connection-pool)
4. [Diagnostic Commands](#diagnostic-commands)
5. [Rollback Procedures](#rollback-procedures)
6. [Escalation Matrix](#escalation-matrix)

---

## Overview & Contact Info

### Platform Components

| Component | Technology | Hosting | Health Check |
|-----------|------------|---------|--------------|
| Backend API | Spring Boot 3.5 | Cloud Run | `/actuator/health` |
| Chat Service | Spring Boot 3.5 | Cloud Run | `/actuator/health` |
| Frontend | Angular 20 | Firebase Hosting | - |
| Database | PostgreSQL 15 | Supabase | Supabase Dashboard |
| Storage | Blob Storage | Supabase | Supabase Dashboard |

### Key URLs

```
Production API:     https://rentoza-backend-xxx.run.app
Chat Service:       https://chat-service-xxx.run.app
Frontend:           https://rentoza.web.app
GCP Console:        https://console.cloud.google.com/run?project=rentoza-prod
Supabase Dashboard: https://app.supabase.com/project/xxx
```

### On-Call Contacts

| Role | Name | Contact | Escalation Time |
|------|------|---------|-----------------|
| Primary On-Call | TBD | +381 xx xxx xxxx | Immediate |
| Secondary On-Call | TBD | +381 xx xxx xxxx | 15 minutes |
| Engineering Lead | TBD | - | 30 minutes |
| Platform Owner | TBD | - | 1 hour |

---

## Incident Response Process

### 1. Acknowledge (< 5 min)
- Acknowledge alert in PagerDuty/Slack
- Start incident timer
- Open incident channel (if SEV1/SEV2)

### 2. Assess (5-15 min)
- Determine scope and impact
- Assign severity level:
  - **SEV1**: Platform down, all users affected
  - **SEV2**: Major feature broken, many users affected
  - **SEV3**: Minor issue, few users affected
  - **SEV4**: Cosmetic/low impact

### 3. Mitigate (15-60 min)
- Apply immediate fix (rollback, scale, restart)
- Communicate status to stakeholders
- Document actions taken

### 4. Resolve
- Deploy permanent fix
- Verify fix in production
- Close incident

### 5. Post-Incident
- Schedule post-mortem (within 48h for SEV1/SEV2)
- Create follow-up tickets
- Update runbook if needed

---

## Common Alerts & Responses

### High Error Rate

**Alert:** `Rentoza - High Error Rate`  
**Threshold:** >5% 5xx errors for 5 minutes  
**Severity:** Critical

#### Symptoms
- Users seeing "Došlo je do greške" messages
- Failed API requests in browser console
- Error rate spike in dashboard

#### Diagnosis Steps

```bash
# 1. Check Cloud Run logs for errors
gcloud logging read "resource.type=cloud_run_revision \
  AND resource.labels.service_name=rentoza-backend \
  AND severity>=ERROR" \
  --limit=50 --format="table(timestamp, textPayload)"

# 2. Look for specific error patterns
gcloud logging read "resource.type=cloud_run_revision \
  AND jsonPayload.severity=ERROR" \
  --limit=20 --format=json | jq '.[] | {time: .timestamp, error: .jsonPayload.message, correlationId: .jsonPayload.requestId}'

# 3. Check if a specific endpoint is failing
gcloud logging read "resource.type=cloud_run_revision \
  AND httpRequest.status>=500" \
  --limit=20 --format="table(httpRequest.requestUrl, httpRequest.status)"
```

#### Common Causes & Fixes

| Cause | Indicators | Fix |
|-------|------------|-----|
| Database down | `DataAccessException` in logs | Check Supabase status, restart connection pool |
| Memory exhaustion | `OutOfMemoryError` | Increase memory, check for leaks |
| Bad deployment | Errors started after deploy | Rollback to previous revision |
| External API down | Circuit breaker open | Wait for recovery, enable fallback |
| Rate limiting | `429` errors, "rate limit" in logs | Verify limits, whitelist if needed |

#### Resolution Checklist
- [ ] Identify error source from correlation IDs
- [ ] Check recent deployments
- [ ] Verify database connectivity
- [ ] Check external service health
- [ ] Apply fix or rollback
- [ ] Verify error rate returns to normal

---

### High Latency

**Alert:** `Rentoza - High Latency`  
**Threshold:** P95 > 2 seconds for 5 minutes  
**Severity:** Warning

#### Symptoms
- Slow page loads
- Spinner showing for extended periods
- Timeouts in browser console

#### Diagnosis Steps

```bash
# 1. Check which endpoints are slow
gcloud logging read "resource.type=cloud_run_revision \
  AND httpRequest.latency>2s" \
  --limit=20 --format="table(httpRequest.requestUrl, httpRequest.latency)"

# 2. Check database query times (if Hibernate SQL logging enabled)
gcloud logging read "resource.type=cloud_run_revision \
  AND textPayload=~'Hibernate: select'" \
  --limit=20

# 3. Check Cloud Run instance count
gcloud run services describe rentoza-backend \
  --platform=managed --region=europe-west1 \
  --format="value(status.traffic[0].latestRevision, status.observedGeneration)"

# 4. Check for cold starts
gcloud logging read "resource.type=cloud_run_revision \
  AND textPayload=~'Started RentozaApplication'" \
  --limit=10 --format="table(timestamp)"
```

#### Common Causes & Fixes

| Cause | Indicators | Fix |
|-------|------------|-----|
| Cold starts | Latency spikes after idle | Set min-instances=1 |
| Slow DB queries | Long query times in logs | Add indexes, optimize queries |
| N+1 queries | Many small queries | Use JOIN FETCH, batch loading |
| External API slow | High latency to specific endpoints | Add timeouts, enable circuit breaker |
| Under-provisioned | All instances at max CPU | Increase max instances |

---

### Circuit Breaker Open

**Alert:** `Rentoza - Circuit Breaker Open`  
**Threshold:** Circuit breaker state = OPEN  
**Severity:** Warning

#### Circuit Breakers in System

| Name | Purpose | Failure Threshold | Wait Duration |
|------|---------|-------------------|---------------|
| `exifValidation` | Image metadata extraction | 70% failure rate | 20 seconds |
| `paymentGateway` | Payment processing | 30% failure rate | 60 seconds |
| `notificationService` | Push notifications | 80% failure rate | 15 seconds |

#### Diagnosis Steps

```bash
# 1. Check which circuit breaker opened
gcloud logging read "resource.type=cloud_run_revision \
  AND textPayload=~'CircuitBreaker'" \
  --limit=20 --format="table(timestamp, textPayload)"

# 2. Check upstream service health
# For payment gateway:
curl -s https://api.paymentprovider.com/health | jq .

# 3. Check failure rate before circuit opened
gcloud logging read "resource.type=cloud_run_revision \
  AND severity=ERROR \
  AND textPayload=~'paymentGateway|exifValidation|notification'" \
  --limit=50
```

#### Recovery Process

1. **Wait for half-open state** (automatic after wait duration)
2. **Check upstream service recovery**
3. **Monitor for successful calls** in half-open state
4. **Circuit will close automatically** after successful calls

#### Manual Reset (if needed)

The circuit breaker will automatically transition through states:
- CLOSED → OPEN (on failure threshold)
- OPEN → HALF_OPEN (after wait duration)
- HALF_OPEN → CLOSED (on successful calls)
- HALF_OPEN → OPEN (on failures)

**Note:** Manual reset is not available via API. Wait for automatic recovery.

---

### High CPU Usage

**Alert:** `Rentoza - High CPU Usage`  
**Threshold:** > 80% for 5 minutes  
**Severity:** Warning

#### Diagnosis Steps

```bash
# 1. Check if auto-scaling is working
gcloud run services describe rentoza-backend \
  --platform=managed --region=europe-west1 \
  --format="yaml(spec.template.spec.containerConcurrency, spec.template.metadata.annotations)"

# 2. Check current instance count
gcloud run revisions list --service=rentoza-backend \
  --platform=managed --region=europe-west1

# 3. Check for CPU-intensive operations
gcloud logging read "resource.type=cloud_run_revision \
  AND textPayload=~'imageProcessing|thumbnail|resize'" \
  --limit=20

# 4. Get thread dump (if debugging endpoint enabled)
curl -s https://rentoza-backend-xxx.run.app/actuator/threaddump | head -100
```

#### Common Causes & Fixes

| Cause | Indicators | Fix |
|-------|------------|-----|
| Image processing spike | Many upload requests | Queue processing, use Cloud Tasks |
| Infinite loop | Single request taking long | Check logs for stuck requests, restart |
| GC thrashing | High memory + CPU | Increase memory limit |
| Too few instances | All instances at 80%+ | Increase max-instances |

---

### Database Connection Pool

**Alert:** `Rentoza - DB Connection Pool Near Limit`  
**Threshold:** Active connections > 80% of pool  
**Severity:** Warning

#### Current Pool Configuration

```yaml
# HikariCP Settings (from application.yml)
maximumPoolSize: 10
minimumIdle: 2
connectionTimeout: 30000 # 30 seconds
idleTimeout: 600000 # 10 minutes
maxLifetime: 1800000 # 30 minutes
```

#### Diagnosis Steps

```bash
# 1. Check current connection count in Supabase
# Go to Supabase Dashboard > Database > Connection Pooling

# 2. Check for long-running transactions
gcloud logging read "resource.type=cloud_run_revision \
  AND textPayload=~'transaction|rollback|commit'" \
  --limit=50

# 3. Check for connection acquisition timeouts
gcloud logging read "resource.type=cloud_run_revision \
  AND textPayload=~'Connection is not available'" \
  --limit=20

# 4. Check HikariCP metrics
curl -s https://rentoza-backend-xxx.run.app/actuator/metrics/hikaricp.connections.active | jq .
```

#### Common Causes & Fixes

| Cause | Indicators | Fix |
|-------|------------|-----|
| Connection leak | Connections not returning | Find @Transactional methods without proper rollback |
| Long transactions | Transactions > 30s | Break up large operations |
| Too many instances | instances × poolSize > Supabase limit | Reduce pool size or use connection proxy |
| Slow queries | Queries blocking connections | Add indexes, optimize queries |

---

## Diagnostic Commands

### Cloud Run Quick Commands

```bash
# View recent logs
gcloud logging read "resource.type=cloud_run_revision \
  AND resource.labels.service_name=rentoza-backend" \
  --limit=50 --format="table(timestamp, severity, textPayload)"

# View error logs only
gcloud logging read "resource.type=cloud_run_revision \
  AND severity>=ERROR" \
  --limit=20

# Check service status
gcloud run services describe rentoza-backend \
  --platform=managed --region=europe-west1

# List recent revisions
gcloud run revisions list --service=rentoza-backend \
  --platform=managed --region=europe-west1 --limit=5

# View current traffic split
gcloud run services describe rentoza-backend \
  --platform=managed --region=europe-west1 \
  --format="yaml(status.traffic)"
```

### Actuator Endpoints

```bash
BASE_URL="https://rentoza-backend-xxx.run.app"

# Health check
curl -s $BASE_URL/actuator/health | jq .

# Detailed health (admin only)
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  $BASE_URL/actuator/health | jq .

# Check metrics
curl -s $BASE_URL/actuator/metrics | jq '.names[]' | grep -E 'http|hikari|jvm'

# Specific metric
curl -s $BASE_URL/actuator/metrics/http.server.requests | jq .

# Info
curl -s $BASE_URL/actuator/info | jq .
```

### Database Commands (Supabase)

```sql
-- Check active connections
SELECT count(*) FROM pg_stat_activity WHERE datname = 'postgres';

-- Find long-running queries
SELECT pid, now() - pg_stat_activity.query_start AS duration, query
FROM pg_stat_activity
WHERE state = 'active' AND now() - pg_stat_activity.query_start > interval '30 seconds';

-- Kill long-running query (use with caution)
SELECT pg_terminate_backend(pid);

-- Check table sizes
SELECT relname, pg_size_pretty(pg_total_relation_size(relid))
FROM pg_catalog.pg_statio_user_tables
ORDER BY pg_total_relation_size(relid) DESC
LIMIT 10;
```

---

## Rollback Procedures

### Cloud Run Rollback

```bash
# 1. List revisions to find previous stable version
gcloud run revisions list --service=rentoza-backend \
  --platform=managed --region=europe-west1 --limit=5

# 2. Route 100% traffic to previous revision
gcloud run services update-traffic rentoza-backend \
  --platform=managed --region=europe-west1 \
  --to-revisions=rentoza-backend-00042-abc=100

# 3. Verify rollback
gcloud run services describe rentoza-backend \
  --platform=managed --region=europe-west1 \
  --format="yaml(status.traffic)"
```

### Database Migration Rollback

**⚠️ CAUTION: Database rollbacks can cause data loss. Consult with team lead.**

```bash
# 1. Check current migration version
# (Run in Supabase SQL Editor)
SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;

# 2. Rollback is manual - apply reverse migration script
# Example: If V42__add_column.sql was the issue
# Apply: V42_rollback__remove_column.sql
```

---

## Database Backup & Restore Procedure (OPS-GAP-2)

### Recovery Objectives

| Metric | Target | Justification |
|--------|--------|---------------|
| **RPO** (Recovery Point Objective) | ≤ 24 hours | Supabase Pro daily automated backup |
| **RTO** (Recovery Time Objective) | ≤ 2 hours | Restore from backup + verify + warm caches |
| **PITR Window** | 7 days (if PITR enabled) | Supabase Pro PITR add-on |

### Supabase Automated Backups

Supabase Pro plan includes daily automated backups with 7-day retention.

**Verify backup status:**
1. Navigate to Supabase Dashboard → Project Settings → Database → Backups
2. Confirm the most recent backup timestamp is < 24 hours ago
3. Verify backup size is consistent with previous backups (sudden size drops indicate data loss)

**Monthly backup health check (add to ops calendar):**
- [ ] Verify backup list shows 7 consecutive daily entries
- [ ] Confirm backup sizes are within ±20% of each other
- [ ] Test restore to a scratch project (see Restore Procedure below)
- [ ] Document results in `#ops-log` channel

### Manual Backup (pg_dump)

Use this when you need an on-demand backup before risky operations
(schema migrations, bulk data changes, GDPR batch deletions).

```bash
# Prerequisites: psql client installed, Supabase connection string from
# GCP Secret Manager (secret: rentoza-db-url)

# 1. Retrieve connection string
DB_URL=$(gcloud secrets versions access latest \
  --secret="rentoza-db-url" --project=rentoza-prod)

# 2. Full database dump (custom format, compressed)
pg_dump "$DB_URL" \
  --format=custom \
  --no-owner \
  --no-privileges \
  --file="rentoza-backup-$(date +%Y%m%d-%H%M%S).dump"

# 3. Schema-only dump (lightweight, for migration rollback planning)
pg_dump "$DB_URL" \
  --schema-only \
  --file="rentoza-schema-$(date +%Y%m%d-%H%M%S).sql"

# 4. Verify dump integrity
pg_restore --list "rentoza-backup-$(date +%Y%m%d-*).dump" | head -20

# 5. Upload to GCS for retention (optional but recommended)
gsutil cp "rentoza-backup-$(date +%Y%m%d-*).dump" \
  gs://rentoza-backups/manual/
```

**Tables with PII (verify encryption at rest):**
- `users` — JMBG, PIB, bank account (AES-GCM encrypted via AttributeEncryptor)
- `data_access_log` — access audit trail
- `consent_records` — GDPR consent provenance

### Restore Procedure

**⚠️ CAUTION: Restoring a backup replaces current data. Coordinate with team.**

#### Option A: Restore from Supabase Dashboard Backup

1. Navigate to Supabase Dashboard → Project Settings → Database → Backups
2. Select the backup timestamp closest to (but before) the incident
3. Click "Restore" and confirm
4. Wait for restore to complete (monitor via Dashboard status)
5. Verify data integrity (see Verification Checklist below)
6. Restart Cloud Run services to reset connection pools:
   ```bash
   gcloud run services update rentoza-backend \
     --region=europe-west1 --platform=managed \
     --update-env-vars="RESTART_TRIGGER=$(date +%s)"
   ```

#### Option B: Restore from Manual pg_dump Backup

```bash
# 1. Retrieve connection string
DB_URL=$(gcloud secrets versions access latest \
  --secret="rentoza-db-url" --project=rentoza-prod)

# 2. Restore (clean + create mode — drops and recreates objects)
pg_restore "$DB_URL" \
  --clean --if-exists \
  --no-owner --no-privileges \
  --dbname="$DB_URL" \
  rentoza-backup-YYYYMMDD-HHMMSS.dump

# 3. Re-run Flyway to ensure migration state is consistent
# (The backend will do this automatically on next deployment)
```

#### Option C: Point-in-Time Recovery (PITR)

If PITR add-on is enabled on the Supabase Pro plan:

1. Navigate to Supabase Dashboard → Database → Point in Time Recovery
2. Select the exact timestamp to recover to (before the corruption event)
3. Initiate recovery and wait for completion
4. Verify data integrity

### Post-Restore Verification Checklist

Run these checks in Supabase SQL Editor after any restore:

```sql
-- 1. Verify row counts for critical tables match expected range
SELECT 'users' AS table_name, count(*) FROM users
UNION ALL
SELECT 'bookings', count(*) FROM bookings
UNION ALL
SELECT 'cars', count(*) FROM cars
UNION ALL
SELECT 'payments', count(*) FROM payments
UNION ALL
SELECT 'messages', count(*) FROM messages;

-- 2. Verify Flyway migration state is consistent
SELECT version, description, success
FROM flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 10;

-- 3. Verify no orphaned foreign keys
-- (Example: bookings referencing deleted users)
SELECT b.id, b.renter_id FROM bookings b
LEFT JOIN users u ON b.renter_id = u.id
WHERE u.id IS NULL LIMIT 5;

-- 4. Verify PII encryption integrity (spot check)
SELECT id, jmbg FROM users WHERE jmbg IS NOT NULL LIMIT 3;
-- All values should start with 'GCM$' (AES-GCM encrypted format)

-- 5. Verify GDPR deletion state is preserved
SELECT count(*) AS pending_deletions
FROM users
WHERE deletion_scheduled_at IS NOT NULL AND deleted = false;
```

### Incident-Specific Recovery Scenarios

| Scenario | Action | Estimated RTO |
|----------|--------|---------------|
| Accidental table DROP | Restore from Supabase backup or PITR | 1-2 hours |
| Corrupt migration (bad V_xx SQL) | Rollback migration + Cloud Run rollback | 30-60 min |
| GDPR deletion ran on wrong users | Restore from PITR to pre-deletion timestamp | 1-2 hours |
| Bulk update corrupted data | Restore single table from pg_dump | 30-60 min |
| Full database loss | Restore from Supabase automated backup | 2-4 hours |

---

## Escalation Matrix

| Severity | Initial Response | Escalation Time | Escalate To |
|----------|------------------|-----------------|-------------|
| SEV1 | Primary On-Call | Immediate | Secondary + Lead |
| SEV2 | Primary On-Call | 15 minutes | Secondary |
| SEV3 | Primary On-Call | 1 hour | - |
| SEV4 | Next business day | - | - |

### When to Escalate

- **SEV1**: Platform down, data loss risk, security breach
- **SEV2**: Major feature broken, cannot resolve in 30 min
- **SEV3**: Multiple users affected, workaround not obvious
- **SEV4**: Single user, cosmetic issue

---

## Post-Incident Template

```markdown
# Incident Post-Mortem: [Title]

**Date:** YYYY-MM-DD
**Duration:** HH:MM
**Severity:** SEVx
**Author:** [Name]

## Summary
Brief description of what happened.

## Impact
- Users affected: X
- Duration of impact: X minutes
- Revenue impact: €X (if applicable)

## Timeline
- HH:MM - Alert triggered
- HH:MM - Acknowledged by [Name]
- HH:MM - Root cause identified
- HH:MM - Fix deployed
- HH:MM - Incident resolved

## Root Cause
What actually caused the incident.

## Resolution
What was done to fix it.

## Action Items
- [ ] Preventive measure 1 (Owner: X, Due: Y)
- [ ] Preventive measure 2 (Owner: X, Due: Y)

## Lessons Learned
What we learned from this incident.
```

---

*Last updated: Phase 5 - Reliability & Monitoring*

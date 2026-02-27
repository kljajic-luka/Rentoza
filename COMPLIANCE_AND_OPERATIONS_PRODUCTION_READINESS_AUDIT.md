# Forensic Production-Readiness Audit: Compliance & Operations Quality

**Repo:** `/Users/kljaja01/Developer/Rentoza`
**Branch:** `main`
**Commit:** `945a5b477c35c308fca9b6243b3b9624ed172927`
**Date:** 2026-02-26
**Scope:** PII minimization, retention, deletion/anonymization, consent/legal artifacts, audit logs, traceability, incident triage readiness, production profile hardening, deploy safety, monitoring/alerting quality, rollback strategy.

---

## DELIVERABLE 1: DATA LIFECYCLE MAP BY DATA CLASS

### 1.1 Identity Documents (Driver License, Selfies, ID Docs)

| Stage | Mechanism | File:Line | Retention |
|-------|-----------|-----------|-----------|
| **Ingestion** | Upload via `RenterVerificationController` | Multi-part POST `/api/users/me/verification/documents` | Stored in Supabase Storage |
| **At-rest encryption** | Supabase-managed bucket | `supabase.storage.check-in-pii.bucket-name` in `application-prod.properties:194` | Encrypted at Supabase layer |
| **Processing** | OCR/biometric via MOCK or ONFIDO provider | `application-prod.properties:61` | Metadata in `renter_documents` table |
| **Selfie retention** | Automated cleanup scheduler | `RenterDocumentRetentionScheduler.java:100-152` | **90 days** then deleted |
| **Check-in selfie** | Per-booking retention | `RenterDocumentRetentionScheduler.java:67` | **7 days** |
| **Rejected docs** | Automated cleanup scheduler | `RenterDocumentRetentionScheduler.java:163-213` | **30 days** then deleted |
| **Annual anonymization** | Monthly scheduler | `RenterDocumentRetentionScheduler.java:223-272` | **1 year** then file deleted, metadata retained |
| **On user deletion** | `GdprService.permanentlyDeleteUser()` | `GdprService.java:238` | `setDriverLicenseNumber(null)` |

**Distributed locking:** All cleanup schedulers use `SchedulerIdempotencyService.tryAcquireLock()` with 23-hour lock duration to prevent duplicate execution across Cloud Run instances.

### 1.2 Financial Records (Payments, Deposits, Payouts)

| Stage | Mechanism | File:Line | Retention |
|-------|-----------|-----------|-----------|
| **Creation** | `PaymentTransaction` entity | `PaymentTransaction.java` — plain `Long userId`, NOT FK | Indefinite |
| **Cascade** | Cascades with Booking via `CascadeType.ALL` | `Booking.java:834` | Not cascade-deleted with User |
| **On user deletion** | User anonymized, but `PaymentTransaction.userId` becomes dangling reference | `GdprService.java:228-251` | **Not touched** — retained for 7-year financial compliance |
| **Payout records** | `PayoutLedgerRepository` | Separate ledger entity | Retained for financial audit |

### 1.3 User Profile PII (Name, Email, Phone, JMBG, PIB, Bank Account)

| Stage | Mechanism | File:Line | Retention |
|-------|-----------|-----------|-----------|
| **At-rest encryption** | `AttributeEncryptor` (AES-ECB-128) | `AttributeEncryptor.java:37` | JMBG, PIB, bank account, driver license number |
| **Hash for uniqueness** | SHA-256 hash columns | `User.java:257` (pibHash), `:274` (jmbgHash), `:336` (driverLicenseNumberHash) | Hashes NOT cleared on deletion |
| **Masking in logs** | `PiiMaskingConverter` | `PiiMaskingConverter.java:46-84` | Email, phone, CC, JWT tokens masked |
| **Export** | `GdprService.exportUserData()` | `GdprService.java:53-90` | On-demand, rate-limited 1/24h |
| **Anonymization** | `permanentlyDeleteUser()` | `GdprService.java:233-243` | Email→`deleted_X@anonymized.rentoza.rs`, name→"Deleted User", all PII→null |

### 1.4 Chat Messages

| Stage | Mechanism | File:Line | Retention |
|-------|-----------|-----------|-----------|
| **Storage** | `Message` entity in chat-service DB | `chat-service/model/Message.java:29-31` | `senderId` is plain Long, no FK to main backend |
| **Moderation** | `ContentModerationFilter` blocks PII in messages | `ContentModerationFilter.java` | Blocks phone/email/payment keywords |
| **On user deletion** | **NOTHING** — no cross-service event | N/A | Messages orphaned with dangling senderId |
| **GDPR export** | **NOT INCLUDED** in data export | `GdprService.java:53-90` | Chat data missing from export |

### 1.5 Booking & Review Data

| Stage | Mechanism | File:Line | Retention |
|-------|-----------|-----------|-----------|
| **Bookings** | No cascade delete with User | `User.java:441` — `@OneToMany(mappedBy = "renter")`, no cascade | Retained indefinitely for audit |
| **Reviews** | Reviewer nullable for GDPR | `Review.java:76-80` — `@ManyToOne` nullable | Retained with null reviewer after deletion |
| **Cars** | Deletion blocked if user owns cars | `User.java:432` — `CascadeType.PERSIST, MERGE` only | Must be transferred or delisted first |

---

## DELIVERABLE 2: COMPLIANCE GAP REPORT (PROVEN vs ASSUMED)

### GAP-1 [CRITICAL]: No Scheduled Executor for GDPR Article 17 Grace Period

**Status:** PROVEN GAP

- `GdprService.permanentlyDeleteUser()` at `GdprService.java:227-251` is fully implemented.
- `initiateAccountDeletion()` sets `deletionScheduledAt = now + 30 days` at `GdprService.java:192-193`.
- **Zero callers** of `permanentlyDeleteUser()` exist in the codebase — confirmed by exhaustive grep.
- The project's own documentation acknowledges this at `archive/audits/AFTER_DEPLOYMENT_MDS/PHASE7_DOCUMENTATION_COMPLIANCE_SUMMARY.md:286-289` where it is listed as a "Recommended Next Step."

**Impact:** Users who request deletion have data retained indefinitely. GDPR Article 17 requires erasure "without undue delay." A 30-day published grace period with no execution is a regulatory violation risk.

**Remediation:** Implement `@Scheduled(cron = "0 0 3 * * *")` job that queries `findByDeletionScheduledAtBeforeAndDeletedFalse(LocalDateTime.now())` and calls `permanentlyDeleteUser()` for each result. Add distributed locking via `SchedulerIdempotencyService`.

### GAP-2 [CRITICAL]: AES-ECB Encryption Mode for PII

**Status:** PROVEN VULNERABILITY

- `AttributeEncryptor.java:37`: `Cipher.getInstance("AES")` — when no mode/padding is specified, Java defaults to `AES/ECB/PKCS5Padding`.
- ECB mode is deterministic: identical plaintext always produces identical ciphertext.
- Enables frequency analysis attacks on structured PII (e.g., JMBG numbers with common prefixes).
- Key is AES-128 (first 16 bytes of env var) at `AttributeEncryptor.java:30`.
- No IV, no GCM, no authenticated encryption.

**Impact:** An attacker with read access to the database can correlate encrypted values and potentially reverse common PII through frequency analysis. ECB is considered broken for structured data by NIST and OWASP.

**Remediation:** Migrate to `AES/GCM/NoPadding` with random 12-byte IV prepended to ciphertext. Implement a data migration job that re-encrypts all existing PII fields. This requires addressing the key rotation mechanism documented in `SECRETS_SETUP.md:62` where PII key is marked as "NEVER rotate."

### GAP-3 [HIGH]: Chat Service Has Zero GDPR Compliance

**Status:** PROVEN GAP

- No deletion propagation from backend to chat-service when user is anonymized.
- No message queue, event bus, or webhook between services for user lifecycle events.
- `Message.senderId` is a plain `Long` column (`chat-service/model/Message.java:29-31`), not a FK — orphaned on user deletion.
- Chat messages are **not included** in the GDPR data export at `GdprService.java:53-90`.
- No content retention policy for chat messages.

**Impact:** GDPR Article 15 export is incomplete (missing chat data). Article 17 erasure does not propagate to chat service. Chat history can contain PII that survives user deletion indefinitely.

### GAP-4 [HIGH]: Consent IP Address Hardcoded as Stub

**Status:** PROVEN STUB

- `GdprService.java:273`: `String ipAddress = "0.0.0.0"; // Would come from request in real implementation`
- `GdprController.java:226-236`: Controller method does not inject `HttpServletRequest` — no way to pass real IP.
- `UserConsent.userAgent` field exists (`UserConsent.java:34-35`) but is never populated.

**Impact:** Consent records lack provenance. Under GDPR Article 7(1), the controller must demonstrate that consent was given. Records with `0.0.0.0` IP and null UserAgent are evidentially weak if challenged by a data protection authority.

### GAP-5 [HIGH]: Data Access Log is Hardcoded Stub

**Status:** PROVEN STUB

- `GdprService.java:311-329`: `getDataAccessLog()` returns two hardcoded sample entries regardless of user.
- `DataAccessLogEntry` has no `@Entity` annotation — it is a plain POJO.
- No real data access audit table exists.
- The endpoint `/api/users/me/data-access-log` is exposed to users and returns fake data.

**Impact:** GDPR Article 15 Right of Access includes the right to know who accessed your data. The endpoint exists but returns fabricated data, which could be considered deceptive if discovered by users or auditors.

### GAP-6 [MEDIUM]: Anonymization Incomplete — Hash Columns Not Cleared

**Status:** PROVEN GAP

- `GdprService.permanentlyDeleteUser()` at lines 238-240 nullifies `driverLicenseNumber`, `jmbg`, `pib`.
- However, the corresponding hash columns (`jmbgHash`, `pibHash`, `driverLicenseNumberHash` at `User.java:257,274,336`) are NOT cleared.
- Hash columns are SHA-256 — not reversible, but still pseudonymous identifiers under GDPR Recital 26.

**Impact:** Post-deletion, a party with the original JMBG/PIB can hash it and confirm the deleted user's identity via hash lookup — violating the purpose of anonymization.

### GAP-7 [MEDIUM]: Password Set to Plaintext "DELETED" on Anonymization

**Status:** PROVEN RISK

- `GdprService.java:242`: `user.setPassword("DELETED")` — stores literal string "DELETED" in password column.
- If the password column uses BCrypt (standard Spring Security), this bypasses the hash — the value stored is not a valid BCrypt hash.
- If an attacker can craft an auth bypass, the password check may behave unpredictably.

**Remediation:** Use `passwordEncoder.encode(UUID.randomUUID().toString())` to produce a valid but unknowable BCrypt hash.

---

## DELIVERABLE 3: PRODUCTION HARDENING SCORECARD

### Functionality 1: GDPR Data Subject Rights

| Dimension | Score (1-5) | Evidence |
|-----------|-------------|----------|
| **Security** | 3 | AES-ECB encryption (GAP-2), PII masking in logs is solid (`PiiMaskingConverter.java`), consent IP stubbed (GAP-4) |
| **Correctness** | 2 | Grace period scheduler missing (GAP-1), chat data excluded from export (GAP-3), access log faked (GAP-5) |
| **Maintainability** | 4 | Clean separation: `GdprController` → `GdprService`, well-documented DTOs, clear retention period config via `@Value` |
| **Performance** | 4 | Rate limiting on export (1/24h), optimized DB queries (P0-5 fixes applied), distributed locking prevents double-execution |
| **Operability** | 2 | No alerting when deletion backlog grows, no admin dashboard for GDPR queue, retention stats endpoint exists but no monitoring integration |
| **Testability** | 2 | `LegalComplianceEntitiesTest.java` exists for entity validation, but no integration test for full deletion lifecycle, no test for retention schedulers, no test verifying export completeness |

**Confidence:** HIGH — all claims verified with file reads and grep searches.

### Functionality 2: Audit Logging & Traceability

| Dimension | Score (1-5) | Evidence |
|-----------|-------------|----------|
| **Security** | 4 | `AdminAuditLog` is immutable (`PreUpdate`/`PreRemove` throw at `AdminAuditLog.java:135-148`), captures IP/UserAgent, before/after state |
| **Correctness** | 4 | Admin actions tracked end-to-end, `AdminAuditInterceptor.java` adds X-Request-ID for correlation, verification audit trail in `RenterVerificationAudit.java` |
| **Maintainability** | 4 | Proper indexing (`idx_audit_admin_created`, `idx_audit_resource`, etc. at `AdminAuditLog.java:36-39`), clean enum-based actions |
| **Performance** | 4 | Optimized indexes, LAZY fetch on admin FK, TEXT columns for before/after state |
| **Operability** | 3 | Request IDs in headers, MDC context set in interceptor, but no centralized log aggregation integration documented |
| **Testability** | 3 | Admin controller tests exist, but no test verifying immutability enforcement or before/after state JSON accuracy |

**Confidence:** HIGH

### Functionality 3: Production Profile Hardening

| Dimension | Score (1-5) | Evidence |
|-----------|-------------|----------|
| **Security** | 4 | All secrets via env vars (`application-prod.properties:74-76,113,122`), CORS restricted to `rentoza.rs`, SameSite=Strict cookies, error details suppressed (`:235-238`), rate limiting per-endpoint |
| **Correctness** | 4 | `PAYMENT_PROVIDER` has no default — fail-fast (`:25`), DDL=none (`:86`), PgBouncer-compatible settings (`:637-644`) |
| **Maintainability** | 4 | Well-documented with inline checklist (`:541-581`), clear separation of concerns between profiles |
| **Performance** | 4 | Batch inserts (`:97-101`), gzip compression (`:226-228`), HikariCP tuned, read-replica routing support (`:627-630`) |
| **Operability** | 3 | Prometheus metrics exposed (`:296`), health checks configured (`:308-311`), but actuator shows `details=always` in prod (`:297`) which could leak internal state |
| **Testability** | 3 | Smoke test profile exists (`staging-smoke.env`), but no automated profile validation test |

**Confidence:** HIGH

### Functionality 4: Deploy & Infrastructure Safety

| Dimension | Score (1-5) | Evidence |
|-----------|-------------|----------|
| **Security** | 4 | GCP Secret Manager for all 25 secrets, MOCK payment guard in `deploy-backend-secure.sh:357-361`, non-root Docker containers |
| **Correctness** | 3 | Correct deploy order — chat first, then backend (`REDEPLOYING_LOGIC.md:47-55`), immutable image tags, but `PAYMENT_PROVIDER` empty in `prod.env` could deploy with empty value |
| **Maintainability** | 2 | **No IaC (Terraform/Pulumi)** — all infrastructure is imperative gcloud CLI, not version-controlled |
| **Performance** | 3 | Appropriate Cloud Run sizing (1Gi/1CPU, 1-5 instances), min-instances=1 prevents cold starts |
| **Operability** | 3 | Comprehensive runbook (`ON_CALL_RUNBOOK.md`, 497 lines), rollback documented via `--image-tag`, but no blue-green/canary |
| **Testability** | 2 | No infrastructure tests, no post-deploy smoke test automation, Lighthouse CI for frontend only |

**Confidence:** HIGH

### Functionality 5: Monitoring & Alerting

| Dimension | Score (1-5) | Evidence |
|-----------|-------------|----------|
| **Security** | 3 | Prometheus/actuator endpoints exposed but actuator `show-details=always` in prod (`:297`) — should be `when-authorized` |
| **Correctness** | 4 | 9 Prometheus dashboard tiles covering request rate, error rate, latency, DB pool, circuit breakers, JVM, CPU, bookings, payments (`MONITORING_DASHBOARD_CONFIG.md:12-197`) |
| **Maintainability** | 3 | Dashboard config is JSON in a markdown file — not deployable as IaC |
| **Performance** | 4 | P95 latency histograms, HikariCP metrics, circuit breaker state tracking |
| **Operability** | 3 | 5 alert policies defined — error rate, latency, circuit breaker, CPU, DB pool (`MONITORING_DASHBOARD_CONFIG.md:204-407`), escalation matrix in runbook, but alerts reference Slack/PagerDuty channels not verified as configured |
| **Testability** | 2 | No synthetic monitoring, no alert validation tests, no chaos engineering setup |

**Confidence:** MEDIUM — alert policies documented but cannot verify actual GCP Monitoring deployment.

---

## DELIVERABLE 4: OPERABILITY GAPS THAT WOULD HURT MTTR

### OPS-GAP-1: No Structured Log Aggregation Integration

**Evidence:** Logs are written to file via logback (`application-prod.properties:287`: `LOG_FILE=${LOG_FILE_PATH:logs/application.log}`). Cloud Run containers have ephemeral storage — file logs are lost on instance restart. Cloud Run does stream stdout to Cloud Logging, but there is no documented integration with a structured query system (no Loki, ELK, or BigQuery export configuration).

**MTTR Impact:** During an incident, operators must use `gcloud logging read` with raw filters (documented in runbook at `ON_CALL_RUNBOOK.md:334-354`). This is slow for cross-service correlation. A request-ID-based search requires manual gcloud CLI invocation per service.

### OPS-GAP-2: No Database Backup/Restore Procedure

**Evidence:** No backup scripts found. No Terraform for Supabase. `ON_CALL_RUNBOOK.md:422-434` mentions database migration rollback but explicitly warns "manual process" and "consult team lead due to data loss risk." No point-in-time recovery documentation.

**MTTR Impact:** A data corruption or accidental deletion incident has no documented recovery path. Supabase provides automated backups, but the team has no documented procedure to verify or restore from them.

### OPS-GAP-3: No Canary/Blue-Green Deployment

**Evidence:** Deployment is a direct Cloud Run service update (`deploy-backend-secure.sh:432-450`). No traffic splitting between old and new revisions. Rollback is manual: list revisions, update traffic routing (runbook lines 404-420).

**MTTR Impact:** A bad deploy immediately affects 100% of traffic. The "detect → decide → rollback" cycle requires manual gcloud commands. Estimated MTTR for a bad deploy: 5-15 minutes (manual, assumes operator is awake and alert fires promptly).

### OPS-GAP-4: Shared Redis Between Staging and Production

**Evidence:** Both `prod.env:42-43` and `staging.env` reference `precise-muskrat-38506.upstash.io:6379`. Same Redis host for both environments.

**MTTR Impact:** A staging load test or misconfiguration could exhaust Redis connections or corrupt rate-limit state, affecting production rate limiting and distributed scheduler locking. This is a blast-radius concern.

### OPS-GAP-5: Actuator Health Shows Details=Always in Production

**Evidence:** `application-prod.properties:297`: `management.endpoint.health.show-details=always`

**MTTR Impact:** Beneficial for debugging but leaks internal component names, database health status, and disk space to any caller of `/actuator/health`. Should be `when-authorized` with admin role check. An attacker can use this to map internal dependencies.

### OPS-GAP-6: No Cross-Service Health Dependency Chain

**Evidence:** Backend depends on chat-service, but there is no health check that validates the chat-service URL is reachable. Circuit breaker exists for `bookingEnrichment` in chat-service (`application.properties:108-116`), but backend has no reciprocal check. If chat-service is down, backend cannot detect it proactively.

**MTTR Impact:** Chat failures manifest as user-facing errors rather than degraded-but-functioning state. A chat-service outage would require manual investigation to identify the root cause.

### OPS-GAP-7: 35+ Scheduled Jobs with No Centralized Dashboard

**Evidence:** 35+ `@Scheduled` cron jobs across 10+ scheduler classes (`BookingScheduler`, `CheckInScheduler`, `CheckOutScheduler`, `PaymentLifecycleScheduler`, `SagaRecoveryScheduler`, `TokenCleanupScheduler`, `SecurityMaintenanceScheduler`, `LicenseExpiryScheduler`, `RenterDocumentRetentionScheduler`, `AdminDashboardService`, `TimeWindowMetrics`). Each has its own logging but no centralized "last-run" dashboard.

**MTTR Impact:** If a scheduler silently fails (e.g., distributed lock never acquired, exception swallowed), there is no alerting mechanism. The GDPR retention scheduler could fail for weeks before anyone notices.

---

## PASS B: FALSE-POSITIVE VERIFICATION AND MISSED CRITICAL PATHS

### Verified as NOT False Positives

1. **GAP-1 (Missing deletion executor):** Confirmed by exhaustive grep — `permanentlyDeleteUser` has zero callers. The project's own documentation admits it is "Recommended Next Step." **Confirmed REAL.**

2. **GAP-2 (AES-ECB):** `AttributeEncryptor.java:37` uses `Cipher.getInstance("AES")`. Java's JCE documentation confirms this defaults to ECB mode. `AttributeEncryptor.java:30` uses only 16 bytes (AES-128). **Confirmed REAL.**

3. **GAP-3 (Chat GDPR):** No inter-service event mechanism found. Chat `Message.senderId` is not a FK (`chat-service/model/Message.java:29-31`). No `@EventListener` or message queue consumer in chat-service for user deletion events. **Confirmed REAL.**

4. **GAP-5 (Fake access log):** `GdprService.java:314` comment explicitly says "In production, this would query an audit log table. For now, return a sample structure." The endpoint is live and returns this fake data. **Confirmed REAL.**

### Potential False Positive Checked

- **Encryption fallback concern:** `AttributeEncryptor.java:52-59` returns raw data on decryption failure. This is intentional for gradual migration from unencrypted legacy data. In a green-field deployment with no legacy data, this is benign. However, it means a key mismatch would silently serve data rather than failing — a design choice that trades correctness for availability. **Verdict: REAL concern for production, acceptable for initial migration phase only.**

### Additional Critical Path Discovered in Pass B

- **Account lockout reset gap:** `SecurityMaintenanceScheduler.java` handles token cleanup and denylist cleanup but does NOT handle account lockout reset. The `User.lockedUntil` field (`User.java:195`) is checked via `isAccountLocked()` (line 657) which compares against `Instant.now()` — so lockout expires naturally by time comparison. This is correct behavior, not a gap. **Verified: NOT a gap.**

- **Export rate limiting is in-memory only:** `GdprService.java:47`: `private final Map<Long, LocalDateTime> lastExportTime = new ConcurrentHashMap<>()`. In a multi-instance Cloud Run deployment, each instance has its own map. A user could export once per instance per 24 hours. With max 5 instances, that's up to 5 exports per 24h. **Verdict: LOW severity — rate limit is advisory, not security-critical.**

---

## OVERALL ASSESSMENT

### Decision: CONDITIONAL GO with MANDATORY remediations before handling real user PII

**Rationale:** The system demonstrates mature engineering patterns across most dimensions — immutable audit logs, distributed scheduler locking, PII masking in logs, comprehensive rate limiting, Supabase RLS on 28+ tables, circuit breakers, and a well-structured runbook. However, three CRITICAL gaps prevent unconditional go:

| # | Gap | Severity | Must-fix timeline |
|---|-----|----------|-------------------|
| 1 | No GDPR deletion executor (GAP-1) | CRITICAL | Before accepting real user registrations |
| 2 | AES-ECB for PII encryption (GAP-2) | CRITICAL | Before storing real JMBG/PIB data |
| 3 | Chat service GDPR isolation (GAP-3) | HIGH | Before chat feature goes live |
| 4 | Consent IP/UserAgent stub (GAP-4) | HIGH | Before consent mechanisms are relied upon |
| 5 | Fake data access log endpoint (GAP-5) | HIGH | Remove endpoint or implement; do not ship fake data |
| 6 | Hash columns not cleared on deletion (GAP-6) | MEDIUM | Before GDPR compliance claim |
| 7 | Plaintext "DELETED" password (GAP-7) | MEDIUM | Before deletion feature ships |

### Regression Tests Required

1. `GdprDeletionSchedulerIntegrationTest` — create user, schedule deletion, advance clock 31 days, verify `permanentlyDeleteUser()` executes and all PII fields are null including hash columns.
2. `AttributeEncryptorGcmMigrationTest` — verify AES-GCM produces different ciphertext for identical plaintext (non-deterministic), verify backward compatibility with ECB-encrypted data.
3. `ChatServiceUserDeletionPropagationTest` — delete user in backend, verify chat messages are anonymized (`senderId` → null or placeholder).
4. `ConsentAuditComplianceTest` — update consent, verify IP address and UserAgent are captured from real HTTP request.
5. `DataAccessLogRealnessTest` — verify endpoint returns actual audit data, not hardcoded samples.
6. `GdprExportCompletenessTest` — verify export includes chat messages, payment history, all PII categories.
7. `RetentionSchedulerAlertingTest` — verify that scheduler failures emit a metric that triggers an alert.

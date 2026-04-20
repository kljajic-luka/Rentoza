# Rentoza Compliance Remediation — Final Execution Prompt

## Mission

Implement the compliance remediation backlog for Rentoza (Serbia) under a **marketplace-intermediary model**:
- Rentoza is the software/payment intermediary.
- Owner and renter are the contracting rental parties.
- Rentoza must provide enforceable agreement evidence, strong approval controls, and payout/tax compliance infrastructure.

This is an implementation task, not analysis. Make code changes directly.

## Legal Assumption Gate (Mandatory)

Proceed only under this assumption:
- Marketplace-intermediary interpretation has been reviewed by Serbian counsel and approved for this product model.

If the code requires legal interpretation beyond this assumption, implement technical infrastructure and leave a clear TODO with legal owner.

## Repo and Stack

- Monorepo: `apps/backend`, `apps/frontend`, `apps/chat-service`
- Backend: Java 21, Spring Boot 3.5.6, JPA/Hibernate, Flyway, PostgreSQL
- Frontend: Angular standalone + TS
- Payments: `PaymentProvider` + `MonriPaymentProvider`
- Current Flyway head: V77
- New migrations start at V78

## Execution Contract

1. Work in sequence by phases.
2. Use atomic commits per phase.
3. Do not break existing behavior outside scope.
4. Run backend tests after each phase (`mvn test` in `apps/backend`).
5. If a test fails due to intended behavior change, update test expectations.
6. At phase end, print:
   - files changed
   - migrations added
   - tests run + result
   - known risks

## Non-Goals (Do Not Implement)

- No Tourism Register integration.
- No enforcement of minimum fleet size.
- Do not change vehicle age policy from 15 to 5 years in this remediation.
- No insurer API partnership work.
- No PDF generation for agreements (canonical JSON + hash is sufficient).

---

## PHASE 1 — Rental Agreement Infrastructure (P0 Blocker)

### 1.1 Migration V78

Create `V78__rental_agreement_infrastructure.sql` with table `rental_agreements`:

- `id BIGSERIAL PK`
- `booking_id BIGINT NOT NULL UNIQUE FK bookings(id)`
- `agreement_version VARCHAR(20) NOT NULL` (ex: `1.0.0`)
- `agreement_type VARCHAR(30) NOT NULL DEFAULT 'STANDARD_RENTAL'`
- `content_hash VARCHAR(128) NOT NULL` (SHA-256 of canonical agreement payload/text)
- `generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- `owner_accepted_at TIMESTAMPTZ NULL`
- `owner_ip VARCHAR(45) NULL`
- `owner_user_agent VARCHAR(500) NULL`
- `renter_accepted_at TIMESTAMPTZ NULL`
- `renter_ip VARCHAR(45) NULL`
- `renter_user_agent VARCHAR(500) NULL`
- `owner_user_id BIGINT NOT NULL FK users(id)`
- `renter_user_id BIGINT NOT NULL FK users(id)`
- `vehicle_snapshot_json JSONB NOT NULL`
- `terms_snapshot_json JSONB NOT NULL`
- `status VARCHAR(20) NOT NULL DEFAULT 'PENDING'` (`PENDING`, `OWNER_ACCEPTED`, `FULLY_ACCEPTED`, `EXPIRED`, `VOIDED`)
- `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- `updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`

Indexes:
- unique index booking_id
- index owner_user_id
- index renter_user_id
- index status

Immutability:
- Block UPDATE for immutable columns: `content_hash`, `agreement_version`, `vehicle_snapshot_json`, `terms_snapshot_json`, `generated_at`, `booking_id`, `owner_user_id`, `renter_user_id`
- Block DELETE on `rental_agreements`
- Follow V77 trigger style conventions
- Keep migration idempotent-safe (`IF NOT EXISTS` patterns compatible with PostgreSQL)

### 1.2 Backend domain

Create:
- `RentalAgreement.java` (JPA entity in `org.example.rentoza.booking`)
- `RentalAgreementRepository.java`
- `RentalAgreementService.java`

Use `@JdbcTypeCode(SqlTypes.JSON)` for JSONB fields.

Service API:
- `generateAgreement(Booking booking)`
- `acceptAsOwner(Long bookingId, Long userId, String ip, String userAgent)`
- `acceptAsRenter(Long bookingId, Long userId, String ip, String userAgent)`
- `getAgreementForBooking(Long bookingId)`
- `isFullyAccepted(Long bookingId)`

Rules:
- Generate exactly once per booking (idempotent).
- Accept endpoints must be idempotent and retry-safe.
- If owner accepts first: `OWNER_ACCEPTED`; once both accepted: `FULLY_ACCEPTED`.
- Capture acceptance metadata (timestamp/IP/UA).

Canonical hashing:
- Build deterministic payload from booking/vehicle/terms snapshots.
- Use stable JSON serialization (sorted keys) before SHA-256.
- Never recalculate existing agreement hash after creation.

### 1.3 Integration points

Booking creation integration:
- After booking is successfully persisted and payment authorization step succeeds, call `generateAgreement(...)`.
- Apply to real booking flow statuses used in this codebase (do not assume a `CONFIRMED` enum exists).

Check-in handshake gate:
- In `CheckInService`, block handshake completion if agreement is not fully accepted.
- Error message: `Rental agreement must be accepted by both parties before starting the trip.`

Rollout safety:
- Add feature flag: `app.compliance.rental-agreement.checkin-enforced` (default `false`).
- If flag is false, only emit warning logs.
- If true, enforce hard block.
- This prevents breaking active legacy trips during deployment.

### 1.4 API

Add secured endpoints:
- `GET /api/bookings/{bookingId}/agreement`
- `POST /api/bookings/{bookingId}/agreement/accept`

Authorization:
- only booking renter, booking owner, or admin (read-only for admin if consistent with existing patterns).
- acceptance only by booking parties.

Metadata capture:
- IP from `X-Forwarded-For` fallback `request.getRemoteAddr()`
- User-Agent from header

### 1.5 Frontend

Add agreement UI to booking details / pre-checkin flow:
- show agreement content summary and status of both parties
- require explicit accept action
- disable handshake start action until both accepted when backend flag enforcement is on

### 1.6 Backfill

Add one-time backfill command/service:
- create agreements for existing future/active bookings that lack one
- never overwrite existing agreements
- log count created/skipped

### 1.7 Acceptance criteria

- migration V78 applies cleanly
- agreement generated for new booking
- both parties can accept idempotently
- handshake is blocked only when enforcement flag is on and agreement not fully accepted
- tests added/updated and green

Commit: `feat(compliance): add rental agreement infrastructure and enforcement gate`

---

## PHASE 2 — Admin Vehicle Approval Hard Gate (P0)

### 2.1 Enforce compliance in approve action

In `AdminCarService.approveCar(...)`, enforce server-side gate before approval:
- derive review/compliance state using existing logic (`canApprove`)
- if false, throw explicit `IllegalStateException` with summary details

No direct-API bypass allowed.

### 2.2 Audit payload

Log approval decisions and rejection reason to existing admin audit infrastructure.
Include:
- owner verification status
- missing/unverified/expired docs
- final `canApprove` value

### 2.3 Acceptance criteria

- direct approve API cannot approve non-compliant car
- admin UI behavior remains consistent
- tests for bypass attempt fail correctly

Commit: `fix(admin): enforce compliance gate on car approval`

---

## PHASE 3 — Tax Withholding Infrastructure (P0 Blocker)

### 3.1 Migration V79

Create `V79__tax_withholding_infrastructure.sql`.

Add to `payout_ledger`:
- `gross_owner_income NUMERIC(19,2)`
- `normalized_expenses_rate NUMERIC(5,4) DEFAULT 0.2000`
- `taxable_base NUMERIC(19,2)`
- `income_tax_rate NUMERIC(5,4) DEFAULT 0.2000`
- `income_tax_withheld NUMERIC(19,2)`
- `net_owner_payout NUMERIC(19,2)`
- `tax_withholding_status VARCHAR(30) DEFAULT 'CALCULATED'`
- `owner_tax_type VARCHAR(20)`
- `remittance_reference VARCHAR(100)`

Create `tax_withholding_summary`:
- `id BIGSERIAL PK`
- `owner_user_id BIGINT NOT NULL FK users(id)`
- `tax_period_year INT NOT NULL`
- `tax_period_month INT NOT NULL`
- `total_gross_income NUMERIC(19,2)`
- `total_normalized_expenses NUMERIC(19,2)`
- `total_taxable_base NUMERIC(19,2)`
- `total_tax_withheld NUMERIC(19,2)`
- `total_net_paid NUMERIC(19,2)`
- `payout_count INT`
- `ppppd_filed BOOLEAN DEFAULT FALSE`
- `ppppd_filing_date DATE`
- `ppppd_reference VARCHAR(100)`
- `created_at TIMESTAMPTZ DEFAULT NOW()`
- `updated_at TIMESTAMPTZ DEFAULT NOW()`
- unique `(owner_user_id, tax_period_year, tax_period_month)`

### 3.2 Calculation logic update

Update payout scheduling/execution:

Current conceptual:
- `grossOwnerIncome = tripAmount - platformFee`

New:
- Determine `ownerTaxType` from domain model:
  - `OwnerType.INDIVIDUAL -> INDIVIDUAL` (withholding applies)
  - `OwnerType.LEGAL_ENTITY -> LEGAL_ENTITY` (no withholding)
  - if entrepreneur type exists in codebase, map accordingly; if not present, do not invent DB enum values beyond current model
- For `INDIVIDUAL`:
  - `normalizedExpenses = grossOwnerIncome * 0.20`
  - `taxableBase = grossOwnerIncome - normalizedExpenses`
  - `incomeTaxWithheld = taxableBase * 0.20`
  - `netOwnerPayout = grossOwnerIncome - incomeTaxWithheld`
- For non-individual:
  - `incomeTaxWithheld = 0`
  - `netOwnerPayout = grossOwnerIncome`
  - `tax_withholding_status = EXEMPT`

Payout transfer amount sent to Monri must be `netOwnerPayout`.

Rounding:
- Monetary amounts scale 2, `RoundingMode.HALF_UP`
- Rate fields scale 4, `RoundingMode.HALF_UP`
- Snapshot all rates/amounts into ledger at creation time (immutable accounting snapshot)

### 3.3 Services and admin API

Create `TaxWithholdingService` with:
- `calculateWithholding(...)`
- `generateMonthlyStatement(ownerId, year, month)`
- `aggregateForPPPPD(year, month)`

Add admin endpoints:
- `GET /api/admin/tax/monthly-summary?year=&month=`
- `GET /api/admin/tax/owner/{userId}/statements?year=`
- `POST /api/admin/tax/monthly-summary/{id}/mark-filed`

### 3.4 Backfill

Backfill existing payout rows:
- set `gross_owner_income` from historical payout basis
- compute withholding fields with current standard rates for historical consistency note
- do not alter already completed transfer references

### 3.5 Acceptance criteria

- payout ledger includes withholding snapshots
- individual owners get net payout after withholding
- non-individual remain exempt
- monthly aggregation works and is unique by owner+period
- admin tax endpoints secured and functional

Commit: `feat(tax): add withholding ledger, summary, and net payout execution`

---

## PHASE 4 — Owner Consent Persistence + OAuth Path Fix (P1)

### 4.1 Migration V80

Create `V80__owner_consent_persistence.sql` adding to `users`:
- `host_agreement_accepted_at TIMESTAMPTZ`
- `vehicle_insurance_confirmed_at TIMESTAMPTZ`
- `vehicle_registration_confirmed_at TIMESTAMPTZ`
- `consent_ip VARCHAR(45)`
- `consent_user_agent VARCHAR(500)`

### 4.2 Backend fixes

- Persist consent timestamps + metadata when owner agreements are accepted in profile completion/registration.
- In OAuth owner completion validation, enforce same agreement checks as non-OAuth owner registration.
- Ensure both local and OAuth flows write equivalent consent evidence.

### 4.3 Acceptance criteria

- agreement booleans are not only validated; they are persisted with provenance
- OAuth owner completion cannot bypass legal consent checks
- tests cover both flows

Commit: `fix(auth): persist owner consents and enforce OAuth agreement validation`

---

## PHASE 5 — Status Model Drift Cleanup (P1)

### 5.1 Migrate app logic from `approvalStatus` to `listingStatus`

Search and replace all runtime query and gate usage:
- repositories
- service filtering
- bookability checks
- admin flows

Use `ListingStatus.APPROVED` as the source of truth.

### 5.2 Compatibility

- Keep DB `approval_status` column for backward compatibility.
- Stop using it in active application logic.
- Add code comment noting legacy retention.

### 5.3 Acceptance criteria

- no functional query path relies on `approvalStatus` for public availability/bookability
- tests updated to validate `listingStatus` logic

Commit: `refactor(car): migrate approval logic to listingStatus source of truth`

---

## PHASE 6 — Targeted Consistency Fixes (P1/P2)

### 6.1 Advance notice alignment

Pick one policy source and align both code and ToS.
Default implementation target:
- set `DEFAULT_ADVANCE_NOTICE_HOURS = 2`
- keep ToS at 2 hours

### 6.2 Expose per-vehicle deposit in DTO/service

Add `securityDepositRsd` to `CarRequestDTO` with validation:
- min `10000`
- max `200000`

Map through create/update paths in `CarService`.

### 6.3 Handshake comment correction

In frontend handshake component, remove inaccurate “digital signatures captured” claim.
Replace with accurate statement about confirmation evidence (timestamp/geolocation/device context).

### 6.4 Booking legal-role metadata (V81)

Create `V81__booking_legal_role_metadata.sql`:
- `platform_role VARCHAR(30) DEFAULT 'INTERMEDIARY'`
- `contract_type VARCHAR(30) DEFAULT 'OWNER_RENTER_DIRECT'`
- `terms_version VARCHAR(20)`
- `terms_content_hash VARCHAR(128)`

Set these values at booking creation.

### 6.5 Acceptance criteria

- policy text and backend behavior aligned
- owner can set listing-level deposit
- booking legal role metadata persists
- no misleading signature claims in UI comments/docs

Commit: `feat(compliance): align policy defaults, deposit DTO, and booking legal metadata`

---

## Testing and Verification Standard (Every Phase)

Run:
- `cd apps/backend && mvn test`

For frontend changes:
- run existing frontend tests used in repo CI
- if e2e impacted, run relevant Playwright specs for booking/check-in agreement path

Add tests for:
- agreement generation and acceptance transitions
- handshake block behavior behind feature flag
- admin approval hard gate
- withholding calculations and payout amount sent to provider
- OAuth consent enforcement
- listingStatus-based bookability

---

## Final Delivery Format (Required)

At the end, output:

1. `Completed phases`
2. `Migrations added`
3. `Key files changed`
4. `Behavior changes`
5. `Test results`
6. `Manual rollout steps`
7. `Known limitations / follow-ups`

Manual rollout steps must include:
- run Flyway migrations
- backfill job for agreements
- backfill tax fields
- toggle `app.compliance.rental-agreement.checkin-enforced` from false to true only after both clients deploy

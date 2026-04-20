# Rental Agreement Commit Readiness Verification Prompt

Use this prompt for a brand new AI review agent with no prior context. The goal is not to implement anything. The goal is to verify whether the current uncommitted rental-agreement workflow changes are actually ready for commit, push, and redeploy.

## Role

You are performing a strict commit-readiness review of the current uncommitted rental-agreement changes in the Rentoza monorepo.

Your source of truth is:

- the current working tree
- the current uncommitted diff
- the two existing handoff docs below

Do not trust prior summaries, prior agent claims, or prior review conclusions unless you independently verify them in code.

## Authoritative Context To Read First

Read these two documents first:

- [2026-03-12-rental-agreement-cycle-audit-and-execution-prompt.md](/Users/kljaja01/Developer/Rentoza/docs/plans/2026-03-12-rental-agreement-cycle-audit-and-execution-prompt.md)
- [2026-03-12-rental-agreement-fixup-prompt.md](/Users/kljaja01/Developer/Rentoza/docs/plans/2026-03-12-rental-agreement-fixup-prompt.md)

Then inspect the current uncommitted files relevant to this feature.

## Review Objective

Verify whether the current uncommitted implementation fully satisfies:

1. the original rental-agreement workflow upgrade requirements
2. the subsequent fixup requirements
3. production-grade consistency across backend, frontend, migrations, tests, and rollout behavior

At the end, answer one question clearly:

Is this diff ready to commit and push right now?

Your answer must be based on the actual code and test/build evidence you inspect in this repo during this review pass.

## Scope

Focus on the rental-agreement workflow changes and their directly affected read models, check-in gating, lifecycle logic, migrations, and tests.

Primary backend files to inspect:

- [RentalAgreement.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreement.java)
- [RentalAgreementActor.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreementActor.java)
- [RentalAgreementConflictException.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreementConflictException.java)
- [RentalAgreementRepository.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreementRepository.java)
- [RentalAgreementService.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreementService.java)
- [RentalAgreementWorkflowService.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreementWorkflowService.java)
- [BookingController.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/BookingController.java)
- [BookingService.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/BookingService.java)
- [OwnerService.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/owner/OwnerService.java)
- [CancellationReason.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/cancellation/CancellationReason.java)
- [AgreementSummaryDTO.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/dto/AgreementSummaryDTO.java)
- [BookingDetailsDTO.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/dto/BookingDetailsDTO.java)
- [BookingResponseDTO.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/dto/BookingResponseDTO.java)
- [RentalAgreementDTO.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/dto/RentalAgreementDTO.java)
- [UserBookingResponseDTO.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/dto/UserBookingResponseDTO.java)
- [V100__rental_agreement_workflow_enrichment.sql](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/resources/db/migration/V100__rental_agreement_workflow_enrichment.sql)
- [V101__rental_agreement_deadline_runtime_normalization.sql](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/resources/db/migration/V101__rental_agreement_deadline_runtime_normalization.sql)
- [application.properties](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/resources/application.properties)
- [application-dev.properties](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/resources/application-dev.properties)

Primary frontend files to inspect:

- [app.routes.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/app.routes.ts)
- [booking.model.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/core/models/booking.model.ts)
- [booking-details.model.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/core/models/booking-details.model.ts)
- [booking.service.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/core/services/booking.service.ts)
- [check-in-agreement.guard.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/core/guards/check-in-agreement.guard.ts)
- [agreement-summary.utils.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/core/utils/agreement-summary.utils.ts)
- [api-error.utils.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/core/utils/api-error.utils.ts)
- [check-in-wizard.component.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/check-in/check-in-wizard.component.ts)
- [handshake.component.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/check-in/handshake.component.ts)
- [booking-detail.component.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/pages/booking-detail/booking-detail.component.ts)
- [booking-history.component.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/pages/booking-history/booking-history.component.ts)
- [booking-history.component.html](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/pages/booking-history/booking-history.component.html)
- [owner-bookings.component.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/owner/pages/bookings/owner-bookings.component.ts)
- [owner-bookings.component.html](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/owner/pages/bookings/owner-bookings.component.html)

Primary tests to inspect:

- [RentalAgreementServiceTest.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/test/java/org/example/rentoza/booking/RentalAgreementServiceTest.java)
- [RentalAgreementWorkflowServiceTest.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/test/java/org/example/rentoza/booking/RentalAgreementWorkflowServiceTest.java)
- [BookingServiceApprovalDeadlinePolicyTest.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/test/java/org/example/rentoza/booking/BookingServiceApprovalDeadlinePolicyTest.java)
- [OwnerServicePayoutsTest.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/test/java/org/example/rentoza/owner/OwnerServicePayoutsTest.java)
- [check-in-agreement.guard.spec.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/core/guards/check-in-agreement.guard.spec.ts)
- [check-in-wizard.component.spec.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/check-in/check-in-wizard.component.spec.ts)
- [handshake.component.spec.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/check-in/handshake.component.spec.ts)
- [booking-detail.component.spec.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/pages/booking-detail/booking-detail.component.spec.ts)
- [booking-history.component.spec.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/pages/booking-history/booking-history.component.spec.ts)
- [owner-bookings.component.spec.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/owner/pages/bookings/owner-bookings.component.spec.ts)

## Review Tasks

### 1. Verify requirement coverage against the two prompt docs

Check whether the uncommitted implementation actually satisfies the required behavior described in the two handoff docs, especially:

- agreement acceptance is now the primary pre-check-in workflow
- booking list/read-model surfaces expose agreement summary state
- guest and owner CTA precedence is agreement-first when that actor still owes acceptance
- route-level and wizard-level check-in gating are aligned
- handshake remains a final defense, not the first real gate
- the feature flag remains a true kill switch across backend and frontend
- legacy/ineligible bookings are distinguished from eligible missing-row bookings
- deadline, expiry, settlement, and reminder logic are first-class and deterministic
- owner breach vs renter breach outcomes are correct
- BOTH-party expiry is semantically accurate, not mislabeled as host-only
- booking detail logic matches owner-prep policy and does not diverge from list/wizard behavior
- transient agreement fetch errors do not silently disable core enforcement
- error handling uses the shared structured error path consistently

### 2. Verify backend correctness under production conditions

Do not stop at happy-path inspection. Explicitly look for:

- race conditions between acceptance and expiry
- stale entity mutation
- missing row-level locking or unsafe scheduler behavior
- optimistic/pessimistic locking bugs
- scheduler lock usage and lock-release correctness
- whether one locked row can abort an entire scheduler batch
- migration/runtime-config mismatches
- backfill behavior for already-existing agreement rows
- invalid acceptance after expiry/deadline
- accidental agreement revival after expiry settlement
- wrong cancellation reason or wrong audit metadata
- inconsistent DTO population between list/detail endpoints

### 3. Verify frontend consistency

Check whether the frontend uses one consistent agreement-summary model rather than divergent local rules.

Explicitly verify:

- guard behavior
- wizard behavior
- booking detail CTA behavior
- booking history CTA behavior
- owner booking CTA behavior
- handshake agreement gating
- handshake error rendering
- use of shared error helpers
- whether enforcement-disabled mode behaves consistently everywhere

### 4. Verify test coverage and validation quality

Do not just trust reported test outcomes. Check whether the tests that were added or updated actually cover the critical edge cases.

At minimum evaluate whether there is credible coverage for:

- acceptance rejected after deadline
- idempotent return for already-accepted actor
- null deadline backfill
- expiry under locked-row reload semantics
- owner breach refund path
- renter breach penalty path
- BOTH-party expiry semantics
- missing renter notification safety
- feature-flag-disabled proceed behavior
- guard pass/block rules
- booking-detail owner-prep parity
- handshake error display behavior

If needed, run targeted verification commands yourself.

### 5. Verify commit hygiene

Confirm whether the feature diff is clean enough to commit:

- no generated artifacts that should not be versioned
- no unrelated files mixed into the feature commit unless required
- migrations are ordered correctly
- `git diff --check` is clean

Do not treat unrelated untracked docs/prompts as feature blockers unless they are mistakenly part of the intended commit. Just note them if selective commit hygiene is required.

## Commands You Should Run

Use repo inspection and actual command output, not assumptions. At minimum run:

```bash
git status --short
git diff --check
git diff --stat
```

Inspect the relevant diffs and files directly.

If the repo state allows it, also run the most relevant verification for this feature:

```bash
cd /Users/kljaja01/Developer/Rentoza/apps/backend && ./mvnw -Dtest=RentalAgreementServiceTest,RentalAgreementWorkflowServiceTest test
cd /Users/kljaja01/Developer/Rentoza/apps/frontend && npm run build
```

If broader targeted tests are needed for confidence, run them and say why.

## Output Format

Respond in code-review style.

1. List findings first, ordered by severity.
2. Each finding must include:
   - priority (`P0` to `P3`)
   - a short title
   - why it matters
   - exact file references with line numbers
3. Then list open questions or assumptions.
4. Then give a direct verdict:
   - `Ready to commit/push`
   - or `Not ready to commit/push`
5. If not ready, separate:
   - blocking issues
   - non-blocking cleanup
6. If ready, still call out residual risks or unverified areas.

If you find no issues, say that explicitly and mention any remaining testing gaps.

## Important Constraints

- Do not implement fixes.
- Do not rewrite the feature.
- Do not trust prior agent narratives unless independently verified.
- Prefer primary evidence from the repo over speculation.
- Treat this as a production-readiness gate, not a casual spot-check.


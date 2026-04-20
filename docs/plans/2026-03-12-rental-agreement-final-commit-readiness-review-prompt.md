# Rental Agreement Final Commit Readiness Review Prompt

Use this prompt for a brand new AI code-review agent with zero prior context.

## Role

You are performing the final independent commit-readiness review for the current uncommitted rental-agreement workflow diff in the Rentoza monorepo.

This is a verification-only pass.

- Do not implement fixes.
- Do not trust prior agent claims unless you independently verify them in code.
- Decide whether the current uncommitted rental-agreement diff is truly ready to commit and push.

## Read These Files First

Read these three documents in this exact order:

1. [2026-03-12-rental-agreement-cycle-audit-and-execution-prompt.md](/Users/kljaja01/Developer/Rentoza/docs/plans/2026-03-12-rental-agreement-cycle-audit-and-execution-prompt.md)
2. [2026-03-12-rental-agreement-fixup-prompt.md](/Users/kljaja01/Developer/Rentoza/docs/plans/2026-03-12-rental-agreement-fixup-prompt.md)
3. [2026-03-12-rental-agreement-commit-readiness-verification-prompt.md](/Users/kljaja01/Developer/Rentoza/docs/plans/2026-03-12-rental-agreement-commit-readiness-verification-prompt.md)

Treat:

- file 1 as the original required implementation scope
- file 2 as the required stabilization/fixup scope
- file 3 as the prior review rubric

Then review the actual current uncommitted code against those requirements.

## Review Objective

Determine whether the current uncommitted rental-agreement diff is ready for:

- commit
- push
- redeploy

You must base your answer on the actual code, migrations, tests, and working-tree diff.

## Current Implementation Areas You Must Verify

Do not assume these are correct. Verify them in code.

### Backend workflow and architecture

- agreement evidence model plus operational workflow fields
- deadline persistence and runtime normalization
- acceptance closure after deadline/expiry
- optimistic/pessimistic concurrency handling
- controller conflict mapping to `409`
- agreement summary generation for renter/owner/detail surfaces
- legacy/ineligible booking behavior vs eligible missing-row behavior
- scheduler distributed locks
- per-item isolated expiry processing
- per-item isolated backfill processing
- settlement behavior for:
  - owner breach
  - renter breach
  - both-party breach
- neutral audit/cancellation metadata for both-party breach
- reminder flow and notification safety

### Frontend workflow and UX

- agreement summary wiring on booking list surfaces
- agreement summary wiring on both booking dialog surfaces
- full booking detail parity
- route guard behavior
- wizard gate behavior
- handshake gate behavior
- handshake confirm error handling
- shared agreement-summary helper behavior
- true kill-switch behavior when rental-agreement enforcement is disabled
- owner-prep policy consistency:
  - host blocked until host accepts
  - host allowed to proceed after host acceptance even if renter still owes acceptance
  - guest/handshake/trip start still blocked until both accept

### Hygiene and release readiness

- `git diff --check`
- migration ordering and coherence
- no generated junk in the intended feature diff
- only selective staging needed for unrelated untracked docs/prompts

## High-Risk Areas To Review Carefully

Explicitly challenge these areas:

1. Scheduler safety
- confirm the expiry batch cannot be aborted by one failed overdue row
- confirm the null-deadline backfill pre-pass cannot abort the batch on one failed row
- confirm both paths log and continue per item

2. Kill-switch consistency
- confirm disabled enforcement does not still prioritize agreement acceptance on booking surfaces
- confirm summary recommendation logic, frontend CTA helpers, list pages, dialogs, and detail pages all align

3. Dialog parity
- confirm both renter and owner dialogs now surface agreement state in-body and expose the correct primary CTA
- confirm they no longer rely only on generic “manage/open full page” flows

4. Mutual-breach audit semantics
- confirm BOTH-party expiry no longer persists host-breach-only metadata
- confirm the cancellation reason / settlement metadata / user-facing text are all consistent

5. Late-flow consistency
- confirm handshake confirmation uses the shared HTTP error parser
- confirm wizard/detail/list/dialog all derive from the same agreement-summary rules rather than divergent local logic

6. Rollout compatibility
- confirm `V100` and `V101` work together coherently for already-migrated environments
- confirm active rows with null deadlines enter the workflow without manual user action

## Files To Inspect

Read the current uncommitted versions of these files at minimum.

### Backend

- [RentalAgreement.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreement.java)
- [RentalAgreementActor.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreementActor.java)
- [RentalAgreementConflictException.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreementConflictException.java)
- [RentalAgreementRepository.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreementRepository.java)
- [RentalAgreementService.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreementService.java)
- [RentalAgreementWorkflowService.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreementWorkflowService.java)
- [RentalAgreementExpiryProcessor.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreementExpiryProcessor.java)
- [RentalAgreementBackfillProcessor.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreementBackfillProcessor.java)
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

### Frontend

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
- [booking-details-dialog.component.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/booking-details-dialog/booking-details-dialog.component.ts)
- [booking-details-dialog.component.html](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/booking-details-dialog/booking-details-dialog.component.html)
- [owner-bookings.component.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/owner/pages/bookings/owner-bookings.component.ts)
- [owner-bookings.component.html](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/owner/pages/bookings/owner-bookings.component.html)
- [owner-booking-details-dialog.component.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/owner/dialogs/owner-booking-details-dialog/owner-booking-details-dialog.component.ts)
- [owner-booking-details-dialog.component.html](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/owner/dialogs/owner-booking-details-dialog/owner-booking-details-dialog.component.html)

### Tests

- [RentalAgreementServiceTest.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/test/java/org/example/rentoza/booking/RentalAgreementServiceTest.java)
- [RentalAgreementWorkflowServiceTest.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/test/java/org/example/rentoza/booking/RentalAgreementWorkflowServiceTest.java)
- [RentalAgreementExpiryProcessorTest.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/test/java/org/example/rentoza/booking/RentalAgreementExpiryProcessorTest.java)
- [BookingServiceApprovalDeadlinePolicyTest.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/test/java/org/example/rentoza/booking/BookingServiceApprovalDeadlinePolicyTest.java)
- [OwnerServicePayoutsTest.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/test/java/org/example/rentoza/owner/OwnerServicePayoutsTest.java)
- [check-in-agreement.guard.spec.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/core/guards/check-in-agreement.guard.spec.ts)
- [check-in-wizard.component.spec.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/check-in/check-in-wizard.component.spec.ts)
- [handshake.component.spec.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/check-in/handshake.component.spec.ts)
- [booking-detail.component.spec.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/pages/booking-detail/booking-detail.component.spec.ts)
- [booking-history.component.spec.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/pages/booking-history/booking-history.component.spec.ts)
- [booking-details-dialog.component.spec.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/booking-details-dialog/booking-details-dialog.component.spec.ts)
- [owner-bookings.component.spec.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/owner/pages/bookings/owner-bookings.component.spec.ts)
- [owner-booking-details-dialog.component.spec.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/owner/dialogs/owner-booking-details-dialog/owner-booking-details-dialog.component.spec.ts)

## Commands To Run

At minimum run:

```bash
git status --short
git diff --check
git diff --stat
```

Inspect the actual diffs and files directly.

If possible, rerun the most relevant verification:

```bash
cd /Users/kljaja01/Developer/Rentoza/apps/backend && ./mvnw -Dtest=RentalAgreementServiceTest,RentalAgreementWorkflowServiceTest,RentalAgreementExpiryProcessorTest test
cd /Users/kljaja01/Developer/Rentoza/apps/frontend && npm run build
cd /Users/kljaja01/Developer/Rentoza/apps/frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='src/app/features/bookings/check-in/handshake.component.spec.ts' --include='src/app/features/bookings/booking-details-dialog/booking-details-dialog.component.spec.ts' --include='src/app/features/owner/dialogs/owner-booking-details-dialog/owner-booking-details-dialog.component.spec.ts'
```

If you cannot run a command, say so explicitly and explain what you inspected instead.

## Output Format

Respond as a strict code review:

1. Findings first, ordered by severity
2. Each finding must include:
   - priority (`P0` to `P3`)
   - concise title
   - why it matters
   - exact file references with line numbers
3. Then list open questions or unverified areas
4. Then give a direct verdict:
   - `Ready to commit/push`
   - or `Not ready to commit/push`
5. If not ready, separate:
   - blocking issues
   - non-blocking cleanup
6. If ready, still note residual risks and any suites you did not rerun

If you find no issues, say that explicitly.

## Important Notes

- The repo contains unrelated untracked prompt/docs files. These are not feature blockers by themselves if the commit is staged selectively.
- Do not let prior agent confidence bias your review.
- Your job is to independently decide whether the current uncommitted rental-agreement diff is actually production-ready.


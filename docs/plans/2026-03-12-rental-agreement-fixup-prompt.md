# Rental Agreement Workflow Fixup Prompt

Use this prompt for the same implementation agent that made the current rental-agreement pass.

## Verification Summary

External review findings:

- `acceptance_deadline_at` type mismatch: partially true, but not a release blocker by itself. The table mixes `Instant/TIMESTAMPTZ` evidence timestamps with a deadline derived from booking-local `startTime`, and the repo already uses `LocalDateTime/TIMESTAMP WITHOUT TIME ZONE` for booking lifecycle windows. Do not churn this to `Instant` unless you also realign the broader booking-time model. If kept as `LocalDateTime`, document that it is a Belgrade-local business deadline.
- renter-breach notification NPE risk: true.
- handshake confirm error handler bypasses `getHttpErrorMessage()`: true.
- BOTH-party expiry using host-breach audit semantics: true.
- redundant `resolveRecommendedAction()` branch: true but low priority.
- guard `catchError(() => of(true))`: true as an intentional availability tradeoff; add an explicit comment, not new behavior.

Additional review findings from a second audit:

- expired agreements can still be accepted after deadline/expiry and can be revived into accepted states after settlement
- pre-existing agreement rows with null `acceptance_deadline_at` never enter reminder/expiry queries, so the new workflow does not fully apply to existing rows
- the new scheduled expiry/reminder jobs do not use the repo’s distributed scheduler lock pattern
- the frontend feature flag is not a true kill switch because the wizard still hard-blocks even when backend enforcement is disabled
- eligible bookings with a missing agreement row are marked `LEGACY` in list enrichment, but detail/check-in flows can lazily generate and then block, causing list/detail CTA drift
- booking detail still hardcodes `FULLY_ACCEPTED` while owner list/wizard use the new “host prep allowed after owner acceptance” rule
- the diff includes generated Firebase hosting cache output that should not ship with the feature commit

## Execution Prompt

Apply a focused fixup pass to the current uncommitted rental-agreement workflow changes. Do not redesign the feature. Preserve the current product policy unless explicitly changed below:

- host vehicle-prep remains allowed after host acceptance even if renter still owes acceptance
- guest check-in / handshake / trip start still require both parties accepted
- BOTH-party deadline expiry keeps the safe full-refund outcome
- keep `acceptance_deadline_at` as a Belgrade-local business deadline unless you find a concrete correctness bug that requires broader temporal refactoring

### Required fixes

1. Prevent post-expiry acceptance and agreement revival.
   - In `RentalAgreementService.acceptAsOwner()` and `acceptAsRenter()`, reject acceptance when the agreement is already expired or when the deadline has passed and the actor has not already accepted.
   - Return a structured `409` from the controller using the existing conflict envelope path.
   - Use a stable machine code such as `RENTAL_AGREEMENT_EXPIRED` or `RENTAL_AGREEMENT_ACCEPTANCE_CLOSED`.
   - Preserve idempotency for actors who already accepted before expiry.

2. Make the workflow apply to existing agreement rows.
   - Ensure rows that already exist but have `acceptance_deadline_at IS NULL` get a persisted deadline.
   - Because this work is still uncommitted, prefer fixing `V100__rental_agreement_workflow_enrichment.sql` directly if safe for the branch. If local dev state forces it, add a follow-up migration instead.
   - The end state must be that existing eligible agreements participate in reminder and expiry jobs without requiring a manual page load.

3. Add distributed locking/idempotency to the new scheduled jobs.
   - In `RentalAgreementWorkflowService`, inject and use `SchedulerIdempotencyService`.
   - Guard both `expireOverdueAgreements()` and `sendPendingAgreementReminders()` with repo-standard lock acquisition/release patterns.
   - Use lock TTLs slightly shorter than the cron interval, matching existing scheduler style elsewhere in the repo.

4. Remove list/detail/check-in agreement-state drift.
   - Do not label eligible bookings as `LEGACY` just because a rental-agreement row is missing.
   - Batch summary generation must distinguish:
     - true legacy / ineligible bookings
     - eligible bookings whose row is missing and should be generated or treated as pending
   - The same booking should not show “open check-in” on list pages and then immediately block on detail/check-in because a row got lazily generated later.
   - Reuse existing `RentalAgreementService.getOrGenerateAgreement()` semantics where appropriate, but do not introduce N+1 explosions.

5. Make the feature flag a real kill switch.
   - When `isRentalAgreementCheckinEnforced()` is off, the check-in wizard must not hard-block on agreement state.
   - Avoid duplicating divergent agreement rules in multiple frontend components.
   - Prefer driving the wizard gate from the same backend summary semantics already used by the guard/list surfaces, or otherwise centralize the logic so flag behavior stays aligned.

6. Align booking detail with the implemented owner-prep policy.
   - `booking-detail.component.ts` currently hard-requires `FULLY_ACCEPTED`.
   - Update it so its CTA logic matches the new backend summary semantics and the owner bookings page.
   - Do not regress renter blocking behavior.

7. Fix concrete correctness/consistency bugs.
   - Add a null guard for `booking.getRenter()` in the renter-breach notification branch.
   - Replace the handshake confirm error message extraction with `getHttpErrorMessage(err)`.
   - Simplify redundant `resolveRecommendedAction()` branches.
   - Add a short code comment in `check-in-agreement.guard.ts` explaining why `catchError(() => of(true))` is intentional and which downstream gate still protects the flow.

8. Correct BOTH-party expiry audit semantics without changing the refund outcome.
   - Do not record BOTH-party expiry as a host-breach-only narrative.
   - Add a distinct settlement policy string such as `BOTH_PARTIES_FULL_REFUND`.
   - Update customer-facing notification copy so it is neutral when both parties missed the deadline.
   - If low-risk for this branch, add a distinct cancellation reason for the mutual-breach case. If that is too invasive, keep the financial path but at minimum fix the stored policy metadata and notification wording.

9. Keep the commit clean.
   - Remove `apps/frontend/.firebase/hosting.ZGlzdC9yZW50b3phLWZyb250ZW5kL2Jyb3dzZXI.cache` from the feature diff unless it is intentionally versioned release output.

### Tests to add or update

- backend:
  - acceptance after expired deadline returns conflict
  - existing agreement with null deadline is backfilled into active workflow participation
  - feature flag off allows proceed semantics
  - BOTH-party expiry keeps full refund but records neutral policy metadata
  - renter-breach notification path does not throw when renter relation is unexpectedly absent
- frontend:
  - wizard does not hard-block when enforcement is disabled
  - legacy booking still passes the guard
  - `currentActorCanProceedToCheckIn === true` passes the guard
  - booking detail owner CTA follows the same agreement rule as owner list
  - handshake confirm error displays flat-string backend errors correctly

### Validation

Run targeted verification after the fixes:

- frontend build
- affected Angular specs for guard, booking-history, owner-bookings, booking-detail, handshake, and any wizard tests touched
- backend tests for `RentalAgreementWorkflowService` and any new service/controller tests added for acceptance closure and deadline backfill

Do not make unrelated refactors during this pass. The goal is a commit-ready stabilization of the existing feature, not a second redesign.

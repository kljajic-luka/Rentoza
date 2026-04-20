# Rental Agreement Cycle Audit And Execution Prompt

## Scope
- Frontend agreement discoverability and actionability
- Backend agreement lifecycle, check-in gating, and no-show/refund logic
- Booking list/detail/read-model coverage needed for enterprise-grade UX

## Verified Current State
- Full booking detail already renders a rental agreement card with acceptance action and blocks its own `canCheckIn()` unless the agreement is `FULLY_ACCEPTED`.
- Backend already blocks trip start at handshake completion when the agreement is not fully accepted, and staging/prod currently enable that enforcement flag.
- The existing backend kill switch is `FeatureFlags.isRentalAgreementCheckinEnforced()` backed by `app.compliance.rental-agreement.checkin-enforced`, and staging/prod currently set it to `true`: [FeatureFlags.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/config/FeatureFlags.java#L14), [prod.env](/Users/kljaja01/Developer/Rentoza/infrastructure/gcp/prod.env#L57), [staging.env](/Users/kljaja01/Developer/Rentoza/infrastructure/gcp/staging.env#L49)
- The current system does **not** make the agreement the dominant pre-check-in workflow. It is still possible to enter the check-in path from earlier surfaces without first resolving agreement acceptance.

## Audit Findings

### 1. Agreement enforcement happens too late in the lifecycle
Severity: Critical

Evidence:
- Backend checks agreement acceptance only when both handshake confirmations are present and trip start is about to happen: [CheckInService.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/checkin/CheckInService.java#L881)
- Guest booking history exposes a direct check-in CTA based only on booking status: [booking-history.component.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/pages/booking-history/booking-history.component.ts#L505), [booking-history.component.html](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/pages/booking-history/booking-history.component.html#L108)
- Owner bookings exposes a direct check-in CTA based only on booking status: [owner-bookings.component.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/owner/pages/bookings/owner-bookings.component.ts#L282), [owner-bookings.component.html](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/owner/pages/bookings/owner-bookings.component.html#L101)
- Check-in wizard loads normally and performs license validation, but has no route-level or wizard-level agreement gate: [check-in-wizard.component.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/check-in/check-in-wizard.component.ts#L647)
- Handshake blocks confirmation if agreement is not fully accepted, but that is the final step, not the first one: [handshake.component.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/check-in/handshake.component.ts#L818)

Impact:
- Users can spend time entering the wrong workflow and only learn about the agreement at or near the handshake.
- The product currently treats agreement acceptance as a late guardrail, not as the primary pre-pickup workflow.

### 2. The primary booking surfaces do not carry agreement state, so the UX cannot prioritize agreement acceptance
Severity: High

Evidence:
- Guest booking list DTO has no agreement status or per-party acceptance summary: [UserBookingResponseDTO.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/dto/UserBookingResponseDTO.java#L22)
- Owner booking DTO has no agreement summary either: [BookingResponseDTO.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/dto/BookingResponseDTO.java#L28)
- The renter dialog still requires going deeper to reach the full booking page: [booking-details-dialog.component.html](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/booking-details-dialog/booking-details-dialog.component.html#L161)
- The owner dialog also hides the full page behind a secondary action and does not render agreement state: [owner-booking-details-dialog.component.html](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/owner/dialogs/owner-booking-details-dialog/owner-booking-details-dialog.component.html#L179)
- The full booking page does have the agreement card, and its own `canCheckIn()` is already agreement-aware, but only after the user reaches that deeper surface: [booking-detail.component.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/pages/booking-detail/booking-detail.component.ts#L240), [booking-detail.component.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/pages/booking-detail/booking-detail.component.ts#L1835)

Impact:
- The system cannot show "Accept agreement" as the primary CTA in booking lists, cards, dialogs, notifications, or countdown banners.
- Users are forced into the exact deep navigation path described in the problem statement.

### 3. The agreement state model is evidence-oriented, not breach-oriented
Severity: High

Evidence:
- Agreement status enum includes `EXPIRED` and `VOIDED`, but the audited code does not operationalize agreement expiry ownership or settlement behavior: [RentalAgreementStatus.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreementStatus.java#L3)
- Acceptance writes evidence and transitions to `OWNER_ACCEPTED`, `RENTER_ACCEPTED`, or `FULLY_ACCEPTED`, but no deadline, breach actor, or expiry reason is recorded: [RentalAgreementService.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreementService.java#L145)
- No-show processing is keyed to check-in workflow status, not to agreement non-acceptance responsibility. Host no-show triggers refund, guest no-show does not apply the requested agreement-based fee model: [CheckInService.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/checkin/CheckInService.java#L1014)
- Stale handshake timeout currently always drives full-refund cancellation, regardless of which party failed to accept the agreement first: [CheckInScheduler.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/checkin/CheckInScheduler.java#L826)

Impact:
- The current model cannot correctly implement:
  - "guest failed to accept by deadline -> fee applies"
  - "owner failed to accept by deadline -> guest full refund"
- Any attempt to bolt that policy onto existing no-show statuses will blur agreement breach with operational no-show.

### 4. The handshake UI can silently drop the agreement gate on fetch failure
Severity: High

Evidence:
- Handshake treats any agreement fetch error as `null` and comments it as "legacy booking": [handshake.component.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/check-in/handshake.component.ts#L1162)
- `agreementNeeded()` returns `false` when agreement is `null`: [handshake.component.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/check-in/handshake.component.ts#L818)

Impact:
- A transient network error or backend error can make the UI behave as if no agreement exists.
- Backend still blocks trip start later, so the user gets a late failure instead of a stable, explicit agreement-required state.

### 5. Error handling around agreement actions is inconsistent and loses server detail
Severity: Medium

Evidence:
- Backend agreement endpoints return flat payloads like `{"error":"..."}`: [BookingController.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/BookingController.java#L658)
- Frontend agreement handlers mostly read `err.error?.message`, which will miss the flat `error` string shape and fall back to generic copy: [booking-detail.component.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/pages/booking-detail/booking-detail.component.ts#L1787), [handshake.component.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/features/bookings/check-in/handshake.component.ts#L1169)
- Shared API error utilities only recognize nested `error.message`, top-level `message`, or `userMessage`, not top-level string `error`: [api-error.utils.ts](/Users/kljaja01/Developer/Rentoza/apps/frontend/src/app/core/utils/api-error.utils.ts#L3)

Impact:
- Users do not reliably see the specific reason an agreement action failed.
- Agreement-specific UX cannot be trustworthy if the error contract is inconsistent.

### 6. Notification architecture is too thin for a priority workflow
Severity: Medium

Evidence:
- Agreement generation sends a one-time `RENTAL_AGREEMENT_PENDING` notification to both parties: [RentalAgreementService.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreementService.java#L121)
- Check-in reminders are generic check-in reminders, not agreement-remediation reminders: [CheckInService.java](/Users/kljaja01/Developer/Rentoza/apps/backend/src/main/java/org/example/rentoza/booking/checkin/CheckInService.java#L1244)

Impact:
- There is no escalating reminder ladder for "agreement still pending and pickup is approaching".
- The system lacks enterprise-grade operational nudges before the user hits the check-in wall.

## Recommended Product Interpretation
The current problem statement contains one ambiguity:
- "check-in should not open under any circumstance if the agreements were not accepted"
- but the example also allows the host to do their phase and requires the guest to accept before proceeding.

Recommended interpretation for implementation:
- Make agreement acceptance the primary pre-pickup workflow for both parties.
- Keep host vehicle-preparation work available only under the policy decisions below, and never surface it as the main CTA while that actor still owes agreement acceptance.
- Never allow guest acknowledgment, handshake completion, or trip start until both parties have accepted.
- On deadline expiry, resolve settlement based on the missing accepting party, not by generic no-show alone.

## Policy Decisions For Implementation
These decisions are now explicit so the implementing agent does not invent hidden product rules.

- Existing feature flag:
  - Reuse `FeatureFlags.isRentalAgreementCheckinEnforced()` as the global kill switch for the new earlier enforcement as well as the existing handshake/trip-start defense.
  - Do not introduce a second overlapping kill switch unless there is a clear rollout need.
- Agreement terminal states:
  - Reuse existing `EXPIRED` and `VOIDED` agreement enum values.
  - Add new terminal enum values only if truly necessary for code clarity; prefer modeling breach ownership with dedicated fields such as `expired_due_to_actor` and `expired_reason`.
- Acceptance deadline:
  - Add `acceptance_deadline_at` as a persisted field.
  - Initial policy: hard settlement deadline is `booking.startTime + app.checkin.no-show-minutes-after-trip-start`.
  - This keeps agreement settlement aligned with the platform's existing check-in expiry semantics while still making agreement acceptance the primary UX before trip start.
  - Make the deadline derivation configurable so product can move it earlier later without schema redesign.
- Renter breach settlement:
  - Add a dedicated agreement-breach settlement path, not a generic no-show path.
  - Introduce a new cancellation/settlement reason for renter agreement breach.
  - Initial policy: configurable penalty percentage of booking total, default `20%`.
  - Settlement shape: `penaltyAmount = 20% of booking total`, `refundToGuest = total - penaltyAmount`, `payoutToHost = penaltyAmount`, deposit released in full if only an authorization hold exists.
- Legacy booking definition:
  - A legacy booking is a booking for which `GET /api/bookings/{id}/agreement` still returns `404` after the backend lazy-generation path has run.
  - In practice this means pre-agreement-era bookings or bookings explicitly excluded from generation.
  - Frontend must distinguish this case from transient fetch failure.
- Host vehicle-preparation while agreement is pending:
  - If the host has **not** accepted the agreement, host vehicle-preparation stays blocked and the primary CTA is agreement acceptance.
  - If the host **has** accepted but the renter has not, host vehicle-preparation may remain available.
  - Guest-side check-in actions, handshake, and trip start remain blocked until both parties have accepted.
- Next Flyway migration version:
  - The repo already contains migrations through `V99`.
  - Start new database work at `V100`.

## Target Architecture

### Backend
- Extend agreement workflow to include deadline-aware and breach-aware fields, for example:
  - `acceptance_deadline_at`
  - `required_next_actor`
  - `expired_due_to_actor`
  - `expired_reason`
  - `settlement_policy_applied`
  - `settlement_record_id`
- Keep immutable evidence fields, but add explicit operational lifecycle around them.
- Introduce a dedicated agreement-resolution service that:
  - decides whether a booking is `AGREEMENT_PENDING_OWNER`, `AGREEMENT_PENDING_RENTER`, `AGREEMENT_COMPLETE`, `AGREEMENT_EXPIRED_OWNER_BREACH`, `AGREEMENT_EXPIRED_RENTER_BREACH`
  - calculates settlement outcome when the deadline passes
  - records audit events and notifications
- Add agreement summary fields to booking list/detail DTOs so frontend can render the right CTA without extra fetch chains.
- Treat agreement expiry as a first-class workflow branch before no-show logic, not as an incidental side effect of check-in status.

### Frontend
- Put agreement status and CTA directly on:
  - guest booking history cards
  - owner bookings cards
  - booking dialogs
  - full booking detail page
  - pre-check-in landing section
- Replace "go to check-in" as the dominant CTA with role-aware agreement CTA when the current actor still owes acceptance.
- Add a pre-check-in route guard or wizard landing state so deep-linking to `/bookings/:id/check-in` cannot bypass the agreement-required UX.
- Make agreement reminders visually stronger than standard check-in messaging.

### Operations And Policy
- Agreement reminder cadence should escalate as trip start approaches.
- Deadline expiry outcomes must be deterministic:
  - owner failed to accept by deadline -> auto-cancel + full refund to guest
  - renter failed to accept by deadline -> apply product-defined fee/penalty and release or settle remaining amounts per policy
- Admin/audit trail must distinguish:
  - agreement breach
  - host operational no-show
  - guest operational no-show
  - handshake timeout after both accepted

## Execution Prompt For Another AI Code Agent

You are implementing a production-grade rental agreement workflow upgrade in the Rentoza monorepo.

### Objective
Make rental agreement acceptance the primary pre-check-in workflow for both renter and owner, with deterministic backend enforcement, explicit deadline/settlement handling, and much more obvious frontend access. The final system must support:
- agreement visibility and acceptance from the main booking surfaces, not only from deep booking details
- role-aware CTA precedence: if the current actor still owes agreement acceptance, that is the primary action
- no guest acknowledgment, handshake, or trip start until both parties have accepted
- policy-driven expiry outcomes:
  - if the owner fails to accept by deadline, the guest gets a full refund
  - if the renter fails to accept by deadline, apply the platform's defined penalty/fee path
- enterprise-grade auditability and test coverage

### Verified Current State
- Full booking detail already renders an agreement card and blocks its own `canCheckIn()`.
- Backend already blocks trip start at handshake completion if agreement is not fully accepted.
- The existing global feature flag is `isRentalAgreementCheckinEnforced()` and should be reused for the expanded enforcement path.
- Main booking lists and dialogs still do not expose agreement state or CTA and still surface direct check-in actions.
- Handshake currently treats any agreement fetch failure as "legacy booking" and can hide the gate.
- The current agreement model has evidence fields, but not deadline/breach/settlement lifecycle.

### Non-Negotiable Requirements
- Do not remove or weaken existing immutable agreement evidence.
- Do not rely on frontend-only gating.
- Do not overload generic no-show statuses to represent agreement-breach outcomes without an explicit agreement resolution model.
- Keep backward compatibility for legacy bookings where needed, but make the fallback explicit and observable.
- Preserve or improve existing booking/check-in tests and add new tests for the upgraded flow.
- Reuse existing `EXPIRED` and `VOIDED` agreement states where practical instead of duplicating terminal meaning.

### Implementation Tasks

1. Introduce a first-class agreement workflow model on the backend.
- Add the fields and enum/state support needed to represent:
  - pending both / pending owner / pending renter / fully accepted
  - deadline reached
  - which actor caused expiry
  - which settlement outcome was applied
- If a new table or columns are needed, add Flyway migrations.
- Start migration numbering at `V100`.
- Keep the existing agreement evidence immutable once written.
- Reuse the existing agreement terminal states where possible and add detailed breach metadata in fields before adding more enums.

2. Add agreement summary to booking read models.
- Extend the DTOs used by:
  - `GET /api/bookings/me`
  - owner bookings endpoints
  - booking detail endpoints
- Include enough information for the frontend to render:
  - overall agreement status
  - owner accepted?
  - renter accepted?
  - whether current actor still needs to accept
  - deadline and urgency metadata
  - recommended primary CTA

3. Rework frontend booking surfaces so agreement is obvious and primary.
- Update guest booking history cards.
- Update owner booking cards.
- Update guest and owner booking dialogs.
- Update full booking detail page.
- Extend the existing booking-detail agreement-aware `canCheckIn()` behavior instead of duplicating or fighting it.
- Add a reusable agreement status/CTA component if that keeps the code cleaner.
- When current actor still owes acceptance, the primary CTA should be something like "Accept rental agreement", not "Check in".

4. Add route-level and wizard-level gating.
- Deep-linking into `/bookings/:id/check-in` must not let users skip agreement-required UX.
- Add a clear pre-check-in landing or guard state that:
  - loads agreement summary reliably
  - distinguishes `404 legacy booking` from transient fetch failure
  - blocks forward progress if agreement is still pending
  - surfaces actionable copy and retry behavior

5. Fix error contract consistency.
- Standardize backend agreement errors to the existing structured API error shape used elsewhere.
- Update frontend agreement consumers to read the shared helper rather than `err.error?.message` ad hoc.
- Ensure users see specific server reasons for agreement-blocked actions.

6. Implement deadline and settlement behavior.
- Add a scheduler/service that detects agreement deadline expiry before pickup.
- Apply deterministic outcome:
  - owner breach -> auto-cancel booking + full refund to guest
  - renter breach -> apply a dedicated agreement-breach settlement path using a configurable penalty percentage, default `20%` of booking total, refunded remainder to guest, and penalty payout to host
- Record explicit audit events and admin-facing metadata.
- Do not conflate this with stale handshake timeout once both sides had already accepted.

7. Preserve and clarify the remaining check-in logic.
- Existing handshake/trip-start enforcement should remain, but should become a final defense rather than the first real gate.
- Reuse `isRentalAgreementCheckinEnforced()` as the kill switch for the earlier route/UI/service enforcement too.
- Fix the handshake agreement fetch path so transient failures do not silently disable the gate.

8. Improve notifications/reminders.
- Add reminder/escalation logic for pending agreements as pickup approaches.
- Make notifications role-aware and include the correct next action.
- Respect the agreed host-prep policy:
  - host prep blocked until host accepts
  - host prep allowed after host acceptance even if renter still owes acceptance
  - guest flow, handshake, and trip start blocked until both accept

### Files To Inspect First
- `apps/backend/src/main/java/org/example/rentoza/booking/RentalAgreementService.java`
- `apps/backend/src/main/java/org/example/rentoza/booking/BookingController.java`
- `apps/backend/src/main/java/org/example/rentoza/booking/checkin/CheckInService.java`
- `apps/backend/src/main/java/org/example/rentoza/booking/checkin/CheckInScheduler.java`
- `apps/backend/src/main/java/org/example/rentoza/booking/dto/UserBookingResponseDTO.java`
- `apps/backend/src/main/java/org/example/rentoza/booking/dto/BookingResponseDTO.java`
- `apps/frontend/src/app/features/bookings/pages/booking-history/booking-history.component.ts`
- `apps/frontend/src/app/features/owner/pages/bookings/owner-bookings.component.ts`
- `apps/frontend/src/app/features/bookings/pages/booking-detail/booking-detail.component.ts`
- `apps/frontend/src/app/features/bookings/check-in/check-in-wizard.component.ts`
- `apps/frontend/src/app/features/bookings/check-in/handshake.component.ts`
- `apps/frontend/src/app/core/utils/api-error.utils.ts`

### Testing Requirements
- Add backend tests for:
  - agreement summary DTO/read model behavior
  - owner breach expiry -> full refund
  - renter breach expiry -> fee/penalty path
  - route/service behavior for partially accepted agreements
  - legacy booking fallback behavior
- Add frontend tests for:
  - booking history CTA precedence
  - owner bookings CTA precedence
  - check-in route guard / pre-check-in landing state
  - transient agreement fetch failure handling
  - acceptance success and failure states

### Delivery Requirements
- Implement the code, do not stop at analysis.
- Keep the diff cohesive and production-oriented.
- Run the most relevant backend and frontend tests.
- At the end, summarize:
  - what changed
  - migrations added
  - policy assumptions made
  - any remaining open decisions

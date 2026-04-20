# Admin Frontend Improvements — Implementation Report

**Date**: 2026-03-03
**Scope**: 15 items across 4 groups (Quick Wins, Medium Effort, Larger Investments, Document Verification UX)
**Commits**: 15 atomic commits (`2102660..7c9fc75`)
**Files changed**: 38 source files (3,156 insertions, 1,088 deletions)
**Build status**: All 15 commits build cleanly (only pre-existing CommonJS warnings from `sockjs-client` and `piexifjs`)

---

## Execution Order & Commit Log

| # | Item | Type | Commit | Description |
|---|------|------|--------|-------------|
| 1 | QW-1 | feat | `2102660` | Replace all `confirm()`/`prompt()` with `ConfirmDialogComponent` |
| 2 | QW-2 | refactor | `f05753a` | Standardize notifications via `AdminNotificationService` |
| 3 | QW-3 | feat | `34ad0b2` | Make dashboard KPI cards clickable with `routerLink` |
| 4 | QW-4 | feat | `cf0cfb6` | Render dispute evidence photos as thumbnail grid |
| 5 | LI-3 | refactor | `a6e6860` | Extract inline DTOs to dedicated `admin.models.ts` |
| 6 | ME-1 | feat | `3bc6d1a` | Add `TimeRangeSelectorComponent` for dashboard charts |
| 7 | ME-5 | feat | `9ff147f` | Add advanced filtering for car list |
| 8 | ME-2 | feat | `6f36371` | Add bulk ban/unban actions to user list |
| 9 | ME-3 | feat | `e181fcd` | Split-view layout for dispute detail page |
| 10 | ME-4 | feat | `f90abb1` | Add resolution templates for dispute resolution |
| 11 | ME-6 | feat | `04c195a` | Add payout trend chart to financial dashboard |
| 12 | DV-1 | feat | `99c5d00` | Add document expiry dashboard for car documents |
| 13 | DV-2 | feat | `c7d2b87` | Add side-by-side photo comparison to car review |
| 14 | LI-1 | feat | `4cde223` | Add global multi-entity search with autocomplete |
| 15 | LI-2 | feat | `7c9fc75` | Add keyboard shortcuts for admin navigation |

---

## Detailed Implementation Notes

### QW-1: Replace all `confirm()`/`prompt()` with MatDialog

**Files changed**:
- `shared/dialogs/confirm-dialog/confirm-dialog.component.ts` — **CREATED**
- `bookings/booking-detail/booking-detail.component.ts` — modified
- `bookings/booking-list/booking-list.component.ts` — modified
- `users/user-detail/user-detail.component.ts` — modified
- `cars/dialogs/car-approval-dialog.component.ts` — modified
- `renter-verifications/renter-verification-detail/renter-verification-detail.component.ts` — modified

**What was done**:
- Created a reusable `ConfirmDialogComponent` (standalone, inline template) with:
  - Configurable title, message, confirm text, button color
  - Optional reason textarea with `requireReason`, `reasonLabel`, `reasonMinLength`
  - Returns `true` or the entered reason string on confirm, `undefined` on cancel
- Replaced all native `confirm()` calls across 5 components with `MatDialog.open(ConfirmDialogComponent, ...)`
- Replaced all native `prompt()` calls (ban reason, rejection reason) with the `requireReason` variant

**Potential review items**:
- Verify that all `confirm()`/`prompt()` usages across the admin module were caught (search for `window.confirm`, `confirm(`, `prompt(` in the admin feature directory)
- The dialog uses `@if` (signal-based control flow) — consistent with the component being new

### QW-2: Standardize Notifications via AdminNotificationService

**Files changed**:
- `core/services/admin-notification.service.ts` — modified (added `showWarning` method)
- `bookings/booking-detail/booking-detail.component.ts` — modified
- `bookings/booking-list/booking-list.component.ts` — modified
- `users/user-detail/user-detail.component.ts` — modified
- `users/user-list/user-list.component.ts` — modified
- `renter-verifications/renter-verification-detail/renter-verification-detail.component.ts` — modified

**What was done**:
- Added `showWarning()` method to `AdminNotificationService` (previously only had `showSuccess`, `showError`, `showInfo`)
- Replaced all direct `MatSnackBar` injections in admin components with `AdminNotificationService`
- Replaced `console.error`-only error handling with proper `showError()` user-facing notifications

**Potential review items**:
- Verify no admin components still inject `MatSnackBar` directly (search for `MatSnackBar` imports in admin feature)
- Check that `showWarning` styling is correct (uses `warn-snackbar` CSS class)

### QW-3: Make Dashboard KPI Cards Clickable

**Files changed**:
- `shared/components/kpi-card/kpi-card.component.ts` — modified
- `shared/components/kpi-card/kpi-card.component.scss` — modified
- `dashboard/dashboard.component.html` — modified

**What was done**:
- Added `routerLink` input to `KpiCardComponent` (`@Input() routerLink: string | null = null`)
- Wrapped card content in `<a [routerLink]>` when link is provided, plain `<div>` otherwise
- Added hover styles (subtle scale, shadow) for clickable cards
- Connected KPI cards to relevant pages: Pending Approvals → `/admin/cars`, Open Disputes → `/admin/disputes`, Suspended Users → `/admin/users`

**Potential review items**:
- The `routerLink` attribute approach means the entire card is a link — verify this is accessible (has proper semantics)
- The `kpi-card` uses `@if`/`@else` for the clickable wrapper

### QW-4: Render Dispute Evidence Photos

**Files changed**:
- `disputes/dispute-detail/dispute-detail.component.html` — modified
- `disputes/dispute-detail/dispute-detail.component.ts` — modified

**What was done**:
- Added `parsePhotoIds()` method to split comma-separated photo ID strings into arrays
- Added `getPhotoUrl()` method to construct photo URLs from booking ID + phase + photo ID
- Added `openEvidenceGallery()` method that opens `PhotoGalleryDialogComponent` with grouped photos (check-in, check-out, additional evidence)
- Rendered photo thumbnails as `<img>` grids in the evidence section of the dispute detail page

**Potential review items**:
- Photo URLs are constructed as `/api/bookings/{bookingId}/{phase}/photos/{photoId}` — verify this matches the backend API
- The `PhotoGalleryDialogComponent` is imported from the shared dialogs directory

### LI-3: Extract Inline DTOs from admin-api.service.ts

**Files changed**:
- `core/models/admin.models.ts` — **CREATED** (402 lines)
- `core/services/admin-api.service.ts` — modified (596 lines removed, replaced with imports)

**What was done**:
- Created `admin.models.ts` containing all 30+ DTO interfaces organized by domain:
  - Dashboard: `DashboardKpiDto`, `RecentBookingDto`
  - User Management: `AdminUserDto`, `AdminUserDetailDto`, `OwnerVerificationRejectRequest`, `BanUserRequest`
  - Car Management: `AdminCarDto`, `CarApprovalRequest`, `AdminCarReviewDetailDto`, `DocumentReviewDto`, `ApprovalStateDto`, `DocumentVerificationRequestDto`, `ExpiringDocumentDto`
  - Disputes: `AdminDisputeListDto`, `AdminDisputeDetailDto`, `DisputeResolutionRequest`, `EscalateDisputeRequest`
  - Financial: `PayoutQueueDto`, `EscrowBalanceDto`, `BatchPayoutRequest`, `BatchPayoutResult`, `PayoutFailure`
  - Analytics: `RevenueTrendDto`, `RevenueDataPoint`, `CohortAnalysisDto`, `RetentionMetrics`, `TopPerformersDto`, `TopHost`, `TopCar`
  - Audit: `AdminAuditLogDto`, `AuditLogSearchParams`
  - Bookings: `AdminBookingDto`, `ForceCompleteBookingRequest`
  - Flagged Messages: `FlaggedMessageDto`, `FlaggedMessagePage`
  - Settings: `AdminSettings`
- `admin-api.service.ts` now uses `export type { ... } from '../models/admin.models'` for backward compatibility
- Also has `import type { ... }` for internal use within the service

**Potential review items**:
- The `export type` re-export block in `admin-api.service.ts` is needed because TypeScript `isolatedModules` cannot use `export { ... }` for type-only re-exports
- Verify all consuming files still compile (they do — confirmed by build)
- Some types may still be defined inline in `admin-api.service.ts` if they weren't part of the original extraction (e.g., `PaginatedResponse`, `HateoasPage` remain in their own model files)

### ME-1: Time Range Selector for Dashboard Charts

**Files changed**:
- `shared/components/time-range-selector/time-range-selector.component.ts` — **CREATED**
- `dashboard/dashboard-charts/dashboard-charts.component.ts` — modified
- `dashboard/dashboard-charts/dashboard-charts.component.scss` — modified

**What was done**:
- Created standalone `TimeRangeSelectorComponent` with `MatButtonToggleGroup`
- Options: 7d, 30d, 90d, 1y, All
- Uses Angular `output()` function to emit selected range
- Integrated into `DashboardChartsComponent` — range selection triggers chart data reload
- Chart data fetching passes the selected days parameter to the API

**Potential review items**:
- Uses Angular `output()` (new API) — verify Angular version supports this (Angular 19+ does)
- The `AdminChartsService` methods need to accept a `days` parameter — verify backend endpoints support this filter

### ME-5: Advanced Filtering for Car List

**Files changed**:
- `cars/car-list/car-list.component.ts` — modified (136+ lines changed)
- `cars/car-list/car-list.component.scss` — modified

**What was done**:
- Added filter bar with:
  - Status filter (`MatSelect`): All, Pending, Approved, Rejected, Active, Inactive
  - Listed After date filter (`<input type="date">`)
- Filters are passed to `AdminApiService.getCars()` which already supported `status` and `listedAfter` parameters
- Clear filters button resets both filters
- Filter state persists across pagination

**Potential review items**:
- Verify that the backend `GET /admin/cars` endpoint actually supports `status` and `listedAfter` query parameters
- The component uses `*ngIf`/`*ngFor` (Observable-based) — consistent with the existing component pattern

### ME-2: Bulk Actions for User List

**Files changed**:
- `users/user-list/user-list.component.ts` — modified (109+ lines changed)
- `users/user-list/user-list.component.html` — modified
- `users/user-list/user-list.component.scss` — modified

**What was done**:
- Added `SelectionModel` from `@angular/cdk/collections` for multi-row selection
- Added checkbox column to the users table (select all / select individual)
- Added bulk action bar that appears when rows are selected: "Ban Selected", "Unban Selected"
- Both bulk actions use `ConfirmDialogComponent` with `requireReason`
- Bulk operations iterate through selected users and call individual API endpoints (no batch API endpoint)
- Selection clears on page change and after bulk operations

**Potential review items**:
- Bulk ban/unban calls individual API endpoints in a loop, not a batch endpoint — this could be slow for many selections and lacks transactional guarantees
- `forkJoin` is used to wait for all operations — if one fails, the overall operation may still partially succeed. Error handling shows a generic message
- The table becomes selectable for all users, including already-banned ones — the "Ban Selected" action should handle already-banned users gracefully

### ME-3: Dispute Split-View Layout

**Files changed**:
- `disputes/dispute-detail/dispute-detail.component.html` — significantly restructured (532 lines changed)
- `disputes/dispute-detail/dispute-detail.component.scss` — modified (115+ lines changed)

**What was done**:
- Reorganized the dispute detail from single-column to two-column split-view:
  - **Left panel** (`.context-panel`, scrollable): Dispute overview card, claim details, guest response, evidence photos, admin review history
  - **Right panel** (`.resolution-panel`, sticky at top: 24px): Action buttons, resolution form, escalation form, resolution summary (for already-resolved disputes)
- Added `.resolution-summary-card` for displaying past resolution details
- Responsive: collapses to single column at 1024px breakpoint
- Grid layout: `grid-template-columns: 1fr 400px`

**Potential review items**:
- Sticky positioning (`position: sticky; top: 24px`) on the resolution panel — verify it works correctly within the `mat-sidenav-content` scrolling container
- Large template restructuring — verify no sections were accidentally dropped during the rewrite
- The component uses `@if`/`@for` (signal-based) — consistent with the component being signals-based

### ME-4: Resolution Templates for Disputes

**Files changed**:
- `disputes/dispute-detail/dispute-detail.component.ts` — modified (added `resolutionTemplates` array and `applyTemplate` method)
- `disputes/dispute-detail/dispute-detail.component.html` — modified (added template selector)

**What was done**:
- Added 4 resolution text templates as a `readonly` array:
  1. "Full refund — guest fault not established"
  2. "Partial deduction — minor damage confirmed"
  3. "Full deduction — significant damage confirmed"
  4. "Dispute rejected — pre-existing damage"
- Each template has placeholder variables: `{guestName}`, `{hostName}`, `{amount}`
- `applyTemplate()` replaces placeholders with actual values from the dispute and form
- Template selector uses `<mat-select>` with `(selectionChange)` event
- Selected template populates the `notes` form control

**Potential review items**:
- The spec mentioned `{remaining}` as a variable but `AdminDisputeDetailDto` has no `depositAmount` field, so this was omitted — verify this is acceptable
- Template text is hardcoded in the component — consider whether these should be configurable or internationalized
- The `{amount}` variable uses `approvedAmount` from the form, which may be `null` initially — handled with `'___'` fallback

### ME-6: Payout Trend Charts in Financial Dashboard

**Files changed**:
- `shared/services/admin-charts.service.ts` — modified (added `PayoutHistoryData` interface and `getPayoutHistory()`)
- `financial/financial-dashboard/financial-dashboard.component.ts` — modified (92+ lines added)
- `financial/financial-dashboard/financial-dashboard.component.html` — modified
- `financial/financial-dashboard/financial-dashboard.component.scss` — modified

**What was done**:
- Added `PayoutHistoryData` interface (`{ labels: string[], amounts: number[] }`)
- Added `getPayoutHistory(days)` method to `AdminChartsService` calling `GET /admin/charts/payout-history?days=N`
- Added `ng2-charts` integration to the financial dashboard:
  - `provideCharts(withDefaultRegisterables())` in component `providers` (scoped, not global)
  - `BaseChartDirective` in imports
  - Line chart with green fill, rounded tension, point styling
- Chart has loading spinner and error states
- Placed between the escrow balance cards and the payout queue table

**Potential review items**:
- `provideCharts(withDefaultRegisterables())` is scoped per component for lazy loading — same pattern as dashboard-charts. Verify this doesn't cause issues with multiple chart.js registrations
- `getPayoutHistory()` uses `catchError(() => of({ labels: [], amounts: [] }))` — silent error swallowing. The component does show an error UI via `payoutChartError` signal, but only on the component-level error handler
- Verify the backend endpoint `GET /admin/charts/payout-history` exists and returns the expected shape

### DV-1: Document Expiry Dashboard

**Files changed**:
- `core/models/admin.models.ts` — modified (added `ExpiringDocumentDto`)
- `core/services/admin-api.service.ts` — modified (added `getExpiringDocuments()`, added to export/import blocks)
- `cars/document-expiry/document-expiry.component.ts` — **CREATED**
- `cars/document-expiry/document-expiry.component.html` — **CREATED**
- `cars/document-expiry/document-expiry.component.scss` — **CREATED**
- `admin.routes.ts` — modified (added `expiring-documents` route)

**What was done**:
- Created `ExpiringDocumentDto` with fields: `carId`, `carBrand`, `carModel`, `carYear`, `ownerName`, `ownerEmail`, `documentType`, `expiryDate`, `daysRemaining`
- Created `getExpiringDocuments(days)` API method calling `GET /admin/cars/expiring-documents?days=N`
- Created standalone signals-based `DocumentExpiryComponent` with:
  - 3 summary cards: Critical (<7 days, red), Warning (<30 days, amber), OK (>30 days, green) — clickable to filter
  - Filter bar: time window (30/60/90 days), document type, urgency level
  - `MatTable` with columns: car, owner, document type, expiry date, days remaining, actions
  - Color-coded urgency chips
  - Empty state
  - Loading spinner
- Route added as `cars/expiring-documents` (before `:id` wildcard to prevent matching)

**Potential review items**:
- Verify the backend endpoint `GET /admin/cars/expiring-documents` exists
- The route is correctly placed before `:id` in the cars children — Angular would otherwise match "expiring-documents" as a car ID
- No pagination implemented — if there are many expiring documents, this could be a performance issue. The current approach loads all documents and filters client-side
- The "Notify Owner" button in the actions column has no implementation (`button` with no click handler) — this is a placeholder

### DV-2: Improved Car Review Photo Comparison

**Files changed**:
- `cars/car-review/car-review.component.ts` — modified (267+ lines changed, inline template)
- `cars/car-review/car-review.component.scss` — modified (120 lines added)

**What was done**:
- Added `PhotoGalleryDialogComponent` and `PhotoGroup` imports
- Added comparison mode properties: `comparisonMode`, `leftPhoto`, `rightPhoto`
- Added methods:
  - `toggleComparisonMode()` — activates comparison and auto-selects first two photos
  - `setLeftPhoto(url)` / `setRightPhoto(url)` — assign photos to left/right slots
  - `openInGallery(url)` — opens `PhotoGalleryDialogComponent` with all car photos
  - `getComparisonPhotos()` — returns the full photo array for the picker
- Added template section: toggle button, side-by-side `.comparison-grid` (2 columns), thumbnail `.comparison-picker` with left/right assignment mini-buttons
- Comparison photos show with "zoom-in" cursor and open in gallery on click
- Placeholder shown when no photo selected for a slot

**Potential review items**:
- The car-review component has a very large inline template (866+ lines before this change) — consider whether it should be extracted to a separate `.html` file
- Left/right photo assignment uses small icon buttons below each thumbnail — UX may be non-obvious without a tooltip (buttons use `matTooltip`)
- The `comparison-grid` uses a fixed 2-column grid — no responsive handling for very small screens (though car review is an admin-only desktop feature)

### LI-1: Global Multi-Entity Search

**Files changed**:
- `shared/services/admin-search.service.ts` — **CREATED** (84 lines)
- `admin-layout/admin-layout.component.ts` — modified (121+ lines changed)
- `admin-layout/admin-layout.component.scss` — modified (75+ lines added)

**What was done**:
- Created `AdminSearchService` with:
  - `SearchResultItem` interface: `type`, `id`, `title`, `subtitle`, `icon`, `route`
  - `SearchResults` interface: `users[]`, `bookings[]`, `cars[]`, `total`
  - `search(query)` method using `forkJoin` to query users, bookings, and cars simultaneously (5 results each, with `catchError` per endpoint)
  - Maps API DTOs to `SearchResultItem` with appropriate icons and route arrays
- Updated admin-layout toolbar:
  - Added `MatAutocompleteModule`, `MatFormFieldModule`, `MatInputModule`, `MatProgressSpinnerModule` imports
  - Replaced simple enter-to-navigate search with `MatAutocomplete` dropdown
  - Added `searchSubject$` with 300ms debounce and `distinctUntilChanged`
  - Results grouped by entity type using `<mat-optgroup>` (Users, Bookings, Cars)
  - Each result shows icon, title, and subtitle
  - "No results" placeholder when search returns empty
  - `navigateToResult()` routes to the entity's detail page
  - `displayWith` returns empty string to prevent `[object Object]` display after selection
  - Loading spinner shown during search
  - Search input has `admin-search-input` class for keyboard shortcut targeting
- Added SCSS for autocomplete dropdown: optgroup labels, result layout, no-results state

**Potential review items**:
- The search fans out 3 concurrent HTTP requests on every debounced keystroke — verify request cancellation works correctly (the `switchMap` should cancel previous in-flight requests)
- Minimum query length is 2 characters — this is checked both in the service and the template
- `displayWith` returns empty string which clears the input after selection — this is intentional since we collapse the search bar, but verify it doesn't cause flicker
- Search results are limited to 5 per entity type (15 max total) — no "see all results" option
- The `searchSubject$` subscription doesn't handle the case where `switchMap` returns `[]` (from the guard clause) — this results in the subscriber never firing for short queries, which is correct but `searchResults` stays stale until the next valid query

### LI-2: Keyboard Shortcuts

**Files changed**:
- `shared/services/admin-keyboard.service.ts` — **CREATED** (136 lines)
- `shared/dialogs/shortcut-help-dialog/shortcut-help-dialog.component.ts` — **CREATED** (104 lines)
- `admin-layout/admin-layout.component.ts` — modified (added HostListener, service injection)

**What was done**:
- Created `AdminKeyboardService` with:
  - Two-key sequence detection (GitHub-style, 800ms timeout)
  - Navigation shortcuts: `g→u` (users), `g→b` (bookings), `g→c` (cars), `g→f` (financial), `g→d` (disputes), `g→v` (verifications), `g→h` (dashboard)
  - Action shortcuts: `/` (focus search), `?` (show help)
  - Input element detection — shortcuts ignored when typing in `<input>`, `<textarea>`, `<select>`, or `contentEditable` elements
  - Modifier key filtering — ignores Ctrl, Meta, Alt (allows Shift for `?`)
  - `enable()`/`disable()` lifecycle methods
  - The `/` shortcut handles both cases: search already expanded (focus input) and search collapsed (click expand button, then focus after 100ms delay)
- Created `ShortcutHelpDialogComponent` (standalone, inline template):
  - Groups shortcuts by category (Navigation, Actions)
  - Renders keyboard keys as styled `<kbd>` elements
  - `parseKeys()` splits `'g → u'` display format into individual keys
- Registered in `AdminLayoutComponent`:
  - `@HostListener('document:keydown', ['$event'])` delegates to `keyboardService.handleKeydown()`
  - `ngOnInit()` calls `keyboardService.enable()`
  - `ngOnDestroy()` calls `keyboardService.disable()`

**Potential review items**:
- The `/` shortcut uses a 100ms `setTimeout` to wait for the search input to render after clicking the expand button — this is fragile and may fail on slow devices. Consider using `ViewChild` with a callback or `requestAnimationFrame`
- The `?` shortcut requires Shift+/ on US keyboards but is detected via `event.key === '?'` which should work cross-layout. Verify on non-US keyboard layouts
- `pendingTimeout` uses `any` type for the timeout handle — could use `ReturnType<typeof setTimeout>`
- `NgZone.run()` is used for router navigation and dialog opening to ensure Angular change detection runs — verify this is necessary (it should be, since the keydown handler fires outside Angular zone)
- The service is `providedIn: 'root'` but is only meaningful within the admin layout — if a user navigates away from admin, the shortcuts remain registered (though `disable()` is called in `ngOnDestroy`)

---

## New Files Created (8 total)

| File | Lines | Purpose |
|------|-------|---------|
| `core/models/admin.models.ts` | 402 | All admin DTO interfaces |
| `shared/dialogs/confirm-dialog/confirm-dialog.component.ts` | 61 | Reusable confirmation dialog |
| `shared/dialogs/shortcut-help-dialog/shortcut-help-dialog.component.ts` | 104 | Keyboard shortcut help overlay |
| `shared/components/time-range-selector/time-range-selector.component.ts` | 64 | Time range toggle (7d/30d/90d/1y/All) |
| `shared/services/admin-search.service.ts` | 84 | Multi-entity search fan-out |
| `shared/services/admin-keyboard.service.ts` | 136 | Keyboard shortcut handler |
| `cars/document-expiry/document-expiry.component.ts` | 160 | Document expiry dashboard |
| `cars/document-expiry/document-expiry.component.html` | 167 | Document expiry template |
| `cars/document-expiry/document-expiry.component.scss` | 197 | Document expiry styles |

All paths relative to `apps/frontend/src/app/features/admin/`

---

## Architectural Patterns Used

1. **Observable-based components** (`*ngIf`/`*ngFor`): admin-layout, user-list, booking-list, car-list, dashboard-charts — kept consistent with existing patterns
2. **Signal-based components** (`@if`/`@for`, `signal()`, `computed()`): dispute-detail, document-expiry, financial-dashboard, confirm-dialog — used for newer components
3. **Standalone components**: all new components are standalone with explicit imports
4. **Serbian locale (sr-RS)**: all date/currency formatting uses `sr-RS` locale and `RSD` currency
5. **`AdminNotificationService`**: all user-facing notifications route through this service (no direct `MatSnackBar`)
6. **`ConfirmDialogComponent`**: all destructive actions use this dialog (no native `confirm()`/`prompt()`)
7. **`PhotoGalleryDialogComponent`**: reused for dispute evidence and car review photo viewing
8. **`provideCharts(withDefaultRegisterables())`**: scoped per component for lazy-loaded chart.js registration
9. **HATEOAS pagination normalization**: API responses go through `normalizePage()` before reaching components
10. **TypeScript `isolatedModules`**: DTO re-exports use `export type { ... }` syntax

---

## Backend Dependencies (Endpoints That Must Exist)

These frontend features call backend endpoints. Verify each exists and returns the expected shape:

| Endpoint | Used By | Expected Response |
|----------|---------|-------------------|
| `GET /admin/cars/expiring-documents?days=N` | DV-1 | `ExpiringDocumentDto[]` |
| `GET /admin/charts/payout-history?days=N` | ME-6 | `{ labels: string[], amounts: number[] }` |
| `GET /admin/users?search=...&page=...&size=...` | LI-1 | `HateoasPage<AdminUserDto>` |
| `GET /admin/bookings?search=...&page=...&size=...` | LI-1 | Paginated `AdminBookingDto` |
| `GET /admin/cars?search=...&status=...&listedAfter=...` | LI-1, ME-5 | `HateoasPage<AdminCarDto>` |
| `POST /admin/users/{id}/ban` | ME-2 (bulk) | 200 OK |
| `POST /admin/users/{id}/unban` | ME-2 (bulk) | 200 OK |
| `GET /api/bookings/{id}/{phase}/photos/{photoId}` | QW-4 | Image binary |
| `GET /admin/charts/revenue-trend?days=N` | ME-1 | `RevenueTrendDto` |

---

## Known Limitations & Gaps for Reviewer

1. **ME-2 (Bulk Actions)**: No batch API endpoint — iterates with individual calls in `forkJoin`. Partial failures are possible. No progress indicator during bulk operations.

2. **DV-1 (Document Expiry)**: No pagination — loads all expiring documents at once. "Notify Owner" button is a placeholder with no implementation. Client-side filtering only.

3. **LI-1 (Global Search)**: Maximum 15 results (5 per entity). No "view all results" link. Stale `searchResults` when query drops below 2 characters (cleared on collapse, but not on backspace to 1 char).

4. **LI-2 (Keyboard Shortcuts)**: The `/` shortcut uses a 100ms `setTimeout` to wait for DOM rendering — fragile timing. Service is `providedIn: 'root'` but only active within admin layout.

5. **ME-6 (Payout Chart)**: `AdminChartsService.getPayoutHistory()` silently swallows errors with `catchError(() => of({ labels: [], amounts: [] }))` — the component shows error state separately but the service-level error is lost.

6. **DV-2 (Photo Comparison)**: The car-review component has an 866+ line inline template which is getting difficult to maintain. The comparison mode UI doesn't handle edge cases like only 1 photo being available.

7. **General**: Several components use hardcoded hex colors (e.g., `#d32f2f`, `#388e3c`, `#3b82f6`) instead of CSS variables — these won't adapt to the dark theme toggle.

8. **ME-3 (Split View)**: Sticky positioning in the resolution panel (`position: sticky; top: 24px`) may not work correctly if the parent scrolling container isn't the expected element within `mat-sidenav-content`.

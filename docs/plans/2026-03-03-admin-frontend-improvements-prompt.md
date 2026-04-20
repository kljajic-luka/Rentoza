# Admin Frontend Improvements — Implementation Prompt

> **Target**: Rentoza Admin Dashboard (Angular 19, standalone components, Angular Material)
> **Standard**: Turo/Airbnb-class admin tooling — fast, keyboard-friendly, zero confirm() dialogs
> **Prerequisite**: Backend audit remediation complete (commit 03eec00, verified 30c6e6b)

---

## CONTEXT

You are improving the admin dashboard frontend for Rentoza, a peer-to-peer car rental marketplace built with:

- **Angular 19** (standalone components, signals for new code, observables in legacy)
- **Angular Material** (MatTable, MatPaginator, MatDialog, MatSnackBar, MatSidenav, MatChips, etc.)
- **ng2-charts + chart.js** (revenue line chart, trip activity bar chart already exist)
- **RxJS** (debounceTime, switchMap, combineLatest patterns)
- **Tailwind CSS** (utility classes for layout and spacing)

### Key Files

| File | Lines | Purpose |
|------|-------|---------|
| `apps/frontend/src/app/admin/admin-api.service.ts` | ~1035 | All admin HTTP calls + 50+ inline DTOs |
| `apps/frontend/src/app/admin/dashboard/dashboard.component.ts` | ~350 | KPI cards, recent bookings, CSV export |
| `apps/frontend/src/app/admin/dashboard/dashboard.component.html` | ~250 | Dashboard template (uses `*ngIf`, `*ngFor`) |
| `apps/frontend/src/app/admin/user-list/user-list.component.ts` | ~400 | User search, filters, pagination |
| `apps/frontend/src/app/admin/user-list/user-list.component.html` | ~300 | User table template (uses `*ngIf`, `*ngFor`) |
| `apps/frontend/src/app/admin/car-list/car-list.component.ts` | ~500 | Pending/All car tabs, approve/reject |
| `apps/frontend/src/app/admin/car-review/car-review.component.ts` | ~850 | Document review workflow, photo grid |
| `apps/frontend/src/app/admin/car-review/car-review.component.html` | ~600 | Car review template |
| `apps/frontend/src/app/admin/dispute-detail/dispute-detail.component.ts` | ~450 | Dispute resolution (Signals, `@if`) |
| `apps/frontend/src/app/admin/dispute-detail/dispute-detail.component.html` | ~350 | Dispute detail template |
| `apps/frontend/src/app/admin/financial-dashboard/financial-dashboard.component.ts` | ~400 | Escrow, payouts, batch processing (Signals) |
| `apps/frontend/src/app/admin/financial-dashboard/financial-dashboard.component.html` | ~350 | Financial dashboard template |
| `apps/frontend/src/app/admin/admin-layout/admin-layout.component.ts` | ~300 | Sidenav, global search, theme toggle |
| `apps/frontend/src/app/admin/shared/admin-notification.service.ts` | ~50 | showSuccess/showError/showInfo wrappers |
| `apps/frontend/src/app/admin/shared/photo-gallery-dialog/` | Existing | Tabs, fullscreen, navigation — reuse this |

### Code Conventions

**For NEW components and modifications to Signal-based components** (`dispute-detail`, `financial-dashboard`):
- Use `signal()`, `computed()`, `effect()`
- Use `@if` / `@for` / `@else` control flow
- Use `inject()` function for DI

**For modifications to Observable-based components** (`dashboard`, `user-list`, `car-list`, `car-review`):
- Keep existing `*ngIf` / `*ngFor` patterns — do NOT migrate to signals
- Use existing Observable patterns (`BehaviorSubject`, `pipe`, `switchMap`)
- Only add signals if creating entirely new sections within these components

**All components**:
- Standalone (no NgModules)
- Use `AdminNotificationService` for all notifications (NOT direct `MatSnackBar`)
- Use `MatDialog` for all confirmations (NEVER `window.confirm()` or `window.prompt()`)

---

## GROUP 1: QUICK WINS (1-2 hours each)

### QW-1: Replace all `confirm()` / `prompt()` with MatDialog

**Problem**: Native browser dialogs block the UI thread, look unprofessional, and can't be styled.

**Locations to fix**:

1. **`car-list.component.ts` line ~428** — `confirm('Are you sure you want to reject this car?')`
2. **`car-review.component.ts` line ~730** — `confirm('Are you sure you want to approve this car?')`
3. **`car-review.component.ts` line ~753** — `prompt('Please provide a reason for rejection:')`
4. **`car-review.component.ts` line ~792** — `confirm('Are you sure you want to suspend this car?')`
5. **`car-review.component.ts` line ~822** — `confirm('Are you sure you want to unsuspend this car?')`

**Implementation**:

Create a reusable `ConfirmDialogComponent` in `apps/frontend/src/app/admin/shared/confirm-dialog/`:

```typescript
// confirm-dialog.component.ts
@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  imports: [MatDialogModule, MatButtonModule, FormsModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p>{{ data.message }}</p>
      @if (data.requireReason) {
        <mat-form-field class="w-full mt-4">
          <mat-label>{{ data.reasonLabel || 'Reason' }}</mat-label>
          <textarea matInput [(ngModel)]="reason" rows="3"
                    [required]="data.requireReason"></textarea>
        </mat-form-field>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
      <button mat-flat-button
              [color]="data.confirmColor || 'primary'"
              [disabled]="data.requireReason && !reason.trim()"
              (click)="confirm()">
        {{ data.confirmText || 'Confirm' }}
      </button>
    </mat-dialog-actions>
  `
})
export class ConfirmDialogComponent {
  data = inject<ConfirmDialogData>(MAT_DIALOG_DATA);
  private dialogRef = inject(MatDialogRef<ConfirmDialogComponent>);
  reason = '';

  confirm() {
    this.dialogRef.close(this.data.requireReason ? this.reason : true);
  }
}

export interface ConfirmDialogData {
  title: string;
  message: string;
  confirmText?: string;
  confirmColor?: 'primary' | 'accent' | 'warn';
  requireReason?: boolean;
  reasonLabel?: string;
}
```

**Replace pattern** — example for `car-review.component.ts` rejection:

BEFORE:
```typescript
const reason = prompt('Please provide a reason for rejection:');
if (!reason) return;
this.rejectCar(reason);
```

AFTER:
```typescript
const dialogRef = this.dialog.open(ConfirmDialogComponent, {
  data: {
    title: 'Reject Car',
    message: 'This car will be rejected and the host notified.',
    confirmText: 'Reject',
    confirmColor: 'warn',
    requireReason: true,
    reasonLabel: 'Rejection reason'
  }
});
dialogRef.afterClosed().subscribe(reason => {
  if (reason) this.rejectCar(reason);
});
```

### QW-2: Standardize Notification Usage

**Problem**: Some components use `AdminNotificationService`, others use `MatSnackBar` directly. This is inconsistent.

**Action**: Search all admin components for direct `MatSnackBar` usage and replace with `AdminNotificationService`. Also add a `showWarning()` method to AdminNotificationService if it doesn't exist.

**File**: `apps/frontend/src/app/admin/shared/admin-notification.service.ts`

Add:
```typescript
showWarning(message: string): void {
  this.snackBar.open(message, 'Dismiss', {
    duration: 6000,
    panelClass: ['warning-snackbar']
  });
}
```

### QW-3: Make Dashboard KPI Cards Clickable

**Problem**: Dashboard KPI cards (Total Users, Active Bookings, Revenue, etc.) are static — clicking them should navigate to the relevant section with pre-applied filters.

**File**: `apps/frontend/src/app/admin/dashboard/dashboard.component.html`

Wrap each KPI card in a clickable container:

```html
<mat-card class="cursor-pointer hover:shadow-lg transition-shadow"
          (click)="navigateToSection('users')"
          (keydown.enter)="navigateToSection('users')"
          tabindex="0"
          role="link"
          [attr.aria-label]="'View all users'">
  <!-- existing card content -->
</mat-card>
```

Add navigation method in `.ts`:
```typescript
navigateToSection(section: string) {
  const routes: Record<string, string[]> = {
    'users': ['/admin/users'],
    'bookings': ['/admin/bookings'],
    'revenue': ['/admin/financial'],
    'cars': ['/admin/cars'],
    'disputes': ['/admin/disputes']
  };
  if (routes[section]) {
    this.router.navigate(routes[section]);
  }
}
```

### QW-4: Render Dispute Evidence Photos

**Problem**: `dispute-detail.component.html` shows photo IDs as text strings instead of rendering actual images. The `PhotoGalleryDialogComponent` already exists and supports tabs, fullscreen, and navigation.

**File**: `apps/frontend/src/app/admin/dispute-detail/dispute-detail.component.html`

Find where evidence photo IDs are displayed as text and replace with clickable thumbnails:

```html
@if (dispute().evidencePhotoIds?.length) {
  <div class="grid grid-cols-4 gap-2 mt-2">
    @for (photoId of dispute().evidencePhotoIds; track photoId) {
      <img [src]="getPhotoUrl(photoId)"
           class="w-full h-24 object-cover rounded cursor-pointer hover:opacity-80 transition-opacity"
           [alt]="'Evidence photo'"
           (click)="openEvidenceGallery(photoId)"
           (keydown.enter)="openEvidenceGallery(photoId)"
           tabindex="0" />
    }
  </div>
}
```

Add to `.ts`:
```typescript
openEvidenceGallery(startPhotoId: string) {
  this.dialog.open(PhotoGalleryDialogComponent, {
    data: {
      photoIds: this.dispute().evidencePhotoIds,
      startIndex: this.dispute().evidencePhotoIds.indexOf(startPhotoId),
      title: 'Dispute Evidence'
    },
    maxWidth: '90vw',
    maxHeight: '90vh'
  });
}
```

---

## GROUP 2: MEDIUM EFFORT (3-6 hours each)

### ME-1: Time Range Selector for Dashboard Charts

**Problem**: Dashboard charts have no time range controls — they show hardcoded periods.

**Implementation**: Add a shared `TimeRangeSelectorComponent` that emits a date range.

```typescript
// time-range-selector.component.ts
@Component({
  selector: 'app-time-range-selector',
  standalone: true,
  imports: [MatButtonToggleModule],
  template: `
    <mat-button-toggle-group [value]="selectedRange()" (change)="onRangeChange($event)">
      <mat-button-toggle value="7d">7D</mat-button-toggle>
      <mat-button-toggle value="30d">30D</mat-button-toggle>
      <mat-button-toggle value="90d">90D</mat-button-toggle>
      <mat-button-toggle value="1y">1Y</mat-button-toggle>
    </mat-button-toggle-group>
  `
})
export class TimeRangeSelectorComponent {
  selectedRange = signal('30d');
  rangeChange = output<{ start: Date; end: Date }>();

  onRangeChange(event: MatButtonToggleChange) {
    this.selectedRange.set(event.value);
    const end = new Date();
    const start = new Date();
    const days = { '7d': 7, '30d': 30, '90d': 90, '1y': 365 }[event.value] ?? 30;
    start.setDate(start.getDate() - days);
    this.rangeChange.emit({ start, end });
  }
}
```

Use this in `dashboard.component.html` above each chart, and pass the range to the API calls that feed the charts.

### ME-2: Bulk Actions for User List

**Problem**: Admin can only act on one user at a time. Bulk ban/unban/verify is needed for moderation at scale.

**File**: `apps/frontend/src/app/admin/user-list/user-list.component.ts` and `.html`

**Implementation**:

1. Add `SelectionModel` (already used in `financial-dashboard`):
```typescript
import { SelectionModel } from '@angular/cdk/collections';

selection = new SelectionModel<any>(true, []);
```

2. Add checkbox column to the MatTable:
```html
<ng-container matColumnDef="select">
  <th mat-header-cell *matHeaderCell>
    <mat-checkbox (change)="$event ? toggleAllRows() : null"
                  [checked]="selection.hasValue() && isAllSelected()"
                  [indeterminate]="selection.hasValue() && !isAllSelected()">
    </mat-checkbox>
  </th>
  <td mat-cell *matCellDef="let row">
    <mat-checkbox (click)="$event.stopPropagation()"
                  (change)="$event ? selection.toggle(row) : null"
                  [checked]="selection.isSelected(row)">
    </mat-checkbox>
  </td>
</ng-container>
```

3. Add a floating action bar that appears when items are selected:
```html
<div *ngIf="selection.hasValue()" class="sticky bottom-4 mx-4 p-3 bg-white dark:bg-gray-800 shadow-lg rounded-lg flex items-center gap-3 z-10">
  <span class="text-sm font-medium">{{ selection.selected.length }} selected</span>
  <button mat-stroked-button color="warn" (click)="bulkBan()">Ban Selected</button>
  <button mat-stroked-button (click)="bulkUnban()">Unban Selected</button>
  <button mat-stroked-button color="primary" (click)="bulkVerify()">Verify Selected</button>
  <button mat-icon-button (click)="selection.clear()" matTooltip="Clear selection">
    <mat-icon>close</mat-icon>
  </button>
</div>
```

4. Bulk action methods should use `ConfirmDialogComponent` (from QW-1) and call the API in sequence, showing a progress indicator.

### ME-3: Dispute Split-View Layout

**Problem**: Dispute resolution requires context-switching between booking details, evidence, and the resolution form. A split-view layout would be far more efficient.

**File**: `apps/frontend/src/app/admin/dispute-detail/dispute-detail.component.html`

Restructure the layout into a two-column split:

```html
<div class="grid grid-cols-1 lg:grid-cols-2 gap-6 h-full">
  <!-- LEFT: Context Panel (scrollable) -->
  <div class="overflow-y-auto space-y-4">
    <mat-card>
      <mat-card-header><mat-card-title>Booking Details</mat-card-title></mat-card-header>
      <mat-card-content>
        <!-- Guest/Host info, dates, amounts, car details -->
      </mat-card-content>
    </mat-card>

    <mat-card>
      <mat-card-header><mat-card-title>Evidence</mat-card-title></mat-card-header>
      <mat-card-content>
        <!-- Photo grid, descriptions, timestamps -->
      </mat-card-content>
    </mat-card>

    <mat-card>
      <mat-card-header><mat-card-title>Timeline</mat-card-title></mat-card-header>
      <mat-card-content>
        <!-- Booking status history with timestamps -->
      </mat-card-content>
    </mat-card>
  </div>

  <!-- RIGHT: Resolution Panel (sticky) -->
  <div class="lg:sticky lg:top-0 lg:self-start space-y-4">
    <mat-card>
      <mat-card-header><mat-card-title>Resolution</mat-card-title></mat-card-header>
      <mat-card-content>
        <!-- Resolution form, amount input, template selector -->
      </mat-card-content>
    </mat-card>
  </div>
</div>
```

### ME-4: Resolution Templates for Disputes

**Problem**: Admins type resolution notes from scratch every time. Predefined templates with variables improve speed and consistency.

**Implementation**: Add a template selector above the resolution notes textarea.

```typescript
// In dispute-detail.component.ts
readonly resolutionTemplates = [
  {
    label: 'Full refund — guest fault not established',
    text: 'After reviewing the evidence, damage could not be attributed to the guest. Full deposit refund issued to {guestName}.'
  },
  {
    label: 'Partial deduction — minor damage confirmed',
    text: 'Evidence confirms minor damage during the rental period. A deduction of {amount} RSD has been applied from the deposit. Remaining {remaining} RSD refunded to {guestName}.'
  },
  {
    label: 'Full deduction — significant damage confirmed',
    text: 'Evidence clearly shows significant damage during the rental period. The full deposit of {amount} RSD has been retained and disbursed to {hostName} for repairs.'
  },
  {
    label: 'Dispute rejected — pre-existing damage',
    text: 'Check-in photos confirm the reported damage existed prior to this rental. No deduction applied. Full deposit refunded to {guestName}.'
  }
];

applyTemplate(template: { label: string; text: string }) {
  let text = template.text;
  const dispute = this.dispute();
  text = text.replace('{guestName}', dispute.guestName || 'the guest');
  text = text.replace('{hostName}', dispute.hostName || 'the host');
  text = text.replace('{amount}', this.approvedAmount()?.toString() || '___');
  text = text.replace('{remaining}',
    ((dispute.depositAmount || 0) - (this.approvedAmount() || 0)).toString());
  this.resolutionNotes.set(text);
}
```

Template in HTML:
```html
<mat-form-field class="w-full">
  <mat-label>Quick template</mat-label>
  <mat-select (selectionChange)="applyTemplate($event.value)">
    @for (t of resolutionTemplates; track t.label) {
      <mat-option [value]="t">{{ t.label }}</mat-option>
    }
  </mat-select>
</mat-form-field>
```

### ME-5: Advanced Filtering for Car List

**Problem**: Car list only has Pending/All tabs. No way to filter by make, status, verification state, date range, or owner.

**File**: `apps/frontend/src/app/admin/car-list/car-list.component.ts` and `.html`

Add a filter panel above the table:

```html
<div class="flex flex-wrap gap-3 mb-4">
  <mat-form-field class="w-48">
    <mat-label>Status</mat-label>
    <mat-select [(ngModel)]="statusFilter" (selectionChange)="applyFilters()">
      <mat-option [value]="null">All</mat-option>
      <mat-option value="PENDING">Pending</mat-option>
      <mat-option value="APPROVED">Approved</mat-option>
      <mat-option value="REJECTED">Rejected</mat-option>
      <mat-option value="SUSPENDED">Suspended</mat-option>
    </mat-select>
  </mat-form-field>

  <mat-form-field class="w-48">
    <mat-label>Search (make/model/plate)</mat-label>
    <input matInput [(ngModel)]="searchQuery" (ngModelChange)="onSearchChange($event)">
  </mat-form-field>

  <mat-form-field class="w-48">
    <mat-label>Listed after</mat-label>
    <input matInput [matDatepicker]="picker" [(ngModel)]="dateFilter" (dateChange)="applyFilters()">
    <mat-datepicker-toggle matSuffix [for]="picker"></mat-datepicker-toggle>
    <mat-datepicker #picker></mat-datepicker>
  </mat-form-field>

  <button mat-stroked-button (click)="clearFilters()" *ngIf="hasActiveFilters()">
    <mat-icon>clear</mat-icon> Clear filters
  </button>
</div>
```

Add debounced search (400ms, same pattern as `user-list`):
```typescript
private searchSubject = new Subject<string>();

ngOnInit() {
  this.searchSubject.pipe(
    debounceTime(400),
    distinctUntilChanged()
  ).subscribe(() => this.applyFilters());
}

onSearchChange(query: string) {
  this.searchSubject.next(query);
}
```

### ME-6: Payout Trend Charts in Financial Dashboard

**Problem**: Financial dashboard shows only current escrow balances with no historical context. Admins need to see payout trends over time.

**File**: `apps/frontend/src/app/admin/financial-dashboard/financial-dashboard.component.ts` and `.html`

Add a line chart showing weekly payout totals (reuse the existing `ng2-charts` setup from the main dashboard):

```typescript
import { BaseChartDirective } from 'ng2-charts';
import { ChartConfiguration } from 'chart.js';

payoutChartData = signal<ChartConfiguration<'line'>['data']>({
  labels: [],
  datasets: [{
    data: [],
    label: 'Payouts (RSD)',
    borderColor: '#4caf50',
    backgroundColor: 'rgba(76,175,80,0.1)',
    fill: true,
    tension: 0.3
  }]
});

payoutChartOptions: ChartConfiguration<'line'>['options'] = {
  responsive: true,
  plugins: { legend: { display: false } },
  scales: {
    y: { beginAtZero: true, ticks: { callback: (v) => v.toLocaleString() + ' RSD' } }
  }
};
```

**Backend support needed**: If no payout history endpoint exists, add `GET /api/v1/admin/financial/payout-history?days=90` that returns `{ date: string, amount: number }[]` grouped by week.

---

## GROUP 3: LARGER INVESTMENTS (8+ hours each)

### LI-1: Global Multi-Entity Search

**Problem**: The admin layout's global search only searches users. Admins need to find bookings (by ID, reference), cars (by plate, make), disputes, and users from one search bar.

**File**: `apps/frontend/src/app/admin/admin-layout/admin-layout.component.ts`

**Implementation**:

1. Create a new `AdminSearchService` that fans out queries to multiple endpoints:
```typescript
@Injectable({ providedIn: 'root' })
export class AdminSearchService {
  private api = inject(AdminApiService);

  search(query: string): Observable<SearchResults> {
    if (!query || query.length < 2) return of({ users: [], bookings: [], cars: [] });

    return forkJoin({
      users: this.api.searchUsers(query).pipe(catchError(() => of([]))),
      bookings: this.api.searchBookings(query).pipe(catchError(() => of([]))),
      cars: this.api.searchCars(query).pipe(catchError(() => of([])))
    });
  }
}
```

2. Replace the current search input with a `MatAutocomplete` overlay that groups results by entity type:
```html
<mat-form-field class="w-80">
  <mat-icon matPrefix>search</mat-icon>
  <input matInput placeholder="Search users, bookings, cars..."
         [matAutocomplete]="searchAuto"
         (input)="onSearchInput($event)">
  <mat-autocomplete #searchAuto="matAutocomplete" (optionSelected)="navigateToResult($event)">
    <mat-optgroup *ngIf="searchResults.users?.length" label="Users">
      <mat-option *ngFor="let u of searchResults.users" [value]="u">
        <mat-icon>person</mat-icon> {{ u.firstName }} {{ u.lastName }} — {{ u.email }}
      </mat-option>
    </mat-optgroup>
    <mat-optgroup *ngIf="searchResults.bookings?.length" label="Bookings">
      <mat-option *ngFor="let b of searchResults.bookings" [value]="b">
        <mat-icon>event</mat-icon> #{{ b.id }} — {{ b.status }}
      </mat-option>
    </mat-optgroup>
    <mat-optgroup *ngIf="searchResults.cars?.length" label="Cars">
      <mat-option *ngFor="let c of searchResults.cars" [value]="c">
        <mat-icon>directions_car</mat-icon> {{ c.make }} {{ c.model }} — {{ c.licensePlate }}
      </mat-option>
    </mat-optgroup>
  </mat-autocomplete>
</mat-form-field>
```

3. `navigateToResult()` routes to the correct detail page based on entity type.

**Backend support needed**: Add search endpoints if they don't exist:
- `GET /api/v1/admin/bookings/search?q=...` (search by ID, reference, guest/host name)
- `GET /api/v1/admin/cars/search?q=...` (search by plate, make, model, owner name)

### LI-2: Keyboard Shortcuts

**Problem**: Admin workflows are mouse-heavy. Power users need keyboard shortcuts for common actions.

**Implementation**: Create an `AdminKeyboardService` that registers global hotkeys:

```typescript
@Injectable({ providedIn: 'root' })
export class AdminKeyboardService {
  private router = inject(Router);

  private shortcuts: Record<string, () => void> = {
    'g u': () => this.router.navigate(['/admin/users']),
    'g b': () => this.router.navigate(['/admin/bookings']),
    'g c': () => this.router.navigate(['/admin/cars']),
    'g f': () => this.router.navigate(['/admin/financial']),
    'g d': () => this.router.navigate(['/admin/disputes']),
    '/': () => document.querySelector<HTMLInputElement>('.admin-search-input')?.focus(),
    '?': () => this.showShortcutHelp(),
  };
}
```

Use a two-key sequence detector (like GitHub's `g i` for issues). Show a help overlay on `?` with all available shortcuts.

Register in `admin-layout.component.ts` via `@HostListener('document:keydown')`.

### LI-3: Extract Inline DTOs from admin-api.service.ts

**Problem**: `admin-api.service.ts` has 50+ inline interfaces/types scattered through ~1035 lines. This makes them impossible to reuse and hard to maintain.

**Action**: Extract all DTOs into organized model files:

```
apps/frontend/src/app/admin/models/
├── user.models.ts        (AdminUserDto, UserDetailDto, RiskScoreDto, etc.)
├── booking.models.ts     (AdminBookingDto, BookingDetailDto, etc.)
├── car.models.ts         (AdminCarDto, CarDetailDto, ApprovalStatusChangeDto, etc.)
├── financial.models.ts   (EscrowBalanceDto, PayoutQueueDto, BatchPayoutRequest, etc.)
├── dispute.models.ts     (DisputeDto, DisputeDetailDto, ResolutionRequest, etc.)
├── audit.models.ts       (AuditLogDto, AuditActionFilter, etc.)
└── index.ts              (barrel export)
```

Keep `admin-api.service.ts` as a pure HTTP service — only method calls, no type definitions.

---

## GROUP 4: DOCUMENT VERIFICATION UX (Critical for P2P Trust)

### DV-1: Document Expiry Dashboard

**Problem**: No visibility into which hosts have expiring documents (insurance, registration). For a P2P car rental, expired documents = liability risk.

**Implementation**: Add a new tab or section in the car management area:

1. **Backend endpoint needed**: `GET /api/v1/admin/cars/expiring-documents?days=30` returning cars with documents expiring within N days.

2. **Frontend**: New `DocumentExpiryComponent` (standalone, signals-based):
   - Table showing: Car, Owner, Document Type, Expiry Date, Days Remaining
   - Color-coded: Red (<7 days), Yellow (<30 days), Green (>30 days)
   - Action button to send reminder notification to host
   - Filter by document type and urgency level

### DV-2: Improved Car Review Photo Comparison

**Problem**: Car review shows photos in a basic grid. For document verification, admins need side-by-side comparison (e.g., registration document vs car plates, front/back of insurance).

**Enhancement to `car-review.component.html`**:

```html
<div class="grid grid-cols-2 gap-4" *ngIf="comparisonMode">
  <div class="border rounded p-2">
    <p class="text-sm font-medium mb-2">Document</p>
    <img [src]="leftPhoto" class="w-full object-contain max-h-96 cursor-zoom-in"
         (click)="openInGallery(leftPhoto)">
  </div>
  <div class="border rounded p-2">
    <p class="text-sm font-medium mb-2">Car Photo</p>
    <img [src]="rightPhoto" class="w-full object-contain max-h-96 cursor-zoom-in"
         (click)="openInGallery(rightPhoto)">
  </div>
</div>
```

Use the existing `PhotoGalleryDialogComponent` for full-screen zoom.

---

## IMPLEMENTATION ORDER

Execute in this order for maximum impact with minimal conflicts:

1. **QW-1** (ConfirmDialog) — Creates shared component used by everything else
2. **QW-2** (Standardize notifications) — Quick grep-and-replace
3. **QW-3** (Clickable KPIs) — Self-contained dashboard change
4. **QW-4** (Evidence photos) — Self-contained dispute change
5. **LI-3** (Extract DTOs) — Reduces `admin-api.service.ts` complexity before further changes
6. **ME-1** (Time range selector) — Shared component for charts
7. **ME-5** (Car list filters) — Improves daily admin workflow
8. **ME-2** (Bulk user actions) — Enables moderation at scale
9. **ME-3** (Dispute split-view) — Restructures dispute-detail layout
10. **ME-4** (Resolution templates) — Builds on dispute split-view
11. **ME-6** (Payout trend charts) — May need backend endpoint
12. **DV-1** (Document expiry) — New component, may need backend endpoint
13. **DV-2** (Photo comparison) — Enhancement to car review
14. **LI-1** (Global search) — Needs backend search endpoints
15. **LI-2** (Keyboard shortcuts) — Polish feature, add last

## VERIFICATION CHECKLIST

After each group, verify:

- [ ] `ng build` succeeds with zero warnings
- [ ] No `window.confirm()` or `window.prompt()` anywhere in admin module
- [ ] No direct `MatSnackBar` usage (only through `AdminNotificationService`)
- [ ] All new components are standalone
- [ ] New Signal-based components use `@if`/`@for`, not `*ngIf`/`*ngFor`
- [ ] Existing Observable-based components keep their current patterns
- [ ] All clickable elements have `tabindex` and keyboard event handlers
- [ ] All images have `alt` attributes
- [ ] No hardcoded strings that should be in models/constants
- [ ] Responsive layout works at 1024px and 1440px widths

## NOTES

- The `PhotoGalleryDialogComponent` already exists with tab support, fullscreen, and navigation — reuse it, don't recreate it
- `SelectionModel` from `@angular/cdk/collections` is already used in `financial-dashboard` — follow that pattern for bulk actions
- Charts use `ng2-charts` with `chart.js` — both are already dependencies
- The backend already supports `Pageable` with `page`/`size`/`sort` query params
- Currency is always RSD (Serbian Dinar)
- All amounts in DTOs are in cents (multiply by 100 from BigDecimal)

import { Component, OnInit, OnDestroy, inject, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterModule, NavigationEnd } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSortModule } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { MatTabsModule } from '@angular/material/tabs';
import { debounceTime, distinctUntilChanged, Subject, filter, takeUntil, forkJoin } from 'rxjs';
import { AdminApiService, AdminCarDto } from '../../../../core/services/admin-api.service';
import { AdminNotificationService } from '../../../../core/services/admin-notification.service';
import { normalizeMediaUrl } from '@shared/utils/media-url.util';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ApprovalStatus } from '@core/models/car.model';
import { CarApprovalDialogComponent } from '../dialogs/car-approval-dialog.component';

@Component({
  selector: 'app-car-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    FormsModule,
    MatTableModule,
    MatPaginatorModule,
    MatSortModule,
    MatInputModule,
    MatFormFieldModule,
    MatButtonModule,
    MatIconModule,
    MatMenuModule,
    MatTabsModule,
    MatProgressSpinnerModule,
    MatChipsModule,
    MatDialogModule,
    MatTooltipModule,
  ],
  template: `
    <div class="admin-page">
      <div class="page-header">
        <div>
          <h1 class="page-title">Car Management</h1>
          <p class="page-subtitle">Approve listings and monitor availability.</p>
        </div>
      </div>

      <div class="surface-card surface-wide table-shell">
        <mat-tab-group (selectedTabChange)="onTabChange($event)">
          <mat-tab>
            <ng-template mat-tab-label>
              <mat-icon>pending_actions</mat-icon>
              <span>Pending Approval</span>
            </ng-template>
            <div class="tab-section">
              <div *ngIf="loadingPending" class="row between" style="padding: 12px 0;">
                <span class="muted">Loading pending cars…</span>
                <mat-progress-spinner diameter="28" mode="indeterminate"></mat-progress-spinner>
              </div>

              <div *ngIf="!loadingPending && pendingCars.length === 0" class="empty-state">
                <mat-icon>check_circle</mat-icon>
                <p>No cars waiting for approval.</p>
              </div>

              <table
                *ngIf="!loadingPending && pendingCars.length > 0"
                mat-table
                [dataSource]="pendingCars"
              >
                <!-- Car column -->
                <ng-container matColumnDef="car">
                  <th mat-header-cell *matHeaderCellDef>Vozilo</th>
                  <td mat-cell *matCellDef="let car">
                    <div class="row">
                      <div
                        class="car-thumb"
                        [style.backgroundImage]="'url(' + getCarThumbUrl(car) + ')'"
                      ></div>
                      <div class="stack" style="gap:2px;">
                        <span class="strong">{{ car.brand }} {{ car.model }}</span>
                        <span class="muted mini-label">{{ car.year }}</span>
                      </div>
                    </div>
                  </td>
                </ng-container>

                <!-- Owner column -->
                <ng-container matColumnDef="owner">
                  <th mat-header-cell *matHeaderCellDef>Vlasnik</th>
                  <td mat-cell *matCellDef="let car">{{ car.ownerEmail }}</td>
                </ng-container>

                <!-- NEW: Status column -->
                <ng-container matColumnDef="status">
                  <th mat-header-cell *matHeaderCellDef>Status</th>
                  <td mat-cell *matCellDef="let car">
                    <span
                      class="badge"
                      [ngClass]="getStatusBadgeClass(car.approvalStatus)"
                      [matTooltip]="car.rejectionReason"
                    >
                      {{ getStatusLabel(car.approvalStatus) }}
                    </span>
                  </td>
                </ng-container>

                <!-- Actions column -->
                <ng-container matColumnDef="actions">
                  <th mat-header-cell *matHeaderCellDef></th>
                  <td mat-cell *matCellDef="let car">
                    <div class="action-buttons">
                      <button
                        mat-stroked-button
                        color="primary"
                        (click)="openApprovalDialog(car)"
                        matTooltip="Pregledaj i odobri/odbij"
                      >
                        <mat-icon>assessment</mat-icon> Pregledaj
                      </button>

                      <!-- Quick Approve Action -->
                      <button
                        mat-icon-button
                        color="accent"
                        *ngIf="car.approvalStatus === ApprovalStatus.PENDING"
                        (click)="approveCar(car, $event)"
                        matTooltip="Brzo odobrenje"
                      >
                        <mat-icon>check_circle</mat-icon>
                      </button>
                    </div>
                  </td>
                </ng-container>

                <tr mat-header-row *matHeaderRowDef="['car', 'owner', 'status', 'actions']"></tr>
                <tr
                  mat-row
                  *matRowDef="let row; columns: ['car', 'owner', 'status', 'actions']"
                ></tr>
              </table>
            </div>
          </mat-tab>

          <mat-tab label="All Cars">
            <div class="tab-section">
              <mat-form-field appearance="outline" class="search-field">
                <mat-icon matPrefix>search</mat-icon>
                <input
                  matInput
                  placeholder="Search cars"
                  [ngModel]="searchTerm"
                  (ngModelChange)="onSearch($event)"
                />
              </mat-form-field>

              <div *ngIf="loadingAll" class="row between" style="padding: 12px 0;">
                <span class="muted">Loading cars…</span>
                <mat-progress-spinner diameter="28" mode="indeterminate"></mat-progress-spinner>
              </div>

              <table *ngIf="!loadingAll" mat-table [dataSource]="allCars">
                <ng-container matColumnDef="id">
                  <th mat-header-cell *matHeaderCellDef>ID</th>
                  <td mat-cell *matCellDef="let car">#{{ car.id }}</td>
                </ng-container>

                <ng-container matColumnDef="car">
                  <th mat-header-cell *matHeaderCellDef>Car</th>
                  <td mat-cell *matCellDef="let car">{{ car.brand }} {{ car.model }}</td>
                </ng-container>

                <ng-container matColumnDef="owner">
                  <th mat-header-cell *matHeaderCellDef>Owner</th>
                  <td mat-cell *matCellDef="let car">{{ car.ownerEmail }}</td>
                </ng-container>

                <ng-container matColumnDef="status">
                  <th mat-header-cell *matHeaderCellDef>Status</th>
                  <td mat-cell *matCellDef="let car">
                    <span class="badge" [ngClass]="getStatusBadgeClass(car.approvalStatus)">
                      {{ getStatusLabel(car.approvalStatus) }}
                    </span>
                  </td>
                </ng-container>

                <ng-container matColumnDef="actions">
                  <th mat-header-cell *matHeaderCellDef></th>
                  <td mat-cell *matCellDef="let car">
                    <button mat-icon-button [matMenuTriggerFor]="menu" aria-label="More actions">
                      <mat-icon>more_vert</mat-icon>
                    </button>
                    <mat-menu #menu="matMenu">
                      <button mat-menu-item (click)="openApprovalDialog(car)">
                        <mat-icon>gavel</mat-icon>
                        <span>Upravljaj statusom</span>
                      </button>
                      <button mat-menu-item (click)="viewCar(car.id)">
                        <mat-icon>visibility</mat-icon>
                        <span>Pregledaj detalje</span>
                      </button>
                    </mat-menu>
                  </td>
                </ng-container>

                <tr
                  mat-header-row
                  *matHeaderRowDef="['id', 'car', 'owner', 'status', 'actions']"
                ></tr>
                <tr
                  mat-row
                  *matRowDef="let row; columns: ['id', 'car', 'owner', 'status', 'actions']"
                ></tr>
              </table>

              <mat-paginator
                [length]="totalElements"
                [pageSize]="pageSize"
                [pageIndex]="pageIndex"
                (page)="onPageChange($event)"
                showFirstLastButtons
              >
              </mat-paginator>
            </div>
          </mat-tab>
        </mat-tab-group>
      </div>
    </div>
  `,
  styleUrls: ['../../admin-shared.styles.scss', './car-list.component.scss'],
})
export class CarListComponent implements OnInit, OnDestroy {
  private adminApi = inject(AdminApiService);
  private router = inject(Router);
  private notification = inject(AdminNotificationService);

  protected getCarThumbUrl(car: AdminCarDto): string {
    return normalizeMediaUrl(car.imageUrl) ?? 'assets/images/car-placeholder.png';
  }

  // Tab State
  selectedTabIndex = 0;

  // Data
  pendingCars: AdminCarDto[] = [];
  allCars: AdminCarDto[] = [];

  // Separate loading states to prevent race conditions
  loadingPending = true;
  loadingAll = true;

  // Legacy getter for backwards compatibility (returns true if either is loading)
  get loading(): boolean {
    return this.loadingPending || this.loadingAll;
  }

  totalElements = 0;
  pageIndex = 0;
  pageSize = 10;

  searchTerm = '';
  private searchSubject = new Subject<string>();

  private cdr = inject(ChangeDetectorRef);
  private destroy$ = new Subject<void>();

  ngOnInit() {
    // Initial load - load both tabs in parallel
    this.refreshView();

    // Listen for navigation end to ensure data refreshes when navigating to this page
    // even if the component is reused.
    // IMPORTANT: Use takeUntil to prevent memory leaks and post-destroy API calls
    this.router.events
      .pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntil(this.destroy$)
      )
      .subscribe(() => {
        this.refreshView();
      });

    this.searchSubject
      .pipe(debounceTime(400), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe((term) => {
        this.pageIndex = 0;
        this.loadAllCars(term);
      });
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
  }

  onTabChange(event: any) {
    this.selectedTabIndex = event.index;
    if (this.selectedTabIndex === 0) {
      this.loadPendingCars();
    } else {
      this.loadAllCars();
    }
  }

  loadPendingCars() {
    this.loadingPending = true;
    this.adminApi.getPendingCars().subscribe({
      next: (cars) => {
        this.pendingCars = cars;
        this.loadingPending = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to load pending cars', err);
        this.notification.showError('Failed to load pending cars');
        this.loadingPending = false;
        this.cdr.markForCheck();
      },
    });
  }

  loadAllCars(search?: string) {
    this.loadingAll = true;
    this.adminApi.getCars(this.pageIndex, this.pageSize, search).subscribe({
      next: (response) => {
        this.allCars = response.content;
        this.totalElements = response.totalElements;
        this.loadingAll = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to load cars', err);
        this.notification.showError('Failed to load cars');
        this.loadingAll = false;
        this.cdr.markForCheck();
      },
    });
  }

  onSearch(term: string) {
    this.searchTerm = term;
    this.searchSubject.next(term);
  }

  onPageChange(event: PageEvent) {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.loadAllCars(this.searchTerm);
  }

  // Helpers
  protected readonly ApprovalStatus = ApprovalStatus;
  private dialog = inject(MatDialog);

  getStatusBadgeClass(status?: string): string {
    switch (status) {
      case ApprovalStatus.PENDING:
        return 'badge-warning';
      case ApprovalStatus.APPROVED:
        return 'badge-success';
      case ApprovalStatus.REJECTED:
        return 'badge-error';
      case ApprovalStatus.SUSPENDED:
        return 'badge-danger';
      default:
        return 'badge-neutral';
    }
  }

  getStatusLabel(status?: string): string {
    switch (status) {
      case ApprovalStatus.PENDING:
        return 'Na čekanju';
      case ApprovalStatus.APPROVED:
        return 'Odobreno';
      case ApprovalStatus.REJECTED:
        return 'Odbijeno';
      case ApprovalStatus.SUSPENDED:
        return 'Suspendirano';
      default:
        return 'Nepoznato';
    }
  }

  refreshView(): void {
    // Use forkJoin to load both lists in parallel and properly manage loading states
    this.loadingPending = true;
    this.loadingAll = true;

    forkJoin({
      pending: this.adminApi.getPendingCars(),
      all: this.adminApi.getCars(this.pageIndex, this.pageSize, this.searchTerm),
    })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: ({ pending, all }) => {
          this.pendingCars = pending;
          this.allCars = all.content;
          this.totalElements = all.totalElements;
          this.loadingPending = false;
          this.loadingAll = false;
          this.cdr.markForCheck();
        },
        error: (err) => {
          console.error('Failed to refresh cars', err);
          this.notification.showError('Failed to load cars');
          this.loadingPending = false;
          this.loadingAll = false;
          this.cdr.markForCheck();
        },
      });
  }

  openApprovalDialog(car: AdminCarDto): void {
    // Navigate to car review page instead of dialog
    this.router.navigate([`/admin/cars/${car.id}/review`]);
  }

  approveCar(car: AdminCarDto, event: Event): void {
    event.stopPropagation();
    if (confirm(`Odobrite vozilo ${car.brand} ${car.model}?`)) {
      this.adminApi.approveCar(car.id).subscribe({
        next: () => {
          this.notification.showSuccess('Vozilo odobreno');
          this.refreshView();
        },
        error: () => {
          this.notification.showError('Greška pri odobravanju');
        },
      });
    }
  }

  viewCar(carId: number) {
    this.notification.showInfo(`Car detail view for ID ${carId} coming soon`);
  }
}
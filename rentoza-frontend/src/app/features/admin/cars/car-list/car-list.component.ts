import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
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
import { debounceTime, distinctUntilChanged, Subject } from 'rxjs';
import { AdminApiService, AdminCarDto } from '../../../../core/services/admin-api.service';
import { AdminNotificationService } from '../../../../core/services/admin-notification.service';

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
              <div *ngIf="loading" class="row between" style="padding: 12px 0;">
                <span class="muted">Loading pending cars…</span>
                <mat-progress-spinner diameter="28" mode="indeterminate"></mat-progress-spinner>
              </div>

              <div *ngIf="!loading && pendingCars.length === 0" class="empty-state">
                <mat-icon>check_circle</mat-icon>
                <p>No cars waiting for approval.</p>
              </div>

              <table
                *ngIf="!loading && pendingCars.length > 0"
                mat-table
                [dataSource]="pendingCars"
              >
                <ng-container matColumnDef="car">
                  <th mat-header-cell *matHeaderCellDef>Car</th>
                  <td mat-cell *matCellDef="let car">
                    <div class="row">
                      <div
                        class="car-thumb"
                        [style.backgroundImage]="
                          'url(' + (car.imageUrl || 'assets/images/car-placeholder.png') + ')'
                        "
                        aria-hidden="true"
                      ></div>
                      <div class="stack" style="gap:2px;">
                        <span class="strong">{{ car.brand }} {{ car.model }}</span>
                        <span class="muted mini-label">{{ car.year }}</span>
                      </div>
                    </div>
                  </td>
                </ng-container>

                <ng-container matColumnDef="owner">
                  <th mat-header-cell *matHeaderCellDef>Owner</th>
                  <td mat-cell *matCellDef="let car">{{ car.ownerEmail }}</td>
                </ng-container>

                <ng-container matColumnDef="actions">
                  <th mat-header-cell *matHeaderCellDef></th>
                  <td mat-cell *matCellDef="let car">
                    <button mat-stroked-button color="primary" (click)="viewCar(car.id)">
                      Review
                    </button>
                  </td>
                </ng-container>

                <tr mat-header-row *matHeaderRowDef="['car', 'owner', 'actions']"></tr>
                <tr mat-row *matRowDef="let row; columns: ['car', 'owner', 'actions']"></tr>
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

              <div *ngIf="loading" class="row between" style="padding: 12px 0;">
                <span class="muted">Loading cars…</span>
                <mat-progress-spinner diameter="28" mode="indeterminate"></mat-progress-spinner>
              </div>

              <table *ngIf="!loading" mat-table [dataSource]="allCars">
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
                    <span
                      class="badge"
                      [ngClass]="car.available ? 'badge-success' : 'badge-neutral'"
                    >
                      {{ car.available ? 'Available' : 'Unavailable' }}
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
                      <button mat-menu-item (click)="viewCar(car.id)">View Details</button>
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
export class CarListComponent implements OnInit {
  private adminApi = inject(AdminApiService);
  private router = inject(Router);
  private notification = inject(AdminNotificationService);

  // Tab State
  selectedTabIndex = 0;

  // Data
  pendingCars: AdminCarDto[] = [];
  allCars: AdminCarDto[] = [];

  loading = true;
  totalElements = 0;
  pageIndex = 0;
  pageSize = 10;

  searchTerm = '';
  private searchSubject = new Subject<string>();

  ngOnInit() {
    this.loadPendingCars();

    this.searchSubject.pipe(debounceTime(400), distinctUntilChanged()).subscribe((term) => {
      this.pageIndex = 0;
      this.loadAllCars(term);
    });
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
    this.loading = true;
    this.adminApi.getPendingCars().subscribe({
      next: (cars) => {
        this.pendingCars = cars;
        this.loading = false;
      },
      error: (err) => {
        console.error('Failed to load pending cars', err);
        this.notification.showError('Failed to load pending cars');
        this.loading = false;
      },
    });
  }

  loadAllCars(search?: string) {
    this.loading = true;
    this.adminApi.getCars(this.pageIndex, this.pageSize, search).subscribe({
      next: (response) => {
        this.allCars = response.content;
        this.totalElements = response.totalElements;
        this.loading = false;
      },
      error: (err) => {
        console.error('Failed to load cars', err);
        this.notification.showError('Failed to load cars');
        this.loading = false;
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

  viewCar(carId: number) {
    // Navigate to detail page (to be implemented)
    // this.router.navigate(['/admin/cars', carId]);
    this.notification.showInfo(`Car detail view for ID ${carId} coming soon`);
  }
}

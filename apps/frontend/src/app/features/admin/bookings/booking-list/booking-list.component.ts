import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AdminApiService, AdminBookingDto } from '../../../../core/services/admin-api.service';
import { AdminNotificationService } from '../../../../core/services/admin-notification.service';
import { ConfirmDialogComponent } from '../../shared/dialogs/confirm-dialog/confirm-dialog.component';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, debounceTime, distinctUntilChanged } from 'rxjs';

@Component({
  selector: 'app-booking-list',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatTableModule,
    MatPaginatorModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatDialogModule,
    MatTooltipModule,
  ],
  styleUrls: ['../../admin-shared.styles.scss'],
  template: `
    <div class="admin-page">
      <h1 class="page-title">Booking Management</h1>

      <div
        class="filters-row"
        style="display: flex; gap: 16px; margin-bottom: 16px; flex-wrap: wrap;"
      >
        <mat-form-field appearance="outline" style="flex: 1; min-width: 200px;">
          <mat-label>Search renter name or email</mat-label>
          <input
            matInput
            [(ngModel)]="searchTerm"
            (ngModelChange)="onSearchChange($event)"
            placeholder="Search..."
          />
          <mat-icon matPrefix>search</mat-icon>
        </mat-form-field>

        <mat-form-field appearance="outline" style="width: 200px;">
          <mat-label>Status</mat-label>
          <mat-select [(ngModel)]="statusFilter" (ngModelChange)="loadBookings()">
            <mat-option [value]="null">All Statuses</mat-option>
            <mat-option *ngFor="let s of statuses" [value]="s">{{ s }}</mat-option>
          </mat-select>
        </mat-form-field>
      </div>

      <div *ngIf="loading()" class="loading-block" style="text-align: center; padding: 48px;">
        <mat-spinner diameter="48"></mat-spinner>
      </div>

      <div *ngIf="!loading()">
        <table mat-table [dataSource]="bookings()" class="full-width" style="width: 100%;">
          <ng-container matColumnDef="id">
            <th mat-header-cell *matHeaderCellDef>ID</th>
            <td mat-cell *matCellDef="let b">{{ b.id }}</td>
          </ng-container>

          <ng-container matColumnDef="status">
            <th mat-header-cell *matHeaderCellDef>Status</th>
            <td mat-cell *matCellDef="let b">
              <span class="badge" [ngClass]="getStatusClass(b.status)">{{ b.status }}</span>
            </td>
          </ng-container>

          <ng-container matColumnDef="carTitle">
            <th mat-header-cell *matHeaderCellDef>Car</th>
            <td mat-cell *matCellDef="let b">{{ b.carTitle }}</td>
          </ng-container>

          <ng-container matColumnDef="renterName">
            <th mat-header-cell *matHeaderCellDef>Renter</th>
            <td mat-cell *matCellDef="let b">{{ b.renterName }}</td>
          </ng-container>

          <ng-container matColumnDef="ownerName">
            <th mat-header-cell *matHeaderCellDef>Owner</th>
            <td mat-cell *matCellDef="let b">{{ b.ownerName }}</td>
          </ng-container>

          <ng-container matColumnDef="totalPrice">
            <th mat-header-cell *matHeaderCellDef>Price</th>
            <td mat-cell *matCellDef="let b">
              {{ b.totalPrice | currency: 'RSD' : 'symbol-narrow' }}
            </td>
          </ng-container>

          <ng-container matColumnDef="dates">
            <th mat-header-cell *matHeaderCellDef>Trip Dates</th>
            <td mat-cell *matCellDef="let b">
              {{ b.startTime | date: 'shortDate' }} - {{ b.endTime | date: 'shortDate' }}
            </td>
          </ng-container>

          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef>Actions</th>
            <td mat-cell *matCellDef="let b">
              <button
                mat-icon-button
                color="primary"
                (click)="viewBooking(b.id)"
                matTooltip="View Details"
              >
                <mat-icon>visibility</mat-icon>
              </button>
              <button
                mat-icon-button
                color="warn"
                *ngIf="!isTerminal(b.status)"
                (click)="forceComplete(b)"
                matTooltip="Force Complete"
              >
                <mat-icon>check_circle</mat-icon>
              </button>
            </td>
          </ng-container>

          <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: displayedColumns"></tr>
        </table>

        <mat-paginator
          [length]="totalElements()"
          [pageSize]="pageSize"
          [pageIndex]="currentPage()"
          [pageSizeOptions]="[10, 20, 50]"
          (page)="onPage($event)"
          showFirstLastButtons
        >
        </mat-paginator>
      </div>
    </div>
  `,
})
export class BookingListComponent implements OnInit {
  private adminApi = inject(AdminApiService);
  private notification = inject(AdminNotificationService);
  private router = inject(Router);
  private dialog = inject(MatDialog);
  private destroyRef = inject(DestroyRef);
  private searchSubject = new Subject<string>();

  bookings = signal<AdminBookingDto[]>([]);
  loading = signal(true);
  totalElements = signal(0);
  currentPage = signal(0);
  pageSize = 20;
  searchTerm = '';
  statusFilter: string | null = null;

  displayedColumns = [
    'id',
    'status',
    'carTitle',
    'renterName',
    'ownerName',
    'totalPrice',
    'dates',
    'actions',
  ];

  statuses = [
    'PENDING_APPROVAL',
    'ACTIVE',
    'APPROVED',
    'CHECK_IN_OPEN',
    'CHECK_IN_HOST_COMPLETE',
    'CHECK_IN_COMPLETE',
    'IN_TRIP',
    'CHECKOUT_OPEN',
    'CHECKOUT_GUEST_COMPLETE',
    'CHECKOUT_HOST_COMPLETE',
    'COMPLETED',
    'CANCELLED',
    'CANCELLATION_PENDING_SETTLEMENT',
    'DECLINED',
    'EXPIRED',
    'NO_SHOW_HOST',
    'NO_SHOW_GUEST',
  ];

  ngOnInit() {
    this.loadBookings();
    this.searchSubject
      .pipe(debounceTime(400), distinctUntilChanged(), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.loadBookings());
  }

  onSearchChange(value: string) {
    this.searchSubject.next(value);
  }

  loadBookings() {
    this.loading.set(true);
    this.adminApi
      .getBookings({
        status: this.statusFilter ?? undefined,
        search: this.searchTerm || undefined,
        page: this.currentPage(),
        size: this.pageSize,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (res) => {
          this.bookings.set(res.content);
          this.totalElements.set(res.totalElements);
          this.loading.set(false);
        },
        error: () => {
          this.notification.showError('Failed to load bookings');
          this.loading.set(false);
        },
      });
  }

  onPage(event: PageEvent) {
    this.currentPage.set(event.pageIndex);
    this.pageSize = event.pageSize;
    this.loadBookings();
  }

  viewBooking(id: number) {
    this.router.navigate(['/admin/bookings', id]);
  }

  forceComplete(booking: AdminBookingDto) {
    const dialogRef = this.dialog.open(ConfirmDialogComponent, {
      data: {
        title: 'Force Complete Booking',
        message: `Force-complete booking #${booking.id}? This action cannot be undone.`,
        confirmText: 'Force Complete',
        confirmColor: 'warn',
        requireReason: true,
        reasonLabel: 'Reason for force-completion',
        reasonMinLength: 10,
      },
    });
    dialogRef.afterClosed().subscribe((reason) => {
      if (reason) {
        this.adminApi
          .forceCompleteBooking(booking.id, reason)
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe({
            next: () => {
              this.notification.showSuccess(`Booking #${booking.id} force-completed`);
              this.loadBookings();
            },
            error: (err) => {
              const msg = err.error?.message || 'Failed to force-complete booking';
              this.notification.showError(msg);
            },
          });
      }
    });
  }

  isTerminal(status: string): boolean {
    return [
      'COMPLETED',
      'CANCELLED',
      'CANCELLATION_PENDING_SETTLEMENT',
      'DECLINED',
      'EXPIRED',
      'EXPIRED_SYSTEM',
      'NO_SHOW_HOST',
      'NO_SHOW_GUEST',
    ].includes(status);
  }

  getStatusClass(status: string): string {
    if (this.isTerminal(status)) return 'badge-neutral';
    if (status === 'CANCELLATION_PENDING_SETTLEMENT') return 'badge-warn';
    if (status === 'IN_TRIP') return 'badge-success';
    if (status.startsWith('CHECK_IN') || status.startsWith('CHECKOUT')) return 'badge-warn';
    return 'badge-info';
  }
}

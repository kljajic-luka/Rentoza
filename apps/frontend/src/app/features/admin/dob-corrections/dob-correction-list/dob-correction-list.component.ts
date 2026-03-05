import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';

import { AdminApiService, AdminUserDto } from '../../../../core/services/admin-api.service';
import { PaginatedResponse } from '../../../../core/models/paginated-response.model';

/**
 * Admin DOB Correction Queue Component
 *
 * Displays a paginated list of users with pending DOB correction requests.
 * Admins can click through to the user detail to approve/reject.
 */
@Component({
  selector: 'app-dob-correction-list',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatTableModule,
    MatPaginatorModule,
    MatProgressSpinnerModule,
    MatChipsModule,
    MatTooltipModule,
  ],
  templateUrl: './dob-correction-list.component.html',
  styleUrls: ['../../admin-shared.styles.scss', './dob-correction-list.component.scss'],
})
export class DobCorrectionListComponent implements OnInit {
  private readonly router = inject(Router);
  private readonly adminApi = inject(AdminApiService);

  // State
  readonly users = signal<AdminUserDto[]>([]);
  readonly totalElements = signal<number>(0);
  readonly loading = signal<boolean>(false);
  readonly error = signal<string | null>(null);
  readonly currentPage = signal<number>(0);
  readonly pageSize = signal<number>(20);

  // Table columns
  readonly displayedColumns = ['user', 'role', 'verificationStatus', 'dobStatus', 'actions'];

  ngOnInit(): void {
    this.loadQueue();
  }

  loadQueue(): void {
    this.loading.set(true);
    this.error.set(null);

    this.adminApi.getPendingDobCorrections(this.currentPage(), this.pageSize()).subscribe({
      next: (response: PaginatedResponse<AdminUserDto>) => {
        this.users.set(response.content);
        this.totalElements.set(response.totalElements);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set('Failed to load pending DOB corrections');
        this.loading.set(false);
      },
    });
  }

  onPageChange(event: PageEvent): void {
    this.currentPage.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
    this.loadQueue();
  }

  viewUser(userId: number): void {
    this.router.navigate(['/admin/users', userId]);
  }

  refresh(): void {
    this.currentPage.set(0);
    this.loadQueue();
  }

  getVerificationBadgeClass(status?: string): string {
    switch (status) {
      case 'VERIFIED':
        return 'badge badge-success';
      case 'PENDING_REVIEW':
        return 'badge badge-warn';
      case 'REJECTED':
        return 'badge badge-error';
      default:
        return 'badge badge-neutral';
    }
  }

  getVerificationLabel(status?: string): string {
    switch (status) {
      case 'VERIFIED':
        return 'Verified';
      case 'PENDING_REVIEW':
        return 'Pending';
      case 'REJECTED':
        return 'Rejected';
      default:
        return 'Not submitted';
    }
  }
}

import { Component, OnInit, ViewChild, inject, DestroyRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatSortModule, MatSort, Sort } from '@angular/material/sort';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { SelectionModel } from '@angular/cdk/collections';
import { Router, RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { debounceTime, distinctUntilChanged, Subject, take, forkJoin, of, catchError } from 'rxjs';
import { AdminUserDto } from '../../../../core/services/admin-api.service';
import { AdminNotificationService } from '../../../../core/services/admin-notification.service';
import { ExportService } from '../../../../core/services/export.service';
import { BanUserDialogComponent } from '../../shared/dialogs/ban-user-dialog/ban-user-dialog.component';
import { UnbanUserDialogComponent } from '../../shared/dialogs/unban-user-dialog/unban-user-dialog.component';
import { ConfirmDialogComponent } from '../../shared/dialogs/confirm-dialog/confirm-dialog.component';
import { AdminStateService } from '../../../../core/services/admin-state.service';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

@Component({
  selector: 'app-user-list',
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
    MatChipsModule,
    MatMenuModule,
    MatProgressSpinnerModule,
    MatDialogModule,
    MatSelectModule,
    MatTooltipModule,
    MatCheckboxModule,
  ],
  templateUrl: './user-list.component.html',
  styleUrls: ['../../admin-shared.styles.scss', './user-list.component.scss'],
})
export class UserListComponent implements OnInit {
  private router = inject(Router);
  private notification = inject(AdminNotificationService);
  private exportService = inject(ExportService);
  private dialog = inject(MatDialog);
  private adminState = inject(AdminStateService);
  private destroyRef = inject(DestroyRef);

  readonly displayedColumns = [
    'select',
    'name',
    'email',
    'role',
    'ownerVerification',
    'status',
    'riskScore',
    'actions',
  ] as const;
  users$ = this.adminState.users$;
  totalElements$ = this.adminState.totalUsers$;
  loading$ = this.adminState.loading$;

  // Bulk selection
  selection = new SelectionModel<AdminUserDto>(true, []);

  // Search debouncing
  searchTerm = '';
  private searchSubject = new Subject<string>();

  // Filter state
  statusFilter = '';
  roleFilter = '';
  verificationFilter = '';

  // Pagination state
  pageIndex = 0;
  pageSize = 20;

  // Sorting state (server-side)
  sortParam?: string;

  // Bulk action in progress
  bulkProcessing = false;

  @ViewChild(MatPaginator) paginator!: MatPaginator;
  @ViewChild(MatSort) sort!: MatSort;

  ngOnInit() {
    this.loadUsers();

    // Debounce search input
    this.searchSubject
      .pipe(debounceTime(400), distinctUntilChanged(), takeUntilDestroyed(this.destroyRef))
      .subscribe((term) => {
        this.pageIndex = 0; // Reset to first page on search
        this.loadUsers(term);
      });
  }

  loadUsers(search?: string) {
    this.selection.clear();
    this.adminState.loadUsers(this.pageIndex, this.pageSize, search, this.sortParam);
  }

  onSearch(term: string) {
    this.searchTerm = term;
    this.searchSubject.next(term);
  }

  onFilterChange() {
    this.pageIndex = 0; // Reset to first page on filter change
    this.loadUsers(this.searchTerm);
  }

  onPageChange(event: PageEvent) {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.loadUsers(this.searchTerm);
  }

  onSortChange(sort: Sort) {
    if (!sort.direction) {
      this.sortParam = undefined;
      this.loadUsers(this.searchTerm);
      return;
    }

    // Only enable sorting for known backend fields
    if (sort.active === 'ownerVerificationSubmittedAt') {
      this.sortParam = `${sort.active},${sort.direction}`;
    } else {
      this.sortParam = undefined;
    }

    this.pageIndex = 0;
    this.loadUsers(this.searchTerm);
  }

  // ==================== BULK SELECTION ====================

  isAllSelected(users: AdminUserDto[]): boolean {
    return this.selection.selected.length === users.length && users.length > 0;
  }

  toggleAllRows(users: AdminUserDto[]): void {
    if (this.isAllSelected(users)) {
      this.selection.clear();
    } else {
      users.forEach((row) => this.selection.select(row));
    }
  }

  // ==================== BULK ACTIONS ====================

  bulkBan(): void {
    const selected = this.selection.selected.filter((u) => !u.banned);
    if (selected.length === 0) {
      this.notification.showWarning('No active users selected to ban');
      return;
    }

    const dialogRef = this.dialog.open(ConfirmDialogComponent, {
      data: {
        title: 'Bulk Ban Users',
        message: `Ban ${selected.length} user(s)? This action requires a reason.`,
        confirmText: 'Ban All',
        confirmColor: 'warn' as const,
        requireReason: true,
        reasonLabel: 'Ban reason',
        reasonMinLength: 5,
      },
    });

    dialogRef.afterClosed().subscribe((reason) => {
      if (reason) {
        this.bulkProcessing = true;
        const actions = selected.map((u) =>
          this.adminState.banUser(u.id, reason).pipe(catchError(() => of('FAILED' as const))),
        );
        forkJoin(actions).subscribe({
          next: (results) => {
            const failed = results.filter((r) => r === 'FAILED').length;
            const succeeded = results.length - failed;
            if (failed === 0) {
              this.notification.showSuccess(`${succeeded} user(s) banned`);
            } else {
              this.notification.showWarning(`${succeeded} banned, ${failed} failed`);
            }
            this.bulkProcessing = false;
            this.loadUsers(this.searchTerm);
          },
        });
      }
    });
  }

  bulkUnban(): void {
    const selected = this.selection.selected.filter((u) => u.banned);
    if (selected.length === 0) {
      this.notification.showWarning('No banned users selected to unban');
      return;
    }

    const dialogRef = this.dialog.open(ConfirmDialogComponent, {
      data: {
        title: 'Bulk Unban Users',
        message: `Unban ${selected.length} user(s)?`,
        confirmText: 'Unban All',
        confirmColor: 'primary' as const,
      },
    });

    dialogRef.afterClosed().subscribe((confirmed) => {
      if (confirmed) {
        this.bulkProcessing = true;
        const actions = selected.map((u) =>
          this.adminState.unbanUser(u.id).pipe(catchError(() => of('FAILED' as const))),
        );
        forkJoin(actions).subscribe({
          next: (results) => {
            const failed = results.filter((r) => r === 'FAILED').length;
            const succeeded = results.length - failed;
            if (failed === 0) {
              this.notification.showSuccess(`${succeeded} user(s) unbanned`);
            } else {
              this.notification.showWarning(`${succeeded} unbanned, ${failed} failed`);
            }
            this.bulkProcessing = false;
            this.loadUsers(this.searchTerm);
          },
        });
      }
    });
  }

  // ==================== HELPERS ====================

  getOwnerVerificationBadge(status?: string): string {
    switch (status) {
      case 'PENDING_REVIEW':
        return 'badge badge-warn';
      case 'VERIFIED':
        return 'badge badge-success';
      case 'REJECTED':
        return 'badge badge-error';
      default:
        return 'badge badge-neutral';
    }
  }

  getOwnerVerificationLabel(status?: string): string {
    switch (status) {
      case 'PENDING_REVIEW':
        return 'Pending';
      case 'VERIFIED':
        return 'Verified';
      case 'REJECTED':
        return 'Rejected';
      default:
        return 'Not submitted';
    }
  }

  viewUser(userId: number) {
    this.router.navigate(['/admin/users', userId]);
  }

  exportUsers() {
    this.users$.pipe(take(1)).subscribe((users) => {
      if (!users || users.length === 0) {
        this.notification.showInfo('No data to export');
        return;
      }

      const dataToExport = users.map((u) => ({
        ID: u.id,
        Name: `${u.firstName} ${u.lastName}`,
        Email: u.email,
        Role: u.role,
        Status: !u.banned ? 'Active' : 'Banned',
        RiskScore: u.riskScore,
      }));

      this.exportService.exportToCsv(dataToExport, 'rentoza_users_export');
    });
  }

  getRoleColor(role: string): string {
    switch (role) {
      case 'ADMIN':
        return 'warn';
      case 'OWNER':
        return 'primary';
      case 'USER':
        return 'accent';
      default:
        return '';
    }
  }

  banUser(user: AdminUserDto): void {
    const dialogRef = this.dialog.open(BanUserDialogComponent, {
      width: '480px',
      data: { email: user.email },
    });

    dialogRef.afterClosed().subscribe((reason) => {
      if (reason) {
        this.adminState.banUser(user.id, reason).subscribe({
          next: () => {
            this.notification.showSuccess('User banned successfully');
            this.loadUsers(this.searchTerm);
          },
          error: () => this.notification.showError('Failed to ban user'),
        });
      }
    });
  }

  unbanUser(user: AdminUserDto): void {
    const dialogRef = this.dialog.open(UnbanUserDialogComponent, {
      width: '480px',
      data: { email: user.email },
    });

    dialogRef.afterClosed().subscribe((confirmed) => {
      if (confirmed) {
        this.adminState.unbanUser(user.id).subscribe({
          next: () => {
            this.notification.showSuccess('User unbanned successfully');
            this.loadUsers(this.searchTerm);
          },
          error: () => this.notification.showError('Failed to unban user'),
        });
      }
    });
  }
}

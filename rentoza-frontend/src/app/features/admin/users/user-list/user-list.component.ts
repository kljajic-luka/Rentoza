import { Component, OnInit, ViewChild, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatSortModule, MatSort } from '@angular/material/sort';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { Router, RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { debounceTime, distinctUntilChanged, Subject, take } from 'rxjs';
import { AdminUserDto } from '../../../../core/services/admin-api.service';
import { AdminNotificationService } from '../../../../core/services/admin-notification.service';
import { ExportService } from '../../../../core/services/export.service';
import { BanUserDialogComponent } from '../../shared/dialogs/ban-user-dialog/ban-user-dialog.component';
import { AdminStateService } from '../../../../core/services/admin-state.service';

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

  displayedColumns: string[] = ['name', 'email', 'role', 'status', 'riskScore', 'actions'];
  users$ = this.adminState.users$;
  totalElements$ = this.adminState.totalUsers$;
  loading$ = this.adminState.loading$;

  // Search debouncing
  searchTerm = '';
  private searchSubject = new Subject<string>();

  // Pagination state
  pageIndex = 0;
  pageSize = 20;

  @ViewChild(MatPaginator) paginator!: MatPaginator;
  @ViewChild(MatSort) sort!: MatSort;

  ngOnInit() {
    this.loadUsers();

    // Debounce search input
    this.searchSubject.pipe(debounceTime(400), distinctUntilChanged()).subscribe((term) => {
      this.pageIndex = 0; // Reset to first page on search
      this.loadUsers(term);
    });
  }

  loadUsers(search?: string) {
    this.adminState.loadUsers(this.pageIndex, this.pageSize, search);
  }

  onSearch(term: string) {
    this.searchTerm = term;
    this.searchSubject.next(term);
  }

  onPageChange(event: PageEvent) {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.loadUsers(this.searchTerm);
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
    if (!confirm(`Unban ${user.email}?`)) return;

    this.adminState.unbanUser(user.id).subscribe({
      next: () => {
        this.notification.showSuccess('User unbanned successfully');
        this.loadUsers(this.searchTerm);
      },
      error: () => this.notification.showError('Failed to unban user'),
    });
  }
}

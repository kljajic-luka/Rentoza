import { Component, OnInit, inject } from '@angular/core';
import { CommonModule, Location } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTabsModule } from '@angular/material/tabs';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AdminNotificationService } from '../../../../core/services/admin-notification.service';
import { BanUserDialogComponent } from '../../shared/dialogs/ban-user-dialog/ban-user-dialog.component';
import { AdminStateService } from '../../../../core/services/admin-state.service';
import { Observable, take } from 'rxjs';
import { AdminUserDetailDto } from '../../../../core/services/admin-api.service';

@Component({
  selector: 'app-user-detail',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatTabsModule,
    MatChipsModule,
    MatDialogModule,
    MatSnackBarModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './user-detail.component.html',
  styleUrls: ['../../admin-shared.styles.scss', './user-detail.component.scss'],
})
export class UserDetailComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private location = inject(Location);
  private notification = inject(AdminNotificationService);
  private dialog = inject(MatDialog);
  private adminState = inject(AdminStateService);

  userId: number | null = null;
  user$: Observable<AdminUserDetailDto | null> = this.adminState.currentUser$;
  loading$: Observable<boolean> = this.adminState.loading$;
  error$: Observable<string | null> = this.adminState.error$;

  ngOnInit() {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.userId = +id;
      this.loadUserDetail();
    } else {
      this.goBack();
    }
  }

  loadUserDetail() {
    if (!this.userId) return;
    this.adminState.loadUserDetail(this.userId);
  }

  goBack() {
    this.location.back();
  }

  openBanDialog() {
    if (!this.userId) return;

    this.user$.pipe(take(1)).subscribe((user) => {
      const dialogRef = this.dialog.open(BanUserDialogComponent, {
        width: '500px',
        data: { email: user?.email ?? '' },
      });

      dialogRef.afterClosed().subscribe((reason) => {
        if (reason && this.userId) {
          this.adminState.banUser(this.userId, reason).subscribe({
            next: () => {
              this.notification.showSuccess('User banned successfully');
              this.loadUserDetail();
            },
            error: () => this.notification.showError('Failed to ban user'),
          });
        }
      });
    });
  }

  unbanUser() {
    if (!this.userId) return;

    this.user$.pipe(take(1)).subscribe((user) => {
      if (!user) return;
      if (confirm(`Are you sure you want to unban ${user.email}?`)) {
        this.adminState.unbanUser(this.userId!).subscribe({
          next: () => {
            this.notification.showSuccess('User unbanned successfully');
            this.loadUserDetail();
          },
          error: () => this.notification.showError('Failed to unban user'),
        });
      }
    });
  }

  getRiskColor(score?: number): string {
    if (!score) return 'bg-gray-500';
    if (score < 30) return 'bg-green-500';
    if (score < 70) return 'bg-yellow-500';
    return 'bg-red-500';
  }
}

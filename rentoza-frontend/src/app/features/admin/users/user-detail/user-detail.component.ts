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
import { RejectOwnerVerificationDialogComponent } from '../../shared/dialogs/reject-owner-verification-dialog/reject-owner-verification-dialog.component';
import { AdminStateService } from '../../../../core/services/admin-state.service';
import { AdminApiService, AdminUserDetailDto } from '../../../../core/services/admin-api.service';
import { Observable, take } from 'rxjs';
import { RiskScoreCardComponent, RiskFactor } from '../../shared/components/risk-score-card/risk-score-card.component';

// Renter Verification Components
import { RenterVerificationCardComponent } from '../../shared/components/renter-verification-card/renter-verification-card.component';
import { DocumentPreviewDialogComponent, DocumentPreviewDialogData } from '../../shared/dialogs/document-preview-dialog/document-preview-dialog.component';
import { ApproveRenterVerificationDialogComponent, ApproveRenterVerificationDialogData } from '../../shared/dialogs/approve-renter-verification-dialog/approve-renter-verification-dialog.component';
import { RejectRenterVerificationDialogComponent, RejectRenterVerificationDialogData, RejectRenterVerificationDialogResult } from '../../shared/dialogs/reject-renter-verification-dialog/reject-renter-verification-dialog.component';
import { RenterDocumentDto, RenterVerificationProfileDto } from '../../../../core/models/admin-renter-verification.model';

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
    RiskScoreCardComponent,
    RenterVerificationCardComponent,
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
  private adminApi = inject(AdminApiService);

  userId: number | null = null;
  user$: Observable<AdminUserDetailDto | null> = this.adminState.currentUser$;
  loading$: Observable<boolean> = this.adminState.loading$;
  error$: Observable<string | null> = this.adminState.error$;

  // Renter verification state
  renterVerificationProfile: RenterVerificationProfileDto | null = null;

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

  getOwnerVerificationBadge(status?: string): string {
    switch (status) {
      case 'PENDING_REVIEW':
        return 'badge badge-warn';
      case 'VERIFIED':
        return 'badge badge-success';
      default:
        return 'badge badge-neutral';
    }
  }

  getOwnerVerificationLabel(status?: string): string {
    switch (status) {
      case 'PENDING_REVIEW':
        return 'Pending review';
      case 'VERIFIED':
        return 'Verified';
      default:
        return 'Not submitted';
    }
  }

  approveOwnerVerification() {
    if (!this.userId) return;
    this.adminState.approveOwnerVerification(this.userId).subscribe({
      next: () => {
        this.notification.showSuccess('Owner verification approved');
        this.loadUserDetail();
      },
      error: () => this.notification.showError('Failed to approve owner verification'),
    });
  }

  rejectOwnerVerification() {
    if (!this.userId) return;

    this.user$.pipe(take(1)).subscribe((user) => {
      const displayName = user ? `${user.firstName} ${user.lastName}` : 'this user';
      const dialogRef = this.dialog.open(RejectOwnerVerificationDialogComponent, {
        width: '520px',
        data: { displayName },
      });

      dialogRef.afterClosed().subscribe((reason) => {
        if (reason) {
          this.adminState.rejectOwnerVerification(this.userId!, reason).subscribe({
            next: () => {
              this.notification.showSuccess('Owner verification rejected');
              this.loadUserDetail();
            },
            error: () => this.notification.showError('Failed to reject owner verification'),
          });
        }
      });
    });
  }

  // ========== RENTER VERIFICATION METHODS ==========

  /**
   * Open document preview dialog.
   */
  openRenterDocumentPreview(doc: RenterDocumentDto): void {
    this.dialog.open(DocumentPreviewDialogComponent, {
      width: '90vw',
      maxWidth: '1200px',
      maxHeight: '90vh',
      data: { document: doc } as DocumentPreviewDialogData,
    });
  }

  /**
   * Open approval confirmation dialog.
   */
  approveRenterVerification(): void {
    if (!this.userId) return;

    // First fetch the verification profile for the dialog
    this.adminApi.getRenterVerificationDetails(this.userId).pipe(take(1)).subscribe({
      next: (profile) => {
        const dialogRef = this.dialog.open(ApproveRenterVerificationDialogComponent, {
          width: '520px',
          data: { profile } as ApproveRenterVerificationDialogData,
        });

        dialogRef.afterClosed().subscribe((result) => {
          if (result && this.userId) {
            this.adminState.approveRenterVerification(this.userId, result.notes).subscribe({
              next: () => {
                this.notification.showSuccess('Verifikacija vozačke dozvole je odobrena');
                this.loadUserDetail();
              },
              error: () => this.notification.showError('Greška pri odobravanju verifikacije'),
            });
          }
        });
      },
      error: () => this.notification.showError('Greška pri učitavanju podataka verifikacije'),
    });
  }

  /**
   * Open rejection dialog.
   */
  rejectRenterVerification(): void {
    if (!this.userId) return;

    this.user$.pipe(take(1)).subscribe((user) => {
      const displayName = user ? `${user.firstName} ${user.lastName}` : 'ovog korisnika';
      const dialogRef = this.dialog.open(RejectRenterVerificationDialogComponent, {
        width: '520px',
        data: { displayName } as RejectRenterVerificationDialogData,
      });

      dialogRef.afterClosed().subscribe((result: RejectRenterVerificationDialogResult | undefined) => {
        if (result && this.userId) {
          this.adminState.rejectRenterVerification(this.userId, result.reason).subscribe({
            next: () => {
              this.notification.showSuccess('Verifikacija vozačke dozvole je odbijena');
              this.loadUserDetail();
            },
            error: () => this.notification.showError('Greška pri odbijanju verifikacije'),
          });
        }
      });
    });
  }

  // ========== RISK ASSESSMENT HELPERS ==========

  getRiskLevel(score?: number): string {
    if (!score) return 'LOW';
    if (score <= 20) return 'LOW';
    if (score <= 50) return 'MEDIUM';
    if (score <= 80) return 'HIGH';
    return 'CRITICAL';
  }

  getRiskFactors(user: AdminUserDetailDto): RiskFactor[] {
    const factors: RiskFactor[] = [];

    // TODO: Replace with actual risk factors from backend API
    // For now, generate factors based on available user data

    // Compliance factors
    if (user.banned) {
      factors.push({
        name: 'Account Banned',
        points: 40,
        category: 'Compliance',
        isNegative: false
      });
    }

    // Account age
    // TODO: Backend to add 'createdAt' field to AdminUserDetailDto
    const accountAgeDays = this.getAccountAgeDays(user.ownerVerificationSubmittedAt);
    if (accountAgeDays < 7) {
      factors.push({
        name: 'Brand new account (< 7 days)',
        points: 20,
        category: 'Account',
        isNegative: false
      });
    } else if (accountAgeDays < 30) {
      factors.push({
        name: 'New account (7-30 days)',
        points: 10,
        category: 'Account',
        isNegative: false
      });
    }

    // Identity verification
    if (!user.phone) {
      factors.push({
        name: 'Phone not verified',
        points: 10,
        category: 'Identity',
        isNegative: false
      });
    }

    // Behavioral factors
    const totalBookings = (user.totalBookings || 0);
    const cancelledBookings = (user.cancelledBookings || 0);
    
    if (totalBookings > 0) {
      const cancellationRate = cancelledBookings / totalBookings;
      if (cancellationRate > 0.30) {
        factors.push({
          name: `High cancellation rate (${Math.round(cancellationRate * 100)}%)`,
          points: 15,
          category: 'Behavioral',
          isNegative: false
        });
      }
    }

    // Positive factors
    const completedBookings = (user.completedBookings || 0);
    if (completedBookings > 10) {
      factors.push({
        name: `Good booking history (${completedBookings} trips)`,
        points: Math.min(10, Math.floor(completedBookings / 2)),
        category: 'Behavioral',
        isNegative: true // Negative = good (reduces risk)
      });
    }

    return factors;
  }

  private getAccountAgeDays(createdAt?: string): number {
    if (!createdAt) return 999;
    const created = new Date(createdAt);
    const now = new Date();
    const diffTime = Math.abs(now.getTime() - created.getTime());
    return Math.ceil(diffTime / (1000 * 60 * 60 * 24));
  }
}

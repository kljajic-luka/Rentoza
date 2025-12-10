import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  FormsModule,
  ReactiveFormsModule,
  FormBuilder,
  FormGroup,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

// Angular Material
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import {
  AdminApiService,
  AdminDisputeDetailDto,
  DisputeResolutionRequest,
  EscalateDisputeRequest,
} from '../../../../core/services/admin-api.service';

/**
 * Dispute Detail Component - Admin view for resolving damage claim disputes.
 *
 * Features:
 * - Full dispute details with evidence photos
 * - Timeline of events (claim, guest response, admin review)
 * - Resolution form (approve/reject/escalate)
 * - Photo evidence gallery
 * - Responsive layout
 */
@Component({
  selector: 'app-dispute-detail',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatDividerModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatDialogModule,
    MatSnackBarModule,
    MatTooltipModule,
  ],
  templateUrl: './dispute-detail.component.html',
  styleUrls: ['./dispute-detail.component.scss'],
})
export class DisputeDetailComponent implements OnInit {
  private adminApi = inject(AdminApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private fb = inject(FormBuilder);
  private snackBar = inject(MatSnackBar);

  // State
  dispute = signal<AdminDisputeDetailDto | null>(null);
  loading = signal(false);
  submitting = signal(false);
  disputeId = signal<number>(0);

  // Forms
  resolutionForm!: FormGroup;
  escalateForm!: FormGroup;

  // UI State
  showResolutionForm = signal(false);
  showEscalateForm = signal(false);

  // Computed
  canResolve = computed(() => {
    const d = this.dispute();
    return d && ['DISPUTED', 'ESCALATED'].includes(d.status);
  });

  hasEvidence = computed(() => {
    const d = this.dispute();
    return d && (d.checkinPhotoIds || d.checkoutPhotoIds || d.evidencePhotoIds);
  });

  ngOnInit(): void {
    this.disputeId.set(+this.route.snapshot.paramMap.get('id')!);
    this.initForms();
    this.loadDispute();
  }

  initForms(): void {
    this.resolutionForm = this.fb.group({
      decision: ['APPROVED', Validators.required],
      approvedAmount: [null],
      notes: ['', [Validators.required, Validators.minLength(10)]],
      rejectionReason: [''],
    });

    this.escalateForm = this.fb.group({
      reason: ['', [Validators.required, Validators.minLength(10)]],
    });

    // Set approvedAmount based on decision
    this.resolutionForm.get('decision')?.valueChanges.subscribe((decision) => {
      const amountControl = this.resolutionForm.get('approvedAmount');
      if (decision === 'APPROVED' || decision === 'PARTIAL') {
        amountControl?.setValidators([Validators.required, Validators.min(0)]);
        if (decision === 'APPROVED' && this.dispute()) {
          amountControl?.setValue(this.dispute()!.claimedAmount);
        }
      } else {
        amountControl?.clearValidators();
        amountControl?.setValue(null);
      }
      amountControl?.updateValueAndValidity();
    });
  }

  loadDispute(): void {
    this.loading.set(true);
    this.adminApi.getDisputeDetail(this.disputeId()).subscribe({
      next: (dispute) => {
        this.dispute.set(dispute);
        this.loading.set(false);
      },
      error: (error) => {
        console.error('Failed to load dispute:', error);
        this.snackBar.open('Failed to load dispute', 'Close', { duration: 3000 });
        this.loading.set(false);
      },
    });
  }

  toggleResolutionForm(): void {
    this.showResolutionForm.update((v) => !v);
    this.showEscalateForm.set(false);
  }

  toggleEscalateForm(): void {
    this.showEscalateForm.update((v) => !v);
    this.showResolutionForm.set(false);
  }

  submitResolution(): void {
    if (this.resolutionForm.invalid) {
      this.resolutionForm.markAllAsTouched();
      return;
    }

    this.submitting.set(true);
    const request: DisputeResolutionRequest = this.resolutionForm.value;

    this.adminApi.resolveDispute(this.disputeId(), request).subscribe({
      next: () => {
        this.snackBar.open('Dispute resolved successfully', 'Close', { duration: 3000 });
        this.submitting.set(false);
        this.router.navigate(['/admin/disputes']);
      },
      error: (error) => {
        console.error('Failed to resolve dispute:', error);
        this.snackBar.open('Failed to resolve dispute: ' + error.message, 'Close', {
          duration: 5000,
        });
        this.submitting.set(false);
      },
    });
  }

  submitEscalation(): void {
    if (this.escalateForm.invalid) {
      this.escalateForm.markAllAsTouched();
      return;
    }

    this.submitting.set(true);
    const request: EscalateDisputeRequest = this.escalateForm.value;

    this.adminApi.escalateDispute(this.disputeId(), request).subscribe({
      next: () => {
        this.snackBar.open('Dispute escalated successfully', 'Close', { duration: 3000 });
        this.submitting.set(false);
        this.loadDispute();
        this.showEscalateForm.set(false);
      },
      error: (error) => {
        console.error('Failed to escalate dispute:', error);
        this.snackBar.open('Failed to escalate dispute: ' + error.message, 'Close', {
          duration: 5000,
        });
        this.submitting.set(false);
      },
    });
  }

  getStatusColor(status: string): string {
    const colorMap: Record<string, string> = {
      DISPUTED: 'warn',
      ESCALATED: 'accent',
      ADMIN_APPROVED: 'primary',
      ADMIN_REJECTED: '',
      REQUIRES_MANUAL_REVIEW: 'warn',
    };
    return colorMap[status] || '';
  }

  formatCurrency(amount: number): string {
    return `R ${amount.toFixed(2)}`;
  }

  formatDate(dateString: string | undefined): string {
    if (!dateString) return 'N/A';
    return new Date(dateString).toLocaleString('en-ZA', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  }

  goBack(): void {
    this.router.navigate(['/admin/disputes']);
  }
}

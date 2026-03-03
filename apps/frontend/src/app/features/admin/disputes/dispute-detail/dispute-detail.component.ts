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
import { MatTooltipModule } from '@angular/material/tooltip';

import {
  AdminApiService,
  AdminDisputeDetailDto,
  DisputeResolutionRequest,
  EscalateDisputeRequest,
} from '../../../../core/services/admin-api.service';
import { AdminNotificationService } from '../../../../core/services/admin-notification.service';
import {
  PhotoGalleryDialogComponent,
  PhotoGroup,
} from '../../shared/dialogs/photo-gallery-dialog/photo-gallery-dialog.component';

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
  private snackBar = inject(AdminNotificationService);
  private dialog = inject(MatDialog);

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

  // Resolution templates
  readonly resolutionTemplates = [
    {
      label: 'Full refund — guest fault not established',
      text: 'After reviewing the evidence, damage could not be attributed to the guest. Full deposit refund issued to {guestName}.',
    },
    {
      label: 'Partial deduction — minor damage confirmed',
      text: 'Evidence confirms minor damage during the rental period. A deduction of {amount} RSD has been applied from the deposit. Remaining balance refunded to {guestName}.',
    },
    {
      label: 'Full deduction — significant damage confirmed',
      text: 'Evidence clearly shows significant damage during the rental period. The full claimed amount of {amount} RSD has been retained and disbursed to {hostName} for repairs.',
    },
    {
      label: 'Dispute rejected — pre-existing damage',
      text: 'Check-in photos confirm the reported damage existed prior to this rental. No deduction applied. Full deposit refunded to {guestName}.',
    },
  ];

  // Computed
  canResolve = computed(() => {
    const d = this.dispute();
    return (
      d &&
      [
        'DISPUTED',
        'ESCALATED',
        'CHECKOUT_GUEST_DISPUTED',
        'CHECKOUT_TIMEOUT_ESCALATED',
        'CHECKOUT_PENDING',
      ].includes(d.status)
    );
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
        this.snackBar.showError('Failed to load dispute');
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

  applyTemplate(template: { label: string; text: string }): void {
    const d = this.dispute();
    if (!d) return;
    const amount = this.resolutionForm.get('approvedAmount')?.value;
    let text = template.text;
    text = text.replace('{guestName}', d.guestName || 'the guest');
    text = text.replace('{hostName}', d.hostName || 'the host');
    text = text.replace('{amount}', amount != null ? amount.toLocaleString('sr-RS') : '___');
    this.resolutionForm.get('notes')?.setValue(text);
  }

  /** Check if this is a checkout-stage dispute (uses different resolution endpoint). */
  isCheckoutDispute = computed(() => {
    const d = this.dispute();
    return (
      d &&
      [
        'CHECKOUT_PENDING',
        'CHECKOUT_GUEST_DISPUTED',
        'CHECKOUT_TIMEOUT_ESCALATED',
        'CHECKOUT_GUEST_ACCEPTED',
        'CHECKOUT_ADMIN_APPROVED',
        'CHECKOUT_ADMIN_REJECTED',
      ].includes(d.status)
    );
  });

  submitResolution(): void {
    if (this.resolutionForm.invalid) {
      this.resolutionForm.markAllAsTouched();
      return;
    }

    this.submitting.set(true);

    // Route to correct endpoint based on dispute type
    if (this.isCheckoutDispute()) {
      const formVal = this.resolutionForm.value;
      const checkoutDecisionMap: Record<string, string> = {
        APPROVED: 'APPROVE',
        REJECTED: 'REJECT',
        PARTIAL: 'PARTIAL',
        MEDIATED: 'PARTIAL',
      };
      const checkoutRequest = {
        decision: checkoutDecisionMap[formVal.decision] || 'APPROVE',
        approvedAmountRsd: formVal.approvedAmount,
        resolutionNotes: formVal.notes,
        notifyParties: true,
      };
      this.adminApi.resolveCheckoutDispute(this.disputeId(), checkoutRequest as any).subscribe({
        next: () => {
          this.snackBar.showSuccess('Checkout dispute resolved successfully');
          this.submitting.set(false);
          this.router.navigate(['/admin/disputes']);
        },
        error: (error: any) => {
          console.error('Failed to resolve checkout dispute:', error);
          this.snackBar.showError(
            'Failed to resolve dispute: ' + (error?.error?.message || error.message),
          );
          this.submitting.set(false);
        },
      });
    } else {
      const request: DisputeResolutionRequest = this.resolutionForm.value;
      this.adminApi.resolveDispute(this.disputeId(), request).subscribe({
        next: () => {
          this.snackBar.showSuccess('Dispute resolved successfully');
          this.submitting.set(false);
          this.router.navigate(['/admin/disputes']);
        },
        error: (error) => {
          console.error('Failed to resolve dispute:', error);
          this.snackBar.showError('Failed to resolve dispute: ' + error.message);
          this.submitting.set(false);
        },
      });
    }
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
        this.snackBar.showSuccess('Dispute escalated successfully');
        this.submitting.set(false);
        this.loadDispute();
        this.showEscalateForm.set(false);
      },
      error: (error) => {
        console.error('Failed to escalate dispute:', error);
        this.snackBar.showError('Failed to escalate dispute: ' + error.message);
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
    const formatted = amount.toLocaleString('sr-RS', {
      minimumFractionDigits: 0,
      maximumFractionDigits: 0,
    });
    return `${formatted} RSD`;
  }

  formatDate(dateString: string | undefined): string {
    if (!dateString) return 'N/A';
    return new Date(dateString).toLocaleString('sr-RS', {
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

  parsePhotoIds(ids: string | undefined): string[] {
    if (!ids) return [];
    return ids.split(',').map((id) => id.trim()).filter(Boolean);
  }

  getPhotoUrl(bookingId: number, phase: string, photoId: string): string {
    return `/api/bookings/${bookingId}/${phase}/photos/${photoId}`;
  }

  openEvidenceGallery(): void {
    const d = this.dispute();
    if (!d) return;

    const groups: PhotoGroup[] = [];

    const checkinIds = this.parsePhotoIds(d.checkinPhotoIds);
    if (checkinIds.length > 0) {
      groups.push({
        label: 'Check-in Photos',
        photoUrls: checkinIds.map((id) => this.getPhotoUrl(d.bookingId, 'checkin', id)),
      });
    }

    const checkoutIds = this.parsePhotoIds(d.checkoutPhotoIds);
    if (checkoutIds.length > 0) {
      groups.push({
        label: 'Check-out Photos',
        photoUrls: checkoutIds.map((id) => this.getPhotoUrl(d.bookingId, 'checkout', id)),
      });
    }

    const evidenceIds = this.parsePhotoIds(d.evidencePhotoIds);
    if (evidenceIds.length > 0) {
      groups.push({
        label: 'Additional Evidence',
        photoUrls: evidenceIds.map((id) => this.getPhotoUrl(d.bookingId, 'evidence', id)),
      });
    }

    this.dialog.open(PhotoGalleryDialogComponent, {
      width: '900px',
      maxHeight: '90vh',
      data: { title: `Evidence — Dispute #${d.id}`, photoGroups: groups },
    });
  }
}
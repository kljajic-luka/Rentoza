import { Component, OnInit, OnDestroy, inject, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import {
  Subject,
  combineLatest,
  of,
  merge,
  EMPTY,
  takeUntil,
  filter,
  map,
  distinctUntilChanged,
  tap,
  switchMap,
  catchError,
  finalize,
  withLatestFrom,
  shareReplay,
  startWith,
} from 'rxjs';

import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatDividerModule } from '@angular/material/divider';
import { FormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';

import {
  AdminApiService,
  AdminCarReviewDetailDto,
  DocumentReviewDto,
} from '../../../../core/services/admin-api.service';
import { AdminNotificationService } from '../../../../core/services/admin-notification.service';
import { normalizeMediaUrlArray } from '@shared/utils/media-url.util';
import { ConfirmDialogComponent } from '../../shared/dialogs/confirm-dialog/confirm-dialog.component';

/**
 * Car review page for admin document verification workflow.
 *
 * Displays:
 * - Car overview with photos
 * - All documents with verification status
 * - Owner identity verification status
 * - Approval state and blockers
 * - Actions to verify/reject documents
 * - Buttons to approve/reject car
 */
@Component({
  selector: 'app-car-review',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatTooltipModule,
    MatExpansionModule,
    MatDividerModule,
  ],
  styleUrls: ['../../admin-shared.styles.scss', './car-review.component.scss'],
  template: `
    <div class="admin-page">
      <!-- Header -->
      <div class="page-header">
        <button mat-icon-button (click)="goBack()" matTooltip="Back to cars">
          <mat-icon>arrow_back</mat-icon>
        </button>
        <div>
          <h1 class="page-title" *ngIf="!loading && carData">
            {{ carData.brand }} {{ carData.model }} ({{ carData.year }})
          </h1>
          <p class="page-subtitle">Document verification & approval workflow</p>
        </div>
      </div>

      <!-- Loading state -->
      <div *ngIf="loading" class="loading-block">
        <mat-progress-spinner diameter="48"></mat-progress-spinner>
        <p>Loading car review details...</p>
      </div>

      <!-- Error state -->
      <div *ngIf="error && !loading" class="error-block">
        <mat-icon>error_outline</mat-icon>
        <p>{{ error }}</p>
        <button mat-raised-button color="primary" (click)="goBack()">Back to cars</button>
      </div>

      <!-- Main content -->
      <div *ngIf="!loading && !error && carData" class="review-grid">
        <!-- LEFT COLUMN: Car Overview -->
        <div class="review-main">
          <!-- Car Photos -->
          <mat-card class="surface-card surface-wide">
            <div class="car-photos">
              <div class="photo-main" [style.backgroundImage]="'url(' + currentPhotoUrl + ')'">
                <div class="photo-overlay" *ngIf="!currentPhotoUrl">
                  <mat-icon>directions_car</mat-icon>
                </div>
              </div>
              <div class="photo-thumbs" *ngIf="carData.imageUrls && carData.imageUrls.length > 1">
                <div
                  *ngFor="let photo of carData.imageUrls; let i = index"
                  class="thumb"
                  [class.active]="i === currentPhotoIndex"
                  [style.backgroundImage]="'url(' + photo + ')'"
                  (click)="selectPhoto(i)"
                ></div>
              </div>
            </div>
          </mat-card>

          <!-- Car Overview Card -->
          <mat-card class="surface-card surface-roomy">
            <mat-card-header>
              <mat-card-title>Car Overview</mat-card-title>
            </mat-card-header>
            <mat-card-content>
              <div class="spec-grid">
                <div class="spec-item">
                  <div class="mini-label">Brand</div>
                  <div class="strong">{{ carData.brand }} {{ carData.model }}</div>
                </div>
                <div class="spec-item">
                  <div class="mini-label">Year</div>
                  <div class="strong">{{ carData.year }}</div>
                </div>
                <div class="spec-item">
                  <div class="mini-label">Location</div>
                  <div class="strong">{{ carData.location }}</div>
                </div>
              </div>

              <mat-divider class="my-3"></mat-divider>

              <!-- Key Dates Section -->
              <div class="dates-section">
                <h3 class="section-title">Compliance Dates</h3>
                <div class="dates-grid">
                  <!-- Registration Expiry -->
                  <div class="date-item">
                    <div class="mini-label">Registration Expires</div>
                    <div
                      class="strong date-value"
                      [class.date-expired]="
                        carData.registrationExpiryDate && isExpired(carData.registrationExpiryDate)
                      "
                      [class.date-valid]="
                        carData.registrationExpiryDate && !isExpired(carData.registrationExpiryDate)
                      "
                    >
                      {{ carData.registrationExpiryDate || 'Not set' }}
                      <mat-icon
                        *ngIf="
                          carData.registrationExpiryDate &&
                          !isExpired(carData.registrationExpiryDate)
                        "
                        class="success-icon"
                      >
                        check_circle
                      </mat-icon>
                      <mat-icon
                        *ngIf="
                          carData.registrationExpiryDate &&
                          isExpired(carData.registrationExpiryDate)
                        "
                        class="error-icon"
                      >
                        cancel
                      </mat-icon>
                    </div>
                  </div>

                  <!-- Technical Inspection Expiry -->
                  <div class="date-item">
                    <div class="mini-label">Tech Inspection Expires</div>
                    <div
                      class="strong date-value"
                      [class.date-expired]="
                        carData.technicalInspectionExpiryDate &&
                        isExpired(carData.technicalInspectionExpiryDate)
                      "
                      [class.date-valid]="
                        carData.technicalInspectionExpiryDate &&
                        !isExpired(carData.technicalInspectionExpiryDate)
                      "
                    >
                      {{ carData.technicalInspectionExpiryDate || 'Not set' }}
                      <mat-icon
                        *ngIf="
                          carData.technicalInspectionExpiryDate &&
                          !isExpired(carData.technicalInspectionExpiryDate)
                        "
                        class="success-icon"
                      >
                        check_circle
                      </mat-icon>
                      <mat-icon
                        *ngIf="
                          carData.technicalInspectionExpiryDate &&
                          isExpired(carData.technicalInspectionExpiryDate)
                        "
                        class="error-icon"
                      >
                        cancel
                      </mat-icon>
                    </div>
                  </div>

                  <!-- Insurance Expiry -->
                  <div class="date-item">
                    <div class="mini-label">Insurance Expires</div>
                    <div
                      class="strong date-value"
                      [class.date-expired]="
                        carData.insuranceExpiryDate && isExpired(carData.insuranceExpiryDate)
                      "
                      [class.date-valid]="
                        carData.insuranceExpiryDate && !isExpired(carData.insuranceExpiryDate)
                      "
                    >
                      {{ carData.insuranceExpiryDate || 'Not set' }}
                      <mat-icon
                        *ngIf="
                          carData.insuranceExpiryDate && !isExpired(carData.insuranceExpiryDate)
                        "
                        class="success-icon"
                      >
                        check_circle
                      </mat-icon>
                      <mat-icon
                        *ngIf="
                          carData.insuranceExpiryDate && isExpired(carData.insuranceExpiryDate)
                        "
                        class="error-icon"
                      >
                        cancel
                      </mat-icon>
                    </div>
                  </div>
                </div>
              </div>
            </mat-card-content>
          </mat-card>

          <!-- Owner Section -->
          <mat-card class="surface-card surface-roomy">
            <mat-card-header>
              <mat-icon mat-card-avatar>person</mat-icon>
              <mat-card-title>Owner Information</mat-card-title>
            </mat-card-header>
            <mat-card-content class="stack">
              <div class="owner-row">
                <span class="mini-label">Name:</span>
                <span class="strong">{{ carData.ownerName }}</span>
              </div>
              <div class="owner-row">
                <span class="mini-label">Email:</span>
                <span class="strong">{{ carData.ownerEmail }}</span>
              </div>
              <div class="owner-row">
                <span class="mini-label">Owner Type:</span>
                <span class="strong">{{ carData.ownerType }}</span>
              </div>
              <div class="owner-row">
                <span class="mini-label">Identity Verified:</span>
                <span
                  class="badge"
                  [ngClass]="carData.ownerIdentityVerified ? 'badge-success' : 'badge-warn'"
                >
                  {{ carData.ownerIdentityVerified ? 'Yes' : 'No - BLOCKING' }}
                </span>
              </div>
            </mat-card-content>
          </mat-card>

          <!-- Documents Section -->
          <mat-card class="surface-card surface-wide">
            <mat-card-header>
              <mat-card-title>Compliance Documents</mat-card-title>
              <div class="doc-summary">
                {{ getDocumentSummary() }}
              </div>
            </mat-card-header>
            <mat-card-content>
              <div class="documents-list">
                <div *ngFor="let doc of carData.documents" class="document-card">
                  <!-- Document Header -->
                  <div class="document-header">
                    <div class="document-info">
                      <h4 class="document-type">{{ getDocumentName(doc.type) }}</h4>
                      <span
                        class="badge status-badge"
                        [ngClass]="getBadgeClassForStatus(doc.status)"
                      >
                        {{ getStatusLabel(doc.status) }}
                      </span>
                      <span
                        class="badge expiry-badge"
                        *ngIf="doc.expiryDate && !doc.isExpired"
                        [ngClass]="doc.daysUntilExpiry <= 30 ? 'badge-warn' : 'badge-success'"
                      >
                        {{ doc.daysUntilExpiry }} days
                      </span>
                      <span class="badge badge-error" *ngIf="doc.isExpired"> EXPIRED </span>
                    </div>
                    <button
                      mat-icon-button
                      (click)="viewDocument(doc)"
                      matTooltip="View document"
                      color="primary"
                    >
                      <mat-icon>visibility</mat-icon>
                    </button>
                  </div>

                  <!-- Document Details -->
                  <div class="document-details">
                    <div class="detail-row">
                      <span class="label">Uploaded:</span>
                      <span>{{ doc.uploadDate | date : 'short' }}</span>
                    </div>
                    <div class="detail-row" *ngIf="doc.expiryDate">
                      <span class="label">Expires:</span>
                      <span>{{ doc.expiryDate }}</span>
                    </div>
                    <div class="detail-row" *ngIf="doc.verifiedByName">
                      <span class="label">Verified by:</span>
                      <span>{{ doc.verifiedByName }} on {{ doc.verifiedAt | date : 'short' }}</span>
                    </div>
                    <div class="detail-row" *ngIf="doc.rejectionReason">
                      <span class="label">Rejection Reason:</span>
                      <span class="error-text">{{ doc.rejectionReason }}</span>
                    </div>
                  </div>

                  <!-- Document Actions (only for PENDING/REJECTED) -->
                  <div
                    class="document-actions"
                    *ngIf="doc.status === 'PENDING' || doc.status === 'REJECTED'"
                  >
                    <button
                      mat-stroked-button
                      color="accent"
                      (click)="verifyDocument(doc)"
                      [disabled]="isProcessing || doc.isExpired"
                      matTooltip="Mark as verified"
                    >
                      <mat-icon>check</mat-icon> Verify
                    </button>
                    <button
                      mat-stroked-button
                      color="warn"
                      (click)="openRejectDialog(doc)"
                      [disabled]="isProcessing"
                      matTooltip="Mark as rejected (requires reason)"
                    >
                      <mat-icon>close</mat-icon> Reject
                    </button>
                  </div>
                </div>
              </div>
            </mat-card-content>
          </mat-card>
        </div>

        <!-- RIGHT COLUMN: Approval Summary & Actions -->
        <div class="review-side">
          <!-- Approval State Summary -->
          <mat-card class="surface-card surface-wide sticky-card">
            <mat-card-header>
              <mat-card-title>Approval Status</mat-card-title>
            </mat-card-header>
            <mat-card-content>
              <!-- Can Approve? -->
              <div class="approval-summary">
                <div
                  class="approval-indicator"
                  [class.can-approve]="carData.approvalState.canApprove"
                  [class.cannot-approve]="!carData.approvalState.canApprove"
                >
                  <mat-icon>{{
                    carData.approvalState.canApprove ? 'check_circle' : 'block'
                  }}</mat-icon>
                  <span>{{
                    carData.approvalState.canApprove
                      ? 'Ready to Approve'
                      : 'Cannot Approve - See Issues Below'
                  }}</span>
                </div>

                <!-- Issues List -->
                <div class="issues-list">
                  <!-- Owner Verification -->
                  <div class="issue-item" [class.resolved]="carData.approvalState.ownerVerified">
                    <mat-icon class="issue-icon">{{
                      carData.approvalState.ownerVerified ? 'check_circle' : 'cancel'
                    }}</mat-icon>
                    <span
                      >Owner Identity:
                      {{ carData.approvalState.ownerVerified ? 'VERIFIED' : 'NOT VERIFIED' }}</span
                    >
                  </div>

                  <!-- Registration Valid -->
                  <div
                    class="issue-item"
                    [class.resolved]="carData.approvalState.registrationValid"
                  >
                    <mat-icon class="issue-icon">{{
                      carData.approvalState.registrationValid ? 'check_circle' : 'cancel'
                    }}</mat-icon>
                    <span
                      >Registration:
                      {{
                        carData.approvalState.registrationValid ? 'Valid' : 'EXPIRED/INVALID'
                      }}</span
                    >
                  </div>

                  <!-- Tech Inspection Valid -->
                  <div
                    class="issue-item"
                    [class.resolved]="carData.approvalState.techInspectionValid"
                  >
                    <mat-icon class="issue-icon">{{
                      carData.approvalState.techInspectionValid ? 'check_circle' : 'cancel'
                    }}</mat-icon>
                    <span
                      >Tech Inspection:
                      {{
                        carData.approvalState.techInspectionValid ? 'Valid' : 'EXPIRED/INVALID'
                      }}</span
                    >
                  </div>

                  <!-- Insurance Valid -->
                  <div class="issue-item" [class.resolved]="carData.approvalState.insuranceValid">
                    <mat-icon class="issue-icon">{{
                      carData.approvalState.insuranceValid ? 'check_circle' : 'cancel'
                    }}</mat-icon>
                    <span
                      >Insurance:
                      {{ carData.approvalState.insuranceValid ? 'Valid' : 'EXPIRED/INVALID' }}</span
                    >
                  </div>

                  <!-- Unverified Documents -->
                  <div
                    class="issue-item"
                    [class.resolved]="carData.approvalState.unverifiedDocuments.length === 0"
                  >
                    <mat-icon class="issue-icon">{{
                      carData.approvalState.unverifiedDocuments.length === 0
                        ? 'check_circle'
                        : 'cancel'
                    }}</mat-icon>
                    <span>
                      Documents Verified:
                      {{
                        carData.approvalState.unverifiedDocuments.length === 0
                          ? 'All'
                          : carData.approvalState.unverifiedDocuments.length + ' unverified'
                      }}
                    </span>
                  </div>
                </div>
              </div>

              <mat-divider class="my-3"></mat-divider>

              <!-- Approve Button -->
              <button
                mat-flat-button
                color="primary"
                class="full-width"
                (click)="approveCar()"
                [disabled]="!carData.approvalState.canApprove || isProcessing"
                matTooltip="Approve car for rental - requires all documents verified & dates valid"
              >
                <mat-icon>check</mat-icon>
                {{ isProcessing ? 'Processing...' : 'Approve Car' }}
              </button>

              <!-- Reject Car Section -->
              <mat-divider class="my-3"></mat-divider>

              <h4 class="section-title">Reject Car</h4>
              <div class="reject-section">
                <mat-form-field appearance="fill" class="full-width">
                  <mat-label>Rejection Reason (min 20 chars)</mat-label>
                  <textarea
                    matInput
                    [(ngModel)]="rejectionReason"
                    [disabled]="isProcessing"
                    rows="4"
                    placeholder="Explain why you're rejecting this car..."
                  ></textarea>
                </mat-form-field>

                <button
                  mat-stroked-button
                  color="warn"
                  class="full-width"
                  (click)="rejectCar()"
                  [disabled]="
                    rejectionReason === null || rejectionReason.length < 20 || isProcessing
                  "
                  matTooltip="Reject car - reason required (min 20 chars)"
                >
                  <mat-icon>close</mat-icon>
                  {{ isProcessing ? 'Processing...' : 'Reject Car' }}
                </button>

                <div class="reason-helper" *ngIf="rejectionReason">
                  <small>
                    {{ rejectionReason.length }}/20 chars
                    <span *ngIf="rejectionReason.length >= 20" class="success"> ✓ Valid </span>
                  </small>
                </div>
              </div>
            </mat-card-content>
          </mat-card>
        </div>
      </div>
    </div>
  `,
})
export class CarReviewComponent implements OnInit, OnDestroy {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private adminApi = inject(AdminApiService);
  private notification = inject(AdminNotificationService);
  private dialog = inject(MatDialog);
  private cdr = inject(ChangeDetectorRef);

  carId: number | null = null;
  carData: AdminCarReviewDetailDto | null = null;
  loading = true;
  error: string | null = null;
  isProcessing = false;
  rejectionReason: string | null = null;

  currentPhotoIndex = 0;
  get currentPhotoUrl(): string {
    if (!this.carData?.imageUrls || this.carData.imageUrls.length === 0) {
      return '';
    }
    return this.carData.imageUrls[this.currentPhotoIndex];
  }

  private destroy$ = new Subject<void>();
  private refresh$ = new Subject<void>();

  ngOnInit() {
    // Resolve the car id from the current route params (with a parent fallback),
    // and keep it reactive so in-place navigations update the view.
    const parentParamMap$ = this.route.parent
      ? this.route.parent.paramMap.pipe(startWith(null))
      : of(null);

    const routeCarId$ = combineLatest([this.route.paramMap, parentParamMap$]).pipe(
      map(([childParams, parentParams]) => {
        return (
          childParams.get('carId') ??
          childParams.get('id') ??
          parentParams?.get('carId') ??
          parentParams?.get('id')
        );
      }),
      map((raw) => {
        const trimmed = (raw ?? '').trim();
        const parsed = Number.parseInt(trimmed, 10);
        return Number.isFinite(parsed) ? parsed : null;
      }),
      distinctUntilChanged(),
      shareReplay({ bufferSize: 1, refCount: true }),
      takeUntil(this.destroy$)
    );

    // Keep component state in sync with the URL (including the error case).
    const validCarId$ = routeCarId$.pipe(
      tap((carId) => {
        if (!carId) {
          this.carId = null;
          this.carData = null;
          this.error = 'Car ID not found in URL';
          this.loading = false;
          return;
        }
        this.carId = carId;
      }),
      filter((carId): carId is number => carId !== null && Number.isFinite(carId) && carId > 0),
      shareReplay({ bufferSize: 1, refCount: true }),
      takeUntil(this.destroy$)
    );

    // Centralized loader: triggers on route-id changes and on manual refresh(),
    // and cancels in-flight requests when a newer trigger happens.
    const loadTriggerCarId$ = merge(
      validCarId$,
      this.refresh$.pipe(
        withLatestFrom(validCarId$),
        map(([, carId]) => carId)
      )
    );

    loadTriggerCarId$
      .pipe(
        tap(() => {
          this.loading = true;
          this.error = null;
          this.cdr.markForCheck();
        }),
        switchMap((carId) =>
          this.adminApi.getCarReviewDetail(carId).pipe(
            catchError((err) => {
              console.error('Failed to load car review details', err);
              this.error = 'Failed to load car review details. Please try again.';
              this.cdr.markForCheck();
              return EMPTY;
            }),
            finalize(() => {
              this.loading = false;
              this.cdr.markForCheck();
            })
          )
        ),
        takeUntil(this.destroy$)
      )
      .subscribe((data) => {
        this.carData = {
          ...data,
          imageUrls: normalizeMediaUrlArray(data.imageUrls),
        };
        this.currentPhotoIndex = 0;
        this.cdr.markForCheck();
      });
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadCarReviewDetail() {
    if (!this.carId) return;
    this.refresh$.next();
  }

  selectPhoto(index: number) {
    this.currentPhotoIndex = index;
  }

  goBack() {
    this.router.navigate(['/admin/cars']);
  }

  isExpired(dateString: string): boolean {
    if (!dateString) return false;
    const date = new Date(dateString);
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    return date < today;
  }

  getDocumentName(type: string): string {
    const names: { [key: string]: string } = {
      REGISTRATION: 'Vehicle Registration (Saobraćajna dozvola)',
      TECHNICAL_INSPECTION: 'Technical Inspection (Tehnički pregled)',
      LIABILITY_INSURANCE: 'Liability Insurance (Polisa Autoodgovornosti)',
      AUTHORIZATION: 'Authorization (Ovlašćenje)',
    };
    return names[type] || type;
  }

  getStatusLabel(status: string): string {
    const labels: { [key: string]: string } = {
      PENDING: 'Pending',
      VERIFIED: 'Verified',
      REJECTED: 'Rejected',
      EXPIRED_AUTO: 'Auto-Expired',
    };
    return labels[status] || status;
  }

  getBadgeClassForStatus(status: string): string {
    switch (status) {
      case 'PENDING':
        return 'badge-warn';
      case 'VERIFIED':
        return 'badge-success';
      case 'REJECTED':
      case 'EXPIRED_AUTO':
        return 'badge-error';
      default:
        return 'badge-neutral';
    }
  }

  getDocumentSummary(): string {
    if (!this.carData) return '';
    const verified = this.carData.documents.filter((d) => d.status === 'VERIFIED').length;
    const total = this.carData.documents.length;
    return `${verified}/${total} documents verified`;
  }

  viewDocument(doc: DocumentReviewDto) {
    // Always use the secured admin endpoint (never trust storage paths/URLs from DTOs).
    if (!doc?.id) return;
    window.open(`/api/admin/documents/${doc.id}/download`, '_blank');
  }

  verifyDocument(doc: DocumentReviewDto) {
    if (!this.carId || this.isProcessing) return;

    const dialogRef = this.dialog.open(ConfirmDialogComponent, {
      data: {
        title: 'Verify Document',
        message: `Verify ${this.getDocumentName(doc.type)}?`,
        confirmText: 'Verify',
        confirmColor: 'primary',
      },
    });
    dialogRef.afterClosed().subscribe((result) => {
      if (!result) return;
      this.isProcessing = true;
      this.adminApi
        .verifyDocument(doc.id, true)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: () => {
            this.notification.showSuccess('Document verified');
            this.loadCarReviewDetail();
            this.isProcessing = false;
          },
          error: (err) => {
            console.error('Failed to verify document', err);
            this.notification.showError('Failed to verify document');
            this.isProcessing = false;
          },
        });
    });
  }

  openRejectDialog(doc: DocumentReviewDto) {
    const dialogRef = this.dialog.open(ConfirmDialogComponent, {
      data: {
        title: 'Reject Document',
        message: `Reject ${this.getDocumentName(doc.type)}?`,
        confirmText: 'Reject',
        confirmColor: 'warn',
        requireReason: true,
        reasonLabel: 'Rejection reason',
        reasonMinLength: 20,
      },
    });
    dialogRef.afterClosed().subscribe((reason) => {
      if (reason) {
        this.rejectDocumentWithReason(doc, reason);
      }
    });
  }

  private rejectDocumentWithReason(doc: DocumentReviewDto, reason: string) {
    if (this.isProcessing) return;

    this.isProcessing = true;
    this.adminApi
      .verifyDocument(doc.id, false, reason)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.notification.showSuccess('Document rejected');
          this.loadCarReviewDetail();
          this.isProcessing = false;
        },
        error: (err) => {
          console.error('Failed to reject document', err);
          this.notification.showError('Failed to reject document');
          this.isProcessing = false;
        },
      });
  }

  approveCar() {
    if (!this.carId || this.isProcessing || !this.carData?.approvalState.canApprove) return;

    const dialogRef = this.dialog.open(ConfirmDialogComponent, {
      data: {
        title: 'Approve Car',
        message: `Approve ${this.carData.brand} ${this.carData.model} for rental?\n\nAll documents are verified and compliance dates are valid.`,
        confirmText: 'Approve',
        confirmColor: 'primary',
      },
    });
    dialogRef.afterClosed().subscribe((result) => {
      if (!result) return;
      this.isProcessing = true;
      this.adminApi
        .approveCar(this.carId!)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: () => {
            this.notification.showSuccess('Car approved for rental');
            this.router.navigate(['/admin/cars']);
          },
          error: (err) => {
            console.error('Failed to approve car', err);
            this.notification.showError('Failed to approve car');
            this.isProcessing = false;
          },
        });
    });
  }

  rejectCar() {
    if (!this.carId || this.isProcessing) return;

    if (!this.rejectionReason || this.rejectionReason.length < 20) {
      this.notification.showError('Rejection reason must be at least 20 characters');
      return;
    }

    const dialogRef = this.dialog.open(ConfirmDialogComponent, {
      data: {
        title: 'Reject Car',
        message: `Reject ${this.carData?.brand} ${this.carData?.model}?\n\nOwner will be notified of rejection reason.`,
        confirmText: 'Reject',
        confirmColor: 'warn',
      },
    });
    dialogRef.afterClosed().subscribe((result) => {
      if (!result) return;
      this.isProcessing = true;
      this.adminApi
        .rejectCar(this.carId!, this.rejectionReason!)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: () => {
            this.notification.showSuccess('Car rejected');
            this.router.navigate(['/admin/cars']);
          },
          error: (err) => {
            console.error('Failed to reject car', err);
            this.notification.showError('Failed to reject car');
            this.isProcessing = false;
          },
        });
    });
  }
}
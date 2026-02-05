import { Component, Input, Output, EventEmitter, OnInit, OnDestroy, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subject, takeUntil } from 'rxjs';

import { AdminApiService } from '../../../../../core/services/admin-api.service';
import {
  RenterDocumentDto,
  RenterVerificationProfileDto,
  DriverLicenseStatus,
  STATUS_BADGE_CONFIG,
  getConfidenceLevel,
} from '../../../../../core/models/admin-renter-verification.model';

/**
 * Renter Verification Card Component
 * 
 * Displays driver's license verification status for a user in the admin panel.
 * Shows license metadata, 3 document tiles (front, back, selfie), and action buttons.
 * 
 * @example
 * <app-renter-verification-card
 *   [userId]="userId"
 *   (approve)="onApprove()"
 *   (reject)="onReject()"
 *   (previewDocument)="openPreview($event)"
 * ></app-renter-verification-card>
 */
@Component({
  selector: 'app-renter-verification-card',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  styleUrls: [
    '../../../admin-shared.styles.scss',
    './renter-verification-card.component.scss',
  ],
  template: `
    <mat-card class="surface-card surface-roomy verification-card">
      <mat-card-header>
        <mat-icon mat-card-avatar class="header-icon">verified_user</mat-icon>
        <mat-card-title>Verifikacija vozačke dozvole</mat-card-title>
        <mat-card-subtitle>
          <span [class]="getStatusBadgeClass()" *ngIf="profile">
            <mat-icon class="status-icon">{{ getStatusIcon() }}</mat-icon>
            {{ getStatusLabel() }}
          </span>
        </mat-card-subtitle>
      </mat-card-header>

      <mat-card-content>
        <!-- Loading State -->
        <div class="loading-state" *ngIf="loading">
          <mat-spinner diameter="32"></mat-spinner>
          <span>Učitavanje...</span>
        </div>

        <!-- No Verification Data -->
        <div class="empty-state" *ngIf="!loading && !profile">
          <mat-icon>description</mat-icon>
          <p>Korisnik nije podneo verifikaciju vozačke dozvole.</p>
        </div>

        <!-- Verification Content -->
        <ng-container *ngIf="!loading && profile">
          <!-- License Metadata -->
          <div class="license-metadata">
            <div class="row between">
              <span class="muted">Broj dozvole</span>
              <span class="strong">{{ profile.maskedLicenseNumber || '—' }}</span>
            </div>
            <div class="row between" *ngIf="profile.licenseExpiryDate">
              <span class="muted">Datum isteka</span>
              <span [class]="profile.expiryWarning ? 'strong text-warn' : 'strong'">
                {{ profile.licenseExpiryDate }}
                <span *ngIf="profile.daysUntilExpiry !== undefined && profile.daysUntilExpiry >= 0">
                  ({{ profile.daysUntilExpiry }} dana)
                </span>
              </span>
            </div>
            <div class="row between" *ngIf="profile.submittedAt">
              <span class="muted">Podneto</span>
              <span class="strong">{{ formatDate(profile.submittedAt) }}</span>
            </div>
            <div class="row between" *ngIf="profile.verifiedAt">
              <span class="muted">Verifikovano</span>
              <span class="strong">
                {{ formatDate(profile.verifiedAt) }}
                <span *ngIf="profile.verifiedByName"> od {{ profile.verifiedByName }}</span>
              </span>
            </div>
            <div class="row between" *ngIf="profile.rejectionReason">
              <span class="muted">Razlog odbijanja</span>
              <span class="strong text-error">{{ profile.rejectionReason }}</span>
            </div>
          </div>

          <!-- Documents Grid -->
          <div class="documents-section" *ngIf="profile.documents && profile.documents.length > 0">
            <h4 class="section-label">Dokumenti za pregled</h4>
            <div class="documents-grid">
              <div 
                class="document-tile"
                *ngFor="let doc of profile.documents"
                (click)="onDocumentClick(doc)"
                [class.clickable]="doc.downloadUrl"
                tabindex="0"
                role="button"
                [attr.aria-label]="'Pregled: ' + doc.typeDisplay"
                (keydown.enter)="onDocumentClick(doc)"
              >
                <div class="doc-thumbnail">
                  <mat-icon class="doc-icon">{{ getDocumentIcon(doc) }}</mat-icon>
                </div>
                <div class="doc-info">
                  <span class="doc-type">{{ doc.typeDisplay }}</span>
                  <span class="doc-status" [class]="'doc-status-' + doc.status.toLowerCase()">
                    {{ doc.statusDisplay }}
                  </span>
                </div>
                
                <!-- OCR/Biometric Badges -->
                <div class="doc-scores" *ngIf="hasScoreData(doc)">
                  <span 
                    class="score-badge"
                    [class]="'confidence-' + getConfidenceLevel(doc.ocrConfidencePercent)"
                    *ngIf="doc.ocrConfidencePercent !== undefined"
                    [matTooltip]="'OCR pouzdanost: ' + doc.ocrConfidencePercent + '%'"
                  >
                    OCR: {{ doc.ocrConfidencePercent }}%
                  </span>
                  <span 
                    class="score-badge"
                    [class]="'confidence-' + getConfidenceLevel(doc.faceMatchPercent)"
                    *ngIf="doc.faceMatchPercent !== undefined"
                    [matTooltip]="'Podudarnost lica: ' + doc.faceMatchPercent + '%'"
                  >
                    Lice: {{ doc.faceMatchPercent }}%
                  </span>
                  <span 
                    class="score-badge liveness-badge"
                    [class.passed]="doc.livenessPassed"
                    [class.failed]="doc.livenessPassed === false"
                    *ngIf="doc.livenessPassed !== undefined"
                    [matTooltip]="doc.livenessPassed ? 'Provera živosti uspešna' : 'Provera živosti neuspešna'"
                  >
                    <mat-icon>{{ doc.livenessPassed ? 'check_circle' : 'cancel' }}</mat-icon>
                    Živost
                  </span>
                </div>
                
                <div class="doc-preview-hint">
                  <mat-icon>zoom_in</mat-icon>
                  <span>Klikni za pregled</span>
                </div>
              </div>
            </div>
          </div>

          <!-- Missing Documents Warning -->
          <div class="missing-docs-warning" *ngIf="profile.missingDocuments && profile.missingDocuments.length > 0">
            <mat-icon>warning</mat-icon>
            <span>Nedostaju dokumenti: {{ profile.missingDocuments.join(', ') }}</span>
          </div>

          <!-- Action Buttons -->
          <div class="action-buttons" *ngIf="canTakeAction()">
            <button 
              mat-flat-button 
              color="primary" 
              class="btn btn-success"
              (click)="onApproveClick()"
              [disabled]="actionLoading"
            >
              <mat-icon>check_circle</mat-icon>
              Odobri verifikaciju
            </button>
            <button 
              mat-stroked-button 
              color="warn"
              class="btn btn-secondary"
              (click)="onRejectClick()"
              [disabled]="actionLoading"
            >
              <mat-icon>cancel</mat-icon>
              Odbij verifikaciju
            </button>
          </div>

          <!-- Approved/Rejected Message -->
          <div class="status-message success" *ngIf="profile.status === 'APPROVED'">
            <mat-icon>check_circle</mat-icon>
            <span>Korisnik može da rezerviše vozila.</span>
          </div>
          <div class="status-message error" *ngIf="profile.status === 'REJECTED'">
            <mat-icon>info</mat-icon>
            <span>Korisnik mora ponovo podneti dokumenta.</span>
          </div>
          <div class="status-message warn" *ngIf="profile.status === 'SUSPENDED'">
            <mat-icon>block</mat-icon>
            <span>Nalog je suspendovan. Potrebna istraga.</span>
          </div>
        </ng-container>
      </mat-card-content>
    </mat-card>
  `,
})
export class RenterVerificationCardComponent implements OnInit, OnDestroy {
  private api = inject(AdminApiService);
  private destroy$ = new Subject<void>();

  @Input() userId!: number;
  
  @Output() approve = new EventEmitter<void>();
  @Output() reject = new EventEmitter<void>();
  @Output() previewDocument = new EventEmitter<RenterDocumentDto>();

  profile: RenterVerificationProfileDto | null = null;
  loading = true;
  actionLoading = false;

  ngOnInit(): void {
    this.loadVerificationProfile();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadVerificationProfile(): void {
    if (!this.userId) {
      this.loading = false;
      return;
    }

    this.loading = true;
    this.api.getRenterVerificationDetails(this.userId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (profile) => {
          this.profile = profile;
          this.loading = false;
        },
        error: () => {
          this.profile = null;
          this.loading = false;
        },
      });
  }

  // ==================== STATUS HELPERS ====================

  getStatusBadgeClass(): string {
    if (!this.profile?.status) return 'badge badge-neutral';
    const config = STATUS_BADGE_CONFIG[this.profile.status];
    return `badge badge-${config?.color || 'neutral'}`;
  }

  getStatusIcon(): string {
    if (!this.profile?.status) return 'help_outline';
    return STATUS_BADGE_CONFIG[this.profile.status]?.icon || 'help_outline';
  }

  getStatusLabel(): string {
    return this.profile?.statusDisplay || 'Nepoznato';
  }

  canTakeAction(): boolean {
    return this.profile?.status === 'PENDING_REVIEW';
  }

  // ==================== DOCUMENT HELPERS ====================

  getDocumentIcon(doc: RenterDocumentDto): string {
    switch (doc.type) {
      case 'DRIVERS_LICENSE_FRONT':
        return 'credit_card';
      case 'DRIVERS_LICENSE_BACK':
        return 'flip_camera_ios';
      case 'SELFIE':
        return 'face';
      default:
        return 'description';
    }
  }

  hasScoreData(doc: RenterDocumentDto): boolean {
    return (
      doc.ocrConfidencePercent !== undefined ||
      doc.faceMatchPercent !== undefined ||
      doc.livenessPassed !== undefined
    );
  }

  getConfidenceLevel(percent?: number): string {
    return getConfidenceLevel(percent);
  }

  formatDate(isoString: string): string {
    try {
      const date = new Date(isoString);
      return date.toLocaleDateString('sr-Latn', {
        day: '2-digit',
        month: 'short',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      });
    } catch {
      return isoString;
    }
  }

  // ==================== EVENT HANDLERS ====================

  onDocumentClick(doc: RenterDocumentDto): void {
    if (doc.downloadUrl) {
      this.previewDocument.emit(doc);
    }
  }

  onApproveClick(): void {
    this.approve.emit();
  }

  onRejectClick(): void {
    this.reject.emit();
  }
}

import { Component, Inject, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DomSanitizer, SafeUrl } from '@angular/platform-browser';

import { AdminApiService } from '../../../../../core/services/admin-api.service';
import { AdminNotificationService } from '../../../../../core/services/admin-notification.service';
import {
  RenterDocumentDto,
  getConfidenceLevel,
} from '../../../../../core/models/admin-renter-verification.model';
import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component';

export interface DocumentPreviewDialogData {
  document: RenterDocumentDto;
}

/**
 * Document Preview Dialog
 *
 * Full-resolution image viewer with OCR/biometric results sidebar.
 * Supports zoom and download functionality.
 */
@Component({
  selector: 'app-document-preview-dialog',
  standalone: true,
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  styleUrls: ['./document-preview-dialog.component.scss'],
  template: `
    <div class="preview-dialog">
      <div class="dialog-header">
        <h2 mat-dialog-title>
          <mat-icon>{{ getDocumentIcon() }}</mat-icon>
          {{ doc.typeDisplay }}
        </h2>
        <button mat-icon-button (click)="close()" aria-label="Zatvori">
          <mat-icon>close</mat-icon>
        </button>
      </div>

      <mat-dialog-content>
        <div class="content-layout">
          <!-- Image Panel -->
          <div class="image-panel">
            <div class="image-container" *ngIf="!imageLoading && imageUrl">
              <img
                [src]="imageUrl"
                [alt]="doc.typeDisplay"
                (load)="onImageLoad()"
                (error)="onImageError()"
                [style.transform]="'scale(' + zoomLevel + ')'"
                draggable="false"
              />
            </div>

            <div class="image-loading" *ngIf="imageLoading">
              <mat-spinner diameter="40"></mat-spinner>
              <span>Učitavanje slike...</span>
            </div>

            <div class="image-error" *ngIf="imageError">
              <mat-icon>broken_image</mat-icon>
              <span>Greška pri učitavanju slike</span>
              <button mat-button (click)="loadImage()">Pokušaj ponovo</button>
            </div>

            <!-- Zoom Controls -->
            <div class="zoom-controls" *ngIf="!imageLoading && !imageError">
              <button
                mat-icon-button
                (click)="zoomOut()"
                [disabled]="zoomLevel <= 0.5"
                matTooltip="Umanji"
              >
                <mat-icon>remove</mat-icon>
              </button>
              <span class="zoom-level">{{ (zoomLevel * 100).toFixed(0) }}%</span>
              <button
                mat-icon-button
                (click)="zoomIn()"
                [disabled]="zoomLevel >= 3"
                matTooltip="Uvećaj"
              >
                <mat-icon>add</mat-icon>
              </button>
              <button mat-icon-button (click)="resetZoom()" matTooltip="Resetuj uvećanje">
                <mat-icon>fit_screen</mat-icon>
              </button>
            </div>
          </div>

          <!-- Results Sidebar -->
          <div class="results-sidebar">
            <!-- OCR Results (for license images) -->
            <div class="results-section" *ngIf="hasOcrData()">
              <h3 class="section-title">
                <mat-icon>document_scanner</mat-icon>
                OCR rezultati
              </h3>

              <div class="results-grid">
                <div class="result-item" *ngIf="doc.ocrExtractedName">
                  <span class="result-label">Ime</span>
                  <span class="result-value">{{ doc.ocrExtractedName }}</span>
                </div>
                <div class="result-item" *ngIf="doc.ocrExtractedNumber">
                  <span class="result-label">Broj dozvole</span>
                  <span class="result-value">{{ doc.ocrExtractedNumber }}</span>
                </div>
                <div class="result-item" *ngIf="doc.ocrExtractedExpiry">
                  <span class="result-label">Datum isteka</span>
                  <span class="result-value">{{ doc.ocrExtractedExpiry }}</span>
                </div>
              </div>

              <div class="confidence-meter" *ngIf="doc.ocrConfidencePercent !== undefined">
                <span class="confidence-label">Pouzdanost OCR</span>
                <div class="meter-bar">
                  <div
                    class="meter-fill"
                    [class]="'confidence-' + getConfidenceLevel(doc.ocrConfidencePercent)"
                    [style.width.%]="doc.ocrConfidencePercent"
                  ></div>
                </div>
                <span
                  class="confidence-value"
                  [class]="'confidence-' + getConfidenceLevel(doc.ocrConfidencePercent)"
                >
                  {{ doc.ocrConfidencePercent }}%
                </span>
              </div>
            </div>

            <!-- Biometric Results (for selfie) -->
            <div class="results-section" *ngIf="hasBiometricData()">
              <h3 class="section-title">
                <mat-icon>face</mat-icon>
                Biometrijska analiza
              </h3>

              <!-- Liveness Check -->
              <div class="liveness-result" *ngIf="doc.livenessPassed !== undefined">
                <mat-icon [class]="doc.livenessPassed ? 'success' : 'error'">
                  {{ doc.livenessPassed ? 'check_circle' : 'cancel' }}
                </mat-icon>
                <div class="liveness-info">
                  <span class="liveness-label">Provera živosti</span>
                  <span class="liveness-value" [class]="doc.livenessPassed ? 'success' : 'error'">
                    {{ doc.livenessPassed ? 'USPEŠNO' : 'NEUSPEŠNO' }}
                  </span>
                </div>
              </div>

              <!-- Face Match -->
              <div class="confidence-meter" *ngIf="doc.faceMatchPercent !== undefined">
                <span class="confidence-label">Podudarnost lica</span>
                <div class="meter-bar">
                  <div
                    class="meter-fill"
                    [class]="'confidence-' + getConfidenceLevel(doc.faceMatchPercent)"
                    [style.width.%]="doc.faceMatchPercent"
                  ></div>
                </div>
                <span
                  class="confidence-value"
                  [class]="'confidence-' + getConfidenceLevel(doc.faceMatchPercent)"
                >
                  {{ doc.faceMatchPercent }}%
                </span>
              </div>

              <!-- Name Match -->
              <div class="confidence-meter" *ngIf="doc.nameMatchPercent !== undefined">
                <span class="confidence-label">Podudarnost imena</span>
                <div class="meter-bar">
                  <div
                    class="meter-fill"
                    [class]="'confidence-' + getConfidenceLevel(doc.nameMatchPercent)"
                    [style.width.%]="doc.nameMatchPercent"
                  ></div>
                </div>
                <span
                  class="confidence-value"
                  [class]="'confidence-' + getConfidenceLevel(doc.nameMatchPercent)"
                >
                  {{ doc.nameMatchPercent }}%
                </span>
              </div>

              <!-- Recommendation -->
              <div class="recommendation" [class]="getRecommendationClass()">
                <mat-icon>{{ getRecommendationIcon() }}</mat-icon>
                <span>{{ getRecommendationText() }}</span>
              </div>
            </div>

            <!-- Document Info -->
            <div class="results-section">
              <h3 class="section-title">
                <mat-icon>info</mat-icon>
                Informacije o dokumentu
              </h3>

              <div class="results-grid">
                <div class="result-item">
                  <span class="result-label">Status</span>
                  <span class="result-value" [class]="'status-' + doc.status.toLowerCase()">
                    {{ doc.statusDisplay }}
                  </span>
                </div>
                <div class="result-item" *ngIf="doc.uploadedAt">
                  <span class="result-label">Datum otpremanja</span>
                  <span class="result-value">{{ formatDate(doc.uploadedAt) }}</span>
                </div>
                <div class="result-item" *ngIf="doc.filename">
                  <span class="result-label">Ime fajla</span>
                  <span class="result-value filename">{{ doc.filename }}</span>
                </div>
                <div class="result-item" *ngIf="doc.processingStatus">
                  <span class="result-label">Obrada</span>
                  <span class="result-value">{{ doc.processingStatus }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        <button mat-button (click)="download()" [disabled]="imageLoading || imageError">
          <mat-icon>download</mat-icon>
          Preuzmi
        </button>
        <button mat-flat-button color="primary" (click)="close()">Zatvori</button>
      </mat-dialog-actions>
    </div>
  `,
})
export class DocumentPreviewDialogComponent implements OnInit {
  private api = inject(AdminApiService);
  private confirmDialog = inject(MatDialog);
  private notification = inject(AdminNotificationService);
  private sanitizer = inject(DomSanitizer);

  doc: RenterDocumentDto;

  imageUrl: SafeUrl | null = null;
  imageLoading = true;
  imageError = false;
  zoomLevel = 1;

  constructor(
    private dialogRef: MatDialogRef<DocumentPreviewDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: DocumentPreviewDialogData,
  ) {
    this.doc = data.document;
  }

  ngOnInit(): void {
    this.loadImage();
  }

  // ==================== IMAGE LOADING ====================

  loadImage(): void {
    this.imageLoading = true;
    this.imageError = false;

    // If we have a download URL, use it directly
    if (this.doc.downloadUrl) {
      this.imageUrl = this.sanitizer.bypassSecurityTrustUrl(this.doc.downloadUrl);
      this.imageLoading = false;
    } else {
      this.imageError = true;
      this.imageLoading = false;
    }
  }

  onImageLoad(): void {
    this.imageLoading = false;
  }

  onImageError(): void {
    this.imageError = true;
    this.imageLoading = false;
  }

  // ==================== ZOOM CONTROLS ====================

  zoomIn(): void {
    if (this.zoomLevel < 3) {
      this.zoomLevel = Math.min(3, this.zoomLevel + 0.25);
    }
  }

  zoomOut(): void {
    if (this.zoomLevel > 0.5) {
      this.zoomLevel = Math.max(0.5, this.zoomLevel - 0.25);
    }
  }

  resetZoom(): void {
    this.zoomLevel = 1;
  }

  // ==================== DATA HELPERS ====================

  hasOcrData(): boolean {
    return (
      this.doc.ocrConfidencePercent !== undefined ||
      this.doc.ocrExtractedName !== undefined ||
      this.doc.ocrExtractedNumber !== undefined
    );
  }

  hasBiometricData(): boolean {
    return (
      this.doc.livenessPassed !== undefined ||
      this.doc.faceMatchPercent !== undefined ||
      this.doc.nameMatchPercent !== undefined
    );
  }

  getConfidenceLevel(percent?: number): string {
    return getConfidenceLevel(percent);
  }

  getDocumentIcon(): string {
    switch (this.doc.type) {
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

  // ==================== RECOMMENDATION LOGIC ====================

  getRecommendationClass(): string {
    if (!this.hasBiometricData()) return 'neutral';

    const liveness = this.doc.livenessPassed ?? false;
    const faceMatch = this.doc.faceMatchPercent ?? 0;

    if (liveness && faceMatch >= 85) return 'success';
    if (!liveness || faceMatch < 70) return 'error';
    return 'warn';
  }

  getRecommendationIcon(): string {
    const cls = this.getRecommendationClass();
    switch (cls) {
      case 'success':
        return 'thumb_up';
      case 'error':
        return 'thumb_down';
      default:
        return 'warning';
    }
  }

  getRecommendationText(): string {
    const cls = this.getRecommendationClass();
    switch (cls) {
      case 'success':
        return 'Svi provere su uspešne. Bezbedno za odobrenje.';
      case 'error':
        return 'Provere nisu uspešne. Preporučuje se odbijanje.';
      default:
        return 'Neke provere su na granici. Pregled je potreban.';
    }
  }

  // ==================== ACTIONS ====================

  download(): void {
    const dialogRef = this.confirmDialog.open(ConfirmDialogComponent, {
      width: '460px',
      data: {
        title: 'Preuzmi dokument',
        message: 'Unesite razlog preuzimanja dokumenta. Ova akcija se trajno evidentira.',
        confirmText: 'Preuzmi',
        confirmColor: 'warn',
        requireReason: true,
        reasonLabel: 'Razlog preuzimanja',
        reasonMinLength: 8,
      },
    });

    dialogRef.afterClosed().subscribe((reason: string | undefined) => {
      if (!reason) {
        return;
      }

      this.api.downloadRenterDocument(this.doc.id, reason).subscribe({
        next: (grant) => {
          window.open(grant.url, '_blank', 'noopener');
        },
        error: () => this.notification.showError('Greška pri dodeli pristupa za preuzimanje'),
      });
    });
  }

  close(): void {
    this.dialogRef.close();
  }
}

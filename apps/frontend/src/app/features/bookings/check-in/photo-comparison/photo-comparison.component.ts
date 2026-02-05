/**
 * Photo Comparison Component
 *
 * Displays side-by-side comparison of host and guest photos,
 * highlighting any detected discrepancies.
 *
 * @since Enterprise Upgrade Phase 2
 */

import {
  Component,
  Input,
  Output,
  EventEmitter,
  inject,
  signal,
  computed,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatExpansionModule } from '@angular/material/expansion';

import { CheckInPhotoDTO } from '../../../../core/models/check-in.model';
import {
  PhotoDiscrepancySummaryDTO,
  getCorrespondingHostType,
} from '../../../../core/models/photo-guidance.model';
import { PhotoGuidanceService } from '../../../../core/services/photo-guidance.service';
import { PhotoViewerDialogComponent } from '../../../../shared/components/photo-viewer-dialog/photo-viewer-dialog.component';
import { environment } from '../../../../../environments/environment';

export interface PhotoComparisonPair {
  photoType: string;
  hostPhotoUrl?: string;
  guestPhotoUrl?: string;
  hostPhoto?: CheckInPhotoDTO;
  guestPhoto?: CheckInPhotoDTO;
  hasDiscrepancy: boolean;
  discrepancy?: PhotoDiscrepancySummaryDTO;
}

@Component({
  selector: 'app-photo-comparison',
  standalone: true,
  imports: [
    CommonModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatChipsModule,
    MatDialogModule,
    MatExpansionModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="comparison-container">
      <!-- Header -->
      <div class="comparison-header">
        <h2>Poređenje fotografija</h2>
        <p>Pregledajte razlike između fotografija domaćina i vaših fotografija</p>
      </div>

      <!-- Discrepancy Summary -->
      @if (discrepancySummary()) {
      <div class="discrepancy-summary" [class]="discrepancySummary()!.severity.toLowerCase()">
        <div class="summary-icon">
          @switch (discrepancySummary()!.severity) { @case ('CRITICAL') {
          <mat-icon>error</mat-icon>
          } @case ('HIGH') {
          <mat-icon>warning</mat-icon>
          } @case ('MEDIUM') {
          <mat-icon>info</mat-icon>
          } @default {
          <mat-icon>check_circle</mat-icon>
          } }
        </div>
        <div class="summary-content">
          <span class="summary-title">
            {{ discrepancySummary()!.totalDiscrepancies }}
            {{
              discrepancySummary()!.totalDiscrepancies === 1
                ? 'razlika pronađena'
                : 'razlike pronađene'
            }}
          </span>
          <span class="summary-message">
            @switch (discrepancySummary()!.severity) { @case ('CRITICAL') { Kritične razlike
            zahtevaju pažnju pre nastavka. } @case ('HIGH') { Značajne razlike pronađene.
            Pregledajte sa domaćinom. } @case ('MEDIUM') { Manje razlike primećene. Možete
            nastaviti. } @default { Bez značajnih razlika. } }
          </span>
        </div>
      </div>
      }

      <!-- Photo Grid -->
      <div class="photos-grid">
        @for (pair of photoComparisons(); track pair.photoType) {
        <mat-card class="photo-pair-card" [class.has-discrepancy]="pair.hasDiscrepancy">
          <mat-card-header>
            <mat-card-title>{{ getPhotoTypeName(pair.photoType) }}</mat-card-title>
            @if (pair.hasDiscrepancy) {
            <mat-chip color="warn">
              <mat-icon>warning</mat-icon>
              Razlika
            </mat-chip>
            }
          </mat-card-header>

          <mat-card-content>
            <div class="photo-columns">
              <!-- Host Photo -->
              <div class="photo-column">
                <label>Domaćin</label>
                @if (pair.hostPhotoUrl) {
                <img
                  [src]="pair.hostPhotoUrl"
                  [alt]="'Domaćin - ' + pair.photoType"
                  (click)="openPhotoViewer(pair.hostPhotoUrl!)"
                  class="comparison-photo"
                />
                } @else {
                <div class="no-photo">
                  <mat-icon>image_not_supported</mat-icon>
                  <span>Nema fotografije</span>
                </div>
                }
              </div>

              <!-- Comparison Indicator -->
              <div class="comparison-indicator">
                @if (pair.hasDiscrepancy) {
                <mat-icon class="discrepancy-icon">compare</mat-icon>
                } @else {
                <mat-icon class="match-icon">check</mat-icon>
                }
              </div>

              <!-- Guest Photo -->
              <div class="photo-column">
                <label>Vaša fotografija</label>
                @if (pair.guestPhotoUrl) {
                <img
                  [src]="pair.guestPhotoUrl"
                  [alt]="'Gost - ' + pair.photoType"
                  (click)="openPhotoViewer(pair.guestPhotoUrl!)"
                  class="comparison-photo"
                />
                } @else {
                <div class="no-photo">
                  <mat-icon>image_not_supported</mat-icon>
                  <span>Nema fotografije</span>
                </div>
                }
              </div>
            </div>

            <!-- Discrepancy Details -->
            @if (pair.discrepancy) {
            <div class="discrepancy-details">
              <mat-icon>info</mat-icon>
              <span>{{ pair.discrepancy.description }}</span>
            </div>
            }
          </mat-card-content>
        </mat-card>
        }
      </div>

      <!-- Action Buttons -->
      <div class="comparison-actions">
        @if (hasBlockingDiscrepancies()) {
        <p class="blocking-message">
          <mat-icon>block</mat-icon>
          Morate rešiti kritične razlike pre nastavka
        </p>
        <button mat-flat-button color="warn" (click)="reportDiscrepancy.emit()">
          <mat-icon>report</mat-icon>
          Prijavi problem
        </button>
        } @else {
        <button mat-flat-button color="primary" (click)="continue.emit()">
          <mat-icon>check</mat-icon>
          Potvrdi i nastavi
        </button>
        }
      </div>
    </div>
  `,
  styles: [
    `
      .comparison-container {
        padding: 16px;
        max-width: 900px;
        margin: 0 auto;
      }

      .comparison-header {
        text-align: center;
        margin-bottom: 24px;
      }

      .comparison-header h2 {
        margin: 0 0 8px 0;
        font-size: 24px;
        font-weight: 600;
      }

      .comparison-header p {
        margin: 0;
        color: rgba(0, 0, 0, 0.6);
      }

      .discrepancy-summary {
        display: flex;
        align-items: center;
        gap: 16px;
        padding: 16px;
        border-radius: 12px;
        margin-bottom: 24px;
      }

      .discrepancy-summary.critical {
        background: #ffebee;
        border: 1px solid #ef5350;
      }

      .discrepancy-summary.critical .summary-icon mat-icon {
        color: #ef5350;
      }

      .discrepancy-summary.high {
        background: #fff3e0;
        border: 1px solid #ff9800;
      }

      .discrepancy-summary.high .summary-icon mat-icon {
        color: #ff9800;
      }

      .discrepancy-summary.medium {
        background: #fff8e1;
        border: 1px solid #ffc107;
      }

      .discrepancy-summary.medium .summary-icon mat-icon {
        color: #ffc107;
      }

      .discrepancy-summary.low {
        background: #e8f5e9;
        border: 1px solid #4caf50;
      }

      .discrepancy-summary.low .summary-icon mat-icon {
        color: #4caf50;
      }

      .summary-icon mat-icon {
        font-size: 32px;
        width: 32px;
        height: 32px;
      }

      .summary-content {
        display: flex;
        flex-direction: column;
      }

      .summary-title {
        font-weight: 600;
        font-size: 16px;
      }

      .summary-message {
        font-size: 14px;
        opacity: 0.8;
      }

      .photos-grid {
        display: flex;
        flex-direction: column;
        gap: 16px;
      }

      .photo-pair-card {
        transition: box-shadow 0.2s;
      }

      .photo-pair-card.has-discrepancy {
        border: 2px solid #ff9800;
      }

      .photo-pair-card mat-card-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
      }

      .photo-columns {
        display: flex;
        gap: 8px;
        align-items: center;
      }

      .photo-column {
        flex: 1;
        display: flex;
        flex-direction: column;
        align-items: center;
      }

      .photo-column label {
        font-size: 12px;
        font-weight: 500;
        margin-bottom: 8px;
        color: rgba(0, 0, 0, 0.6);
      }

      .comparison-photo {
        width: 100%;
        aspect-ratio: 4/3;
        object-fit: cover;
        border-radius: 8px;
        cursor: pointer;
        transition: transform 0.2s;
        /* CRITICAL: Respect EXIF orientation metadata for rotated photos */
        image-orientation: from-image !important;
      }

      .comparison-photo:hover {
        transform: scale(1.02);
      }

      .no-photo {
        width: 100%;
        aspect-ratio: 4/3;
        background: #f5f5f5;
        border-radius: 8px;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        color: rgba(0, 0, 0, 0.4);
        gap: 8px;
      }

      .comparison-indicator {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 40px;
      }

      .discrepancy-icon {
        color: #ff9800;
      }

      .match-icon {
        color: #4caf50;
      }

      .discrepancy-details {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-top: 12px;
        padding: 12px;
        background: #fff3e0;
        border-radius: 8px;
        font-size: 14px;
      }

      .discrepancy-details mat-icon {
        color: #ff9800;
        flex-shrink: 0;
      }

      .comparison-actions {
        margin-top: 24px;
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 16px;
      }

      .blocking-message {
        display: flex;
        align-items: center;
        gap: 8px;
        color: #f44336;
        font-weight: 500;
      }

      /* Mobile responsive */
      @media (max-width: 600px) {
        .photo-columns {
          flex-direction: column;
        }

        .comparison-indicator {
          width: 100%;
          padding: 8px 0;
        }
      }
    `,
  ],
})
export class PhotoComparisonComponent {
  @Input() hostPhotos: CheckInPhotoDTO[] = [];
  @Input() guestPhotos: CheckInPhotoDTO[] = [];
  @Input() discrepancies: PhotoDiscrepancySummaryDTO[] = [];
  @Input() sessionId?: string;

  @Output() continue = new EventEmitter<void>();
  @Output() reportDiscrepancy = new EventEmitter<void>();

  private readonly dialog = inject(MatDialog);
  private readonly guidanceService = inject(PhotoGuidanceService);

  // Build comparison pairs
  protected readonly photoComparisons = computed<PhotoComparisonPair[]>(() => {
    const pairs: PhotoComparisonPair[] = [];
    const photoTypes = [
      'EXTERIOR_FRONT',
      'EXTERIOR_REAR',
      'EXTERIOR_LEFT',
      'EXTERIOR_RIGHT',
      'INTERIOR_DASHBOARD',
      'INTERIOR_REAR',
      'ODOMETER',
      'FUEL_GAUGE',
    ];

    // Debug: Log incoming photos
    console.log(
      '[PhotoComparison] Host photos:',
      this.hostPhotos.map((p) => ({ type: p.photoType, url: p.url?.substring(0, 50) }))
    );
    console.log(
      '[PhotoComparison] Guest photos:',
      this.guestPhotos.map((p) => ({ type: p.photoType, url: p.url?.substring(0, 50) }))
    );
    console.log('[PhotoComparison] SessionId:', this.sessionId);

    for (const baseType of photoTypes) {
      const hostType = `HOST_${baseType}`;
      const guestType = `GUEST_${baseType}`;

      const hostPhoto = this.hostPhotos.find((p) => p.photoType === hostType);
      const guestPhoto = this.guestPhotos.find((p) => p.photoType === guestType);

      const discrepancy = this.discrepancies.find(
        (d) => d.photoType === guestType || d.photoType === hostType
      );

      const hostUrl = hostPhoto ? this.getHostPhotoUrl(hostPhoto) : undefined;
      console.log(
        `[PhotoComparison] ${baseType}: hostPhoto=${!!hostPhoto}, hostUrl=${hostUrl?.substring(
          0,
          80
        )}`
      );

      pairs.push({
        photoType: baseType,
        hostPhotoUrl: hostUrl,
        guestPhotoUrl: guestPhoto ? this.getGuestPhotoUrl(guestPhoto) : undefined,
        hostPhoto,
        guestPhoto,
        hasDiscrepancy: !!discrepancy,
        discrepancy,
      });
    }

    return pairs;
  });

  // Calculate discrepancy summary
  protected readonly discrepancySummary = computed(() => {
    if (this.discrepancies.length === 0) return null;

    const severityOrder = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];
    const maxSeverity = this.discrepancies.reduce((max, d) => {
      const maxIndex = severityOrder.indexOf(max);
      const currentIndex = severityOrder.indexOf(d.severity);
      return currentIndex < maxIndex ? d.severity : max;
    }, 'LOW' as PhotoDiscrepancySummaryDTO['severity']);

    return {
      totalDiscrepancies: this.discrepancies.length,
      severity: maxSeverity,
    };
  });

  protected hasBlockingDiscrepancies(): boolean {
    return this.discrepancies.some((d) => d.severity === 'CRITICAL');
  }

  protected getPhotoTypeName(baseType: string): string {
    const names: Record<string, string> = {
      EXTERIOR_FRONT: 'Prednja strana',
      EXTERIOR_REAR: 'Zadnja strana',
      EXTERIOR_LEFT: 'Leva strana',
      EXTERIOR_RIGHT: 'Desna strana',
      INTERIOR_DASHBOARD: 'Kontrolna tabla',
      INTERIOR_REAR: 'Zadnja sedišta',
      ODOMETER: 'Kilometraža',
      FUEL_GAUGE: 'Gorivo',
    };
    return names[baseType] || baseType;
  }

  /**
   * Get URL for host photo (stored on backend).
   * Uses the check-in photo API endpoint.
   * Transforms storage path to proper API URL.
   */
  private getHostPhotoUrl(photo: CheckInPhotoDTO): string {
    const url = photo.url;

    // If URL is already absolute, return as-is
    if (url && url.startsWith('http')) {
      return url;
    }

    // If it's a data URL (shouldn't happen for host photos, but handle it)
    if (url?.startsWith('data:')) {
      return url;
    }

    // Transform storage path to API URL
    if (url) {
      const baseUrl = environment.baseApiUrl.replace(/\/$/, ''); // Remove trailing slash
      // Strip "checkin/" prefix if present (storage key format)
      const pathSegment = url.replace(/^checkin\//, '');
      return `${baseUrl}/checkin/photos/${pathSegment}`;
    }

    // Fallback: use sessionId and photoId if available
    if (this.sessionId && photo.photoId) {
      return `${environment.baseApiUrl}/checkin/photos/${this.sessionId}/${photo.photoId}`;
    }

    return '';
  }

  /**
   * Get URL for guest photo (may be local data URL or backend URL).
   * Guest photos captured during session are stored as data URLs.
   * After submission, they have backend URLs.
   */
  private getGuestPhotoUrl(photo: CheckInPhotoDTO): string {
    // Check if it's a local data URL (captured but not yet submitted)
    if (photo.url?.startsWith('data:')) {
      return photo.url;
    }

    // Otherwise, use the guest-checkin photos endpoint
    return `${environment.baseApiUrl}/guest-checkin/photos/${this.sessionId}/${photo.photoId}`;
  }

  protected openPhotoViewer(photoUrl: string): void {
    this.dialog.open(PhotoViewerDialogComponent, {
      data: { photoUrl },
      maxWidth: '95vw',
      maxHeight: '95vh',
      panelClass: 'photo-viewer-dialog',
    });
  }
}

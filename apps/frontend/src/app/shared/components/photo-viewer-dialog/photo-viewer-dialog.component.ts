/**
 * Photo Viewer Dialog Component
 *
 * Full-screen photo viewer for inspecting check-in photos with:
 * - Pan & Zoom (pinch gesture on mobile, scroll wheel on desktop)
 * - Swipe navigation between photos
 * - Photo type labels
 * - **Trust Badges**: EXIF validation status indicators (Phase 2: Resilience)
 *
 * ## Usage
 * ```typescript
 * this.dialog.open(PhotoViewerDialogComponent, {
 *   data: {
 *     photos: vehiclePhotos,
 *     startIndex: 0
 *   },
 *   panelClass: 'fullscreen-dialog'
 * });
 * ```
 *
 * ## Accessibility
 * - Keyboard navigation: Left/Right arrows, Escape to close
 * - ARIA labels for screen readers
 */
import {
  Component,
  Inject,
  signal,
  computed,
  HostListener,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { ExifValidationStatus } from '../../../core/models/check-in.model';

export interface PhotoViewerPhoto {
  url: string;
  photoType: string;
  label?: string;
  exifValidationStatus?: ExifValidationStatus;
  exifTimestamp?: string | null;
  deviceModel?: string | null;
}

export interface PhotoViewerDialogData {
  photos: PhotoViewerPhoto[];
  startIndex: number;
}

@Component({
  selector: 'app-photo-viewer-dialog',
  standalone: true,
  imports: [CommonModule, MatDialogModule, MatIconModule, MatButtonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div
      class="photo-viewer"
      (wheel)="onWheel($event)"
      (touchstart)="onTouchStart($event)"
      (touchmove)="onTouchMove($event)"
      (touchend)="onTouchEnd()"
    >
      <!-- Close Button -->
      <button
        mat-icon-button
        class="close-btn"
        (click)="close()"
        aria-label="Zatvori pregled fotografija"
      >
        <mat-icon>close</mat-icon>
      </button>

      <!-- Trust Badge (EXIF Validation Status) -->
      @if (currentPhoto().exifValidationStatus) {
      <div
        class="trust-badge"
        [class.verified]="isVerified()"
        [class.warning]="hasWarning()"
        [class.rejected]="isRejected()"
      >
        <mat-icon>{{ trustBadgeIcon() }}</mat-icon>
        <span class="trust-text">{{ trustBadgeText() }}</span>
        @if (currentPhoto().exifTimestamp) {
        <span class="trust-time">{{ currentPhoto().exifTimestamp | date : 'short' }}</span>
        }
      </div>
      }

      <!-- Image Container -->
      <div class="image-container" (dblclick)="onDoubleTap()">
        <img
          [src]="currentPhoto().url"
          [alt]="currentPhoto().label || currentPhoto().photoType"
          [style.transform]="imageTransform()"
          class="photo-image"
          draggable="false"
        />
      </div>

      <!-- Navigation Arrows -->
      @if (photos.length > 1) {
      <button
        mat-icon-button
        class="nav-btn nav-prev"
        (click)="prev()"
        [disabled]="currentIndex() === 0"
        aria-label="Prethodna fotografija"
      >
        <mat-icon>chevron_left</mat-icon>
      </button>

      <button
        mat-icon-button
        class="nav-btn nav-next"
        (click)="next()"
        [disabled]="currentIndex() === photos.length - 1"
        aria-label="Sledeća fotografija"
      >
        <mat-icon>chevron_right</mat-icon>
      </button>
      }

      <!-- Bottom Info Bar -->
      <div class="info-bar">
        <div class="photo-label">
          {{ currentPhoto().label || formatPhotoType(currentPhoto().photoType) }}
        </div>
        <div class="counter">{{ currentIndex() + 1 }} / {{ photos.length }}</div>
      </div>

      <!-- Zoom Controls -->
      <div class="zoom-controls">
        <button mat-icon-button (click)="zoomOut()" [disabled]="scale() <= 1" aria-label="Umanji">
          <mat-icon>remove</mat-icon>
        </button>
        <span class="zoom-level">{{ scale() * 100 | number : '1.0-0' }}%</span>
        <button mat-icon-button (click)="zoomIn()" [disabled]="scale() >= 4" aria-label="Uvećaj">
          <mat-icon>add</mat-icon>
        </button>
      </div>
    </div>
  `,
  styles: [
    `
      .photo-viewer {
        position: fixed;
        inset: 0;
        background: rgba(0, 0, 0, 0.95);
        display: flex;
        flex-direction: column;
        touch-action: none;
        user-select: none;
      }

      /* Close button */
      .close-btn {
        position: absolute;
        top: 16px;
        right: 16px;
        z-index: 100;
        color: white;
        background: rgba(0, 0, 0, 0.5);
      }

      .close-btn:hover {
        background: rgba(255, 255, 255, 0.2);
      }

      /* Image container */
      .image-container {
        flex: 1;
        display: flex;
        align-items: center;
        justify-content: center;
        overflow: hidden;
        cursor: grab;
      }

      .image-container:active {
        cursor: grabbing;
      }

      .photo-image {
        max-width: 100%;
        max-height: 100%;
        object-fit: contain;
        transition: transform 0.15s ease-out;
        will-change: transform;
        /* CRITICAL: Respect EXIF orientation metadata for rotated photos */
        image-orientation: from-image !important;
      }

      /* Navigation buttons */
      .nav-btn {
        position: absolute;
        top: 50%;
        transform: translateY(-50%);
        z-index: 50;
        color: white;
        background: rgba(0, 0, 0, 0.5);
        width: 48px;
        height: 48px;
      }

      .nav-btn:hover:not(:disabled) {
        background: rgba(255, 255, 255, 0.2);
      }

      .nav-btn:disabled {
        opacity: 0.3;
      }

      .nav-prev {
        left: 16px;
      }

      .nav-next {
        right: 16px;
      }

      .nav-btn mat-icon {
        font-size: 32px;
        width: 32px;
        height: 32px;
      }

      /* Info bar */
      .info-bar {
        position: absolute;
        bottom: 0;
        left: 0;
        right: 0;
        padding: 16px;
        background: linear-gradient(transparent, rgba(0, 0, 0, 0.8));
        display: flex;
        justify-content: space-between;
        align-items: center;
        color: white;
      }

      .photo-label {
        font-size: 14px;
        font-weight: 500;
      }

      .counter {
        font-size: 12px;
        opacity: 0.8;
      }

      /* Zoom controls */
      .zoom-controls {
        position: absolute;
        bottom: 70px;
        left: 50%;
        transform: translateX(-50%);
        display: flex;
        align-items: center;
        gap: 8px;
        background: rgba(0, 0, 0, 0.6);
        border-radius: 24px;
        padding: 4px 12px;
      }

      .zoom-controls button {
        color: white;
      }

      .zoom-controls button:disabled {
        opacity: 0.3;
      }

      .zoom-level {
        color: white;
        font-size: 12px;
        min-width: 45px;
        text-align: center;
      }

      /* Trust Badge - EXIF Validation Status */
      .trust-badge {
        position: absolute;
        top: 16px;
        left: 16px;
        z-index: 100;
        display: flex;
        align-items: center;
        gap: 6px;
        padding: 8px 12px;
        border-radius: 20px;
        font-size: 12px;
        font-weight: 500;
        background: rgba(0, 0, 0, 0.6);
        color: white;
        backdrop-filter: blur(4px);
      }

      .trust-badge.verified {
        background: rgba(34, 197, 94, 0.9);
        color: white;
      }

      .trust-badge.verified mat-icon {
        color: white;
      }

      .trust-badge.warning {
        background: rgba(251, 191, 36, 0.9);
        color: #1a1a1a;
      }

      .trust-badge.warning mat-icon {
        color: #1a1a1a;
      }

      .trust-badge.rejected {
        background: rgba(239, 68, 68, 0.9);
        color: white;
      }

      .trust-badge mat-icon {
        font-size: 16px;
        width: 16px;
        height: 16px;
      }

      .trust-text {
        white-space: nowrap;
      }

      .trust-time {
        font-size: 10px;
        opacity: 0.85;
        margin-left: 4px;
      }

      /* Mobile optimizations */
      @media (max-width: 600px) {
        .nav-btn {
          width: 40px;
          height: 40px;
        }

        .nav-prev {
          left: 8px;
        }

        .nav-next {
          right: 8px;
        }

        .zoom-controls {
          bottom: 80px;
        }
      }
    `,
  ],
})
export class PhotoViewerDialogComponent {
  photos: PhotoViewerPhoto[];

  // State signals
  readonly currentIndex = signal(0);
  readonly scale = signal(1);
  readonly translateX = signal(0);
  readonly translateY = signal(0);

  // Touch tracking for gestures
  private touchStartX = 0;
  private touchStartY = 0;
  private lastTouchDistance = 0;
  private isPanning = false;

  // Computed signals
  readonly currentPhoto = computed(() => this.photos[this.currentIndex()]);

  readonly imageTransform = computed(
    () => `scale(${this.scale()}) translate(${this.translateX()}px, ${this.translateY()}px)`
  );

  // Trust Badge computed signals for EXIF validation
  readonly isVerified = computed(() => {
    const status = this.currentPhoto()?.exifValidationStatus;
    return status === 'VALID' || status === 'VALID_NO_GPS';
  });

  readonly hasWarning = computed(() => {
    const status = this.currentPhoto()?.exifValidationStatus;
    return status === 'VALID_WITH_WARNINGS' || status === 'PENDING';
  });

  readonly isRejected = computed(() => {
    const status = this.currentPhoto()?.exifValidationStatus;
    return status?.startsWith('REJECTED') ?? false;
  });

  readonly trustBadgeIcon = computed((): string => {
    const status = this.currentPhoto()?.exifValidationStatus;
    switch (status) {
      case 'VALID':
      case 'VALID_NO_GPS':
        return 'verified';
      case 'VALID_WITH_WARNINGS':
      case 'PENDING':
        return 'warning';
      case 'OVERRIDE_APPROVED':
        return 'admin_panel_settings';
      default:
        if (status?.startsWith('REJECTED')) {
          return 'error';
        }
        return 'help_outline';
    }
  });

  readonly trustBadgeText = computed((): string => {
    const status = this.currentPhoto()?.exifValidationStatus;
    switch (status) {
      case 'VALID':
        return 'Verifikovano uživo';
      case 'VALID_NO_GPS':
        return 'Verifikovano (bez GPS)';
      case 'VALID_WITH_WARNINGS':
        return 'Upozorenja';
      case 'PENDING':
        return 'Čeka validaciju';
      case 'OVERRIDE_APPROVED':
        return 'Admin odobrio';
      case 'REJECTED_TOO_OLD':
        return 'Slika prestara';
      case 'REJECTED_NO_EXIF':
        return 'Bez metapodataka';
      case 'REJECTED_LOCATION_MISMATCH':
        return 'Pogrešna lokacija';
      case 'REJECTED_NO_GPS':
        return 'Bez GPS-a';
      case 'REJECTED_FUTURE_TIMESTAMP':
        return 'Nevalidan datum';
      default:
        return 'Nepoznat status';
    }
  });

  // Photo type labels in Serbian
  private readonly photoTypeLabels: Record<string, string> = {
    HOST_EXTERIOR_FRONT: 'Prednja strana',
    HOST_EXTERIOR_REAR: 'Zadnja strana',
    HOST_EXTERIOR_LEFT: 'Leva strana',
    HOST_EXTERIOR_RIGHT: 'Desna strana',
    HOST_INTERIOR_DASHBOARD: 'Instrument tabla',
    HOST_INTERIOR_REAR: 'Zadnja sedišta',
    HOST_ODOMETER: 'Kilometraža',
    HOST_FUEL_GAUGE: 'Nivo goriva',
    HOST_DAMAGE_PREEXISTING: 'Postojeća oštećenja',
  };

  constructor(
    @Inject(MAT_DIALOG_DATA) data: PhotoViewerDialogData,
    private dialogRef: MatDialogRef<PhotoViewerDialogComponent>
  ) {
    this.photos = data.photos;
    this.currentIndex.set(Math.max(0, Math.min(data.startIndex, data.photos.length - 1)));
  }

  // ========== KEYBOARD NAVIGATION ==========

  @HostListener('document:keydown', ['$event'])
  onKeydown(event: KeyboardEvent): void {
    switch (event.key) {
      case 'ArrowLeft':
        this.prev();
        break;
      case 'ArrowRight':
        this.next();
        break;
      case 'Escape':
        this.close();
        break;
      case '+':
      case '=':
        this.zoomIn();
        break;
      case '-':
        this.zoomOut();
        break;
    }
  }

  // ========== NAVIGATION ==========

  next(): void {
    if (this.currentIndex() < this.photos.length - 1) {
      this.currentIndex.update((i) => i + 1);
      this.resetZoom();
    }
  }

  prev(): void {
    if (this.currentIndex() > 0) {
      this.currentIndex.update((i) => i - 1);
      this.resetZoom();
    }
  }

  close(): void {
    this.dialogRef.close();
  }

  // ========== ZOOM CONTROLS ==========

  zoomIn(): void {
    const newScale = Math.min(this.scale() + 0.5, 4);
    this.scale.set(newScale);
  }

  zoomOut(): void {
    const newScale = Math.max(this.scale() - 0.5, 1);
    this.scale.set(newScale);
    if (newScale === 1) {
      this.resetTranslate();
    }
  }

  onDoubleTap(): void {
    if (this.scale() > 1) {
      this.resetZoom();
    } else {
      this.scale.set(2);
    }
  }

  onWheel(event: WheelEvent): void {
    event.preventDefault();
    const delta = event.deltaY > 0 ? -0.2 : 0.2;
    const newScale = Math.max(1, Math.min(4, this.scale() + delta));
    this.scale.set(newScale);

    if (newScale === 1) {
      this.resetTranslate();
    }
  }

  // ========== TOUCH GESTURES ==========

  onTouchStart(event: TouchEvent): void {
    if (event.touches.length === 1) {
      // Single touch - prepare for swipe or pan
      this.touchStartX = event.touches[0].clientX;
      this.touchStartY = event.touches[0].clientY;
      this.isPanning = this.scale() > 1;
    } else if (event.touches.length === 2) {
      // Two finger - pinch zoom
      this.lastTouchDistance = this.getTouchDistance(event.touches);
    }
  }

  onTouchMove(event: TouchEvent): void {
    event.preventDefault();

    if (event.touches.length === 1 && this.isPanning) {
      // Pan when zoomed
      const deltaX = (event.touches[0].clientX - this.touchStartX) / this.scale();
      const deltaY = (event.touches[0].clientY - this.touchStartY) / this.scale();
      this.translateX.update((x) => x + deltaX);
      this.translateY.update((y) => y + deltaY);
      this.touchStartX = event.touches[0].clientX;
      this.touchStartY = event.touches[0].clientY;
    } else if (event.touches.length === 2) {
      // Pinch zoom
      const distance = this.getTouchDistance(event.touches);
      const scaleDelta = distance / this.lastTouchDistance;
      const newScale = Math.max(1, Math.min(4, this.scale() * scaleDelta));
      this.scale.set(newScale);
      this.lastTouchDistance = distance;
    }
  }

  onTouchEnd(): void {
    if (!this.isPanning && this.scale() === 1) {
      // Check for swipe gesture
      // Swipe handling could be added here if needed
    }
    this.isPanning = false;
  }

  // ========== HELPERS ==========

  formatPhotoType(photoType: string | undefined): string {
    if (!photoType) return '';
    return this.photoTypeLabels[photoType] ?? photoType.replace(/_/g, ' ');
  }

  private resetZoom(): void {
    this.scale.set(1);
    this.resetTranslate();
  }

  private resetTranslate(): void {
    this.translateX.set(0);
    this.translateY.set(0);
  }

  private getTouchDistance(touches: TouchList): number {
    const dx = touches[0].clientX - touches[1].clientX;
    const dy = touches[0].clientY - touches[1].clientY;
    return Math.sqrt(dx * dx + dy * dy);
  }
}
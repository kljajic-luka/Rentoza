/**
 * Guided Photo Capture Component (Refactored)
 *
 * Enterprise-grade photo capture using native camera with:
 * - Pre-capture guidance (silhouettes, checklists, distance hints)
 * - Native device camera (more reliable, better UX)
 * - Post-capture verification with retake option
 * - Progress tracking across all required photos
 * - Dark/Light mode support
 *
 * Uses the same pattern as host check-in for consistency.
 *
 * @since Enterprise Upgrade Phase 2.1
 */

import {
  Component,
  Input,
  Output,
  EventEmitter,
  OnInit,
  OnDestroy,
  OnChanges,
  SimpleChanges,
  inject,
  signal,
  computed,
  effect,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { PhotoGuidanceService } from '../../../../core/services/photo-guidance.service';
import { ThemeService } from '../../../../core/services/theme.service';
import { CheckInPhotoType } from '../../../../core/models/check-in.model';
import {
  PhotoGuidanceDTO,
  GuestCheckInPhotoSubmissionDTO,
} from '../../../../core/models/photo-guidance.model';
import {
  CheckInPersistenceService,
  CaptureState,
  PersistedPhoto,
} from '../../../../core/services/check-in-persistence.service';

type CapturePhase = 'guidance' | 'captured';

interface PhotoCaptureState {
  blob: Blob | null;
  previewUrl: string | null;
  verified: boolean;
}

@Component({
  selector: 'app-guided-photo-capture',
  standalone: true,
  imports: [
    CommonModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatCardModule,
    MatChipsModule,
    MatSnackBarModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="guided-capture-container" [class.dark-mode]="isDarkMode()">
      @if (currentGuidance(); as guidance) {
      <!-- Header with Progress -->
      <header class="capture-header">
        <div class="progress-info">
          <span class="step-indicator">
            Korak {{ currentIndex() + 1 }} od {{ totalPhotos() }}
          </span>
          @if (guidance.category) {
          <span class="category-badge" [attr.data-category]="guidance.category">
            {{ getCategoryLabel(guidance.category) }}
          </span>
          }
        </div>
        <mat-progress-bar
          mode="determinate"
          [value]="overallProgress()"
          color="primary"
        ></mat-progress-bar>
      </header>

      <!-- Main Content Area -->
      <main class="capture-main">
        @switch (currentPhase()) {
        <!-- Phase 1: Pre-Capture Guidance -->
        @case ('guidance') {
        <div class="guidance-phase">
          <!-- Photo Type Title -->
          <div class="photo-header">
            <mat-icon class="photo-icon">{{ getPhotoIcon() }}</mat-icon>
            <h2>{{ guidance.displayName }}</h2>
          </div>

          <!-- Silhouette Preview Card -->
          <div class="silhouette-card">
            @if (guidance.silhouetteUrl) {
            <img
              [src]="guidance.silhouetteUrl"
              alt="Primer pravilnog kadra"
              class="silhouette-image"
            />
            } @else {
            <div class="silhouette-placeholder">
              <mat-icon>photo_camera</mat-icon>
            </div>
            }
            <div class="silhouette-label">Primer pravilnog kadra</div>
          </div>

          <!-- Instructions -->
          <p class="instructions">{{ guidance.instructionsSr }}</p>

          <!-- Distance Hint -->
          @if (guidance.minDistanceMeters) {
          <div class="hint-card distance-hint">
            <mat-icon>straighten</mat-icon>
            <div class="hint-content">
              <span class="hint-label">Preporučena udaljenost</span>
              <span class="hint-value">
                {{ guidance.minDistanceMeters }} -
                {{ guidance.maxDistanceMeters }} metara
              </span>
            </div>
          </div>
          }

          <!-- Visibility Checklist -->
          @if (guidance.visibilityChecklistSr.length) {
          <div class="checklist-section">
            <span class="checklist-title">
              <mat-icon>checklist</mat-icon>
              Proverite da je vidljivo:
            </span>
            <div class="checklist-items">
              @for (item of guidance.visibilityChecklistSr; track item) {
              <mat-chip highlighted>
                <mat-icon matChipAvatar>check_circle</mat-icon>
                {{ item }}
              </mat-chip>
              }
            </div>
          </div>
          }

          <!-- Common Mistakes Warning -->
          @if (guidance.commonMistakesSr.length) {
          <div class="warning-card">
            <div class="warning-header">
              <mat-icon>warning</mat-icon>
              <span>Česte greške - izbegavajte:</span>
            </div>
            <ul class="warning-list">
              @for (mistake of guidance.commonMistakesSr; track mistake) {
              <li>{{ mistake }}</li>
              }
            </ul>
          </div>
          }

          <!-- Capture Button -->
          <button mat-fab extended color="primary" (click)="openCamera()" class="capture-button">
            <mat-icon>photo_camera</mat-icon>
            Otvori kameru
          </button>

          <!-- Hidden file input for native camera -->
          <input
            type="file"
            accept="image/*"
            capture="environment"
            [id]="'camera-input-' + guidance.photoType"
            (change)="onPhotoSelected($event)"
            hidden
          />
        </div>
        }

        <!-- Phase 2: Post-Capture Verification -->
        @case ('captured') {
        <div class="verification-phase">
          <div class="photo-header">
            <h2>Proverite fotografiju</h2>
            <p class="subtitle">{{ guidance.displayName }}</p>
          </div>

          <!-- Captured Photo Preview -->
          <div class="preview-container">
            @if (capturedPreviewUrl()) {
            <img
              [src]="capturedPreviewUrl()"
              alt="Snimljena fotografija"
              class="captured-preview"
            />
            }
          </div>

          <!-- Verification Checklist -->
          @if (guidance.visibilityChecklistSr.length) {
          <div class="verification-checklist">
            <span class="verification-title">Da li fotografija prikazuje:</span>
            <div class="checklist-items verification">
              @for (item of guidance.visibilityChecklistSr; track item) {
              <div class="verification-item">
                <mat-icon>check</mat-icon>
                <span>{{ item }}</span>
              </div>
              }
            </div>
          </div>
          }

          <!-- Action Buttons -->
          <div class="verification-actions">
            <button mat-stroked-button (click)="retakePhoto()" class="retake-button">
              <mat-icon>refresh</mat-icon>
              Ponovi snimanje
            </button>

            <button mat-flat-button color="primary" (click)="confirmPhoto()" class="confirm-button">
              <mat-icon>check</mat-icon>
              {{ isLastPhoto() ? 'Potvrdi i završi' : 'Potvrdi i nastavi' }}
            </button>
          </div>
        </div>
        } }
      </main>

      } @else {
      <main class="capture-main">
        <div class="guidance-phase">
          <div class="photo-header">
            <mat-icon class="photo-icon">hourglass_top</mat-icon>
            <h2>Učitavanje uputstava</h2>
          </div>
        </div>
      </main>
      }
      <!-- Navigation Pills -->
      <nav class="navigation-pills" aria-label="Photo navigation">
        @for (guidance of allGuidance(); track guidance.photoType; let i = $index) {
        <button
          class="nav-pill"
          [class.active]="i === currentIndex()"
          [class.completed]="isPhotoCompleted(guidance.photoType)"
          (click)="goToPhoto(i)"
          [attr.aria-label]="guidance.displayName"
          [attr.aria-current]="i === currentIndex() ? 'step' : null"
        >
          @if (isPhotoCompleted(guidance.photoType)) {
          <mat-icon>check</mat-icon>
          } @else {
          {{ i + 1 }}
          }
        </button>
        }
      </nav>

      <!-- PHASE 1 IMPROVEMENT: Completion Progress Checklist -->
      <section class="completion-checklist" aria-label="Status fotografija">
        <div class="checklist-header">
          <mat-icon class="checklist-icon">photo_library</mat-icon>
          <span class="checklist-title"
            >Napredak: {{ completedPhotosCount() }}/{{ totalPhotos() }}</span
          >
        </div>
        <div class="checklist-grid">
          @for (status of photoTypeStatusList(); track status.type) {
          <div
            class="checklist-item"
            [class.completed]="status.completed"
            [class.current]="status.isCurrent"
          >
            <mat-icon class="status-icon">
              {{
                status.completed
                  ? 'check_circle'
                  : status.isCurrent
                  ? 'radio_button_checked'
                  : 'radio_button_unchecked'
              }}
            </mat-icon>
            <span class="type-name">{{ status.displayName }}</span>
          </div>
          }
        </div>
      </section>

      <!-- Footer with Cancel -->
      <footer class="capture-footer">
        <button mat-button (click)="cancelCapture()" class="cancel-button">
          <mat-icon>close</mat-icon>
          Otkaži snimanje
        </button>
      </footer>
    </div>
  `,
  styles: [
    `
      /* ========== BASE CONTAINER ========== */
      .guided-capture-container {
        display: flex;
        flex-direction: column;
        min-height: 100dvh;
        background: var(--mat-app-background-color, #fafafa);
        color: var(--mat-app-text-color, rgba(0, 0, 0, 0.87));
        padding: 16px;
        padding-bottom: max(16px, env(safe-area-inset-bottom));
        box-sizing: border-box;
      }

      /* Dark mode overrides */
      .guided-capture-container.dark-mode {
        background: #121212;
        color: rgba(255, 255, 255, 0.87);
      }

      /* ========== HEADER ========== */
      .capture-header {
        margin-bottom: 20px;
      }

      .progress-info {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: 12px;
      }

      .step-indicator {
        font-size: 16px;
        font-weight: 600;
        color: inherit;
      }

      .category-badge {
        font-size: 12px;
        font-weight: 500;
        text-transform: uppercase;
        letter-spacing: 0.5px;
        padding: 4px 12px;
        border-radius: 16px;
        background: rgba(0, 0, 0, 0.08);
      }

      .dark-mode .category-badge {
        background: rgba(255, 255, 255, 0.12);
      }

      .category-badge[data-category='exterior'] {
        background: rgba(33, 150, 243, 0.15);
        color: var(--brand-primary);
      }

      .category-badge[data-category='interior'] {
        background: rgba(156, 39, 176, 0.15);
        color: #7b1fa2;
      }

      .category-badge[data-category='reading'] {
        background: rgba(76, 175, 80, 0.15);
        color: #388e3c;
      }

      .dark-mode .category-badge[data-category='exterior'] {
        background: rgba(33, 150, 243, 0.25);
        color: #64b5f6;
      }

      .dark-mode .category-badge[data-category='interior'] {
        background: rgba(156, 39, 176, 0.25);
        color: #ce93d8;
      }

      .dark-mode .category-badge[data-category='reading'] {
        background: rgba(76, 175, 80, 0.25);
        color: #81c784;
      }

      /* ========== MAIN CONTENT ========== */
      .capture-main {
        flex: 1;
        display: flex;
        flex-direction: column;
        overflow-y: auto;
        -webkit-overflow-scrolling: touch;
      }

      .guidance-phase,
      .verification-phase {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 16px;
      }

      /* ========== PHOTO HEADER ========== */
      .photo-header {
        text-align: center;
        margin-bottom: 8px;
      }

      .photo-icon {
        font-size: 40px;
        width: 40px;
        height: 40px;
        color: var(--brand-primary);
        margin-bottom: 8px;
      }

      .photo-header h2 {
        margin: 0;
        font-size: 24px;
        font-weight: 600;
        line-height: 1.3;
      }

      .photo-header .subtitle {
        margin: 4px 0 0;
        font-size: 14px;
        opacity: 0.7;
      }

      /* ========== SILHOUETTE CARD ========== */
      .silhouette-card {
        width: 100%;
        max-width: 320px;
        aspect-ratio: 4/3;
        /* Dark background for white SVG silhouettes */
        background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
        border-radius: 16px;
        overflow: hidden;
        position: relative;
        display: flex;
        align-items: center;
        justify-content: center;
        border: 2px solid rgba(255, 255, 255, 0.1);
        box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
      }

      .dark-mode .silhouette-card {
        background: linear-gradient(135deg, #0d1117 0%, #161b22 100%);
        border-color: rgba(255, 255, 255, 0.15);
      }

      .silhouette-image {
        max-width: 90%;
        max-height: 90%;
        object-fit: contain;
        filter: drop-shadow(0 2px 4px rgba(0, 0, 0, 0.3));
      }

      .silhouette-placeholder {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 8px;
        color: rgba(255, 255, 255, 0.5);
      }

      .dark-mode .silhouette-placeholder {
        color: rgba(255, 255, 255, 0.4);
      }

      .silhouette-placeholder mat-icon {
        font-size: 64px;
        width: 64px;
        height: 64px;
      }

      .silhouette-label {
        position: absolute;
        bottom: 0;
        left: 0;
        right: 0;
        padding: 8px;
        background: rgba(0, 0, 0, 0.6);
        color: white;
        text-align: center;
        font-size: 12px;
        font-weight: 500;
      }

      /* ========== INSTRUCTIONS ========== */
      .instructions {
        text-align: center;
        font-size: 15px;
        line-height: 1.6;
        opacity: 0.85;
        max-width: 400px;
        margin: 0;
      }

      /* ========== HINT CARDS ========== */
      .hint-card {
        display: flex;
        align-items: center;
        gap: 12px;
        padding: 12px 16px;
        background: rgba(33, 150, 243, 0.08);
        border-radius: 12px;
        width: 100%;
        max-width: 400px;
      }

      .dark-mode .hint-card {
        background: rgba(33, 150, 243, 0.15);
      }

      .hint-card mat-icon {
        color: var(--brand-primary);
        flex-shrink: 0;
      }

      .hint-content {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }

      .hint-label {
        font-size: 12px;
        opacity: 0.7;
      }

      .hint-value {
        font-size: 15px;
        font-weight: 600;
      }

      /* ========== CHECKLIST ========== */
      .checklist-section {
        width: 100%;
        max-width: 400px;
      }

      .checklist-title {
        display: flex;
        align-items: center;
        gap: 8px;
        font-size: 14px;
        font-weight: 500;
        margin-bottom: 12px;
        opacity: 0.9;
      }

      .checklist-title mat-icon {
        font-size: 20px;
        width: 20px;
        height: 20px;
        color: #4caf50;
      }

      .checklist-items {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
      }

      .checklist-items mat-chip {
        --mdc-chip-label-text-size: 13px;
      }

      /* ========== WARNING CARD ========== */
      .warning-card {
        width: 100%;
        max-width: 400px;
        background: rgba(255, 152, 0, 0.1);
        border-left: 4px solid #ff9800;
        border-radius: 0 12px 12px 0;
        padding: 12px 16px;
      }

      .dark-mode .warning-card {
        background: rgba(255, 152, 0, 0.15);
      }

      .warning-header {
        display: flex;
        align-items: center;
        gap: 8px;
        font-weight: 600;
        color: #e65100;
        margin-bottom: 8px;
      }

      .dark-mode .warning-header {
        color: #ffb74d;
      }

      .warning-list {
        margin: 0;
        padding-left: 24px;
        font-size: 14px;
        line-height: 1.6;
      }

      .warning-list li {
        margin-bottom: 4px;
      }

      .warning-list li:last-child {
        margin-bottom: 0;
      }

      /* ========== CAPTURE BUTTON ========== */
      .capture-button {
        margin-top: 24px;
        min-width: 200px;
        height: 56px;
        font-size: 16px;
        font-weight: 600;
      }

      /* ========== PREVIEW CONTAINER ========== */
      .preview-container {
        width: 100%;
        max-width: 400px;
        aspect-ratio: 4/3;
        background: #000;
        border-radius: 16px;
        overflow: hidden;
        position: relative;
      }

      .captured-preview {
        width: 100%;
        height: 100%;
        object-fit: cover;
        /* CRITICAL: Respect EXIF orientation metadata for rotated photos */
        image-orientation: from-image;
      }

      /* ========== VERIFICATION ========== */
      .verification-checklist {
        width: 100%;
        max-width: 400px;
        padding: 16px;
        background: rgba(76, 175, 80, 0.08);
        border-radius: 12px;
      }

      .dark-mode .verification-checklist {
        background: rgba(76, 175, 80, 0.15);
      }

      .verification-title {
        display: block;
        font-size: 14px;
        font-weight: 500;
        margin-bottom: 12px;
      }

      .checklist-items.verification {
        display: flex;
        flex-direction: column;
        gap: 8px;
      }

      .verification-item {
        display: flex;
        align-items: center;
        gap: 8px;
        font-size: 14px;
      }

      .verification-item mat-icon {
        color: #4caf50;
        font-size: 20px;
        width: 20px;
        height: 20px;
      }

      .verification-actions {
        display: flex;
        gap: 16px;
        width: 100%;
        max-width: 400px;
        justify-content: center;
        margin-top: 16px;
      }

      .retake-button {
        min-width: 140px;
      }

      .confirm-button {
        min-width: 180px;
        height: 48px;
      }

      /* ========== NAVIGATION PILLS ========== */
      .navigation-pills {
        display: flex;
        justify-content: center;
        gap: 8px;
        flex-wrap: wrap;
        margin: 24px 0 16px;
        padding: 0 8px;
      }

      .nav-pill {
        width: 36px;
        height: 36px;
        border-radius: 50%;
        border: 2px solid rgba(0, 0, 0, 0.2);
        background: transparent;
        color: inherit;
        font-size: 14px;
        font-weight: 600;
        cursor: pointer;
        display: flex;
        align-items: center;
        justify-content: center;
        transition: all 0.2s ease;
      }

      .dark-mode .nav-pill {
        border-color: rgba(255, 255, 255, 0.3);
      }

      .nav-pill:hover:not(.completed):not(.active) {
        background: rgba(0, 0, 0, 0.05);
      }

      .dark-mode .nav-pill:hover:not(.completed):not(.active) {
        background: rgba(255, 255, 255, 0.1);
      }

      .nav-pill.active {
        border-color: var(--brand-primary);
        background: var(--brand-primary);
        color: white;
      }

      .nav-pill.completed {
        border-color: #4caf50;
        background: #4caf50;
        color: white;
      }

      .nav-pill mat-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
      }

      /* ========== COMPLETION CHECKLIST (Phase 1 Improvement) ========== */
      .completion-checklist {
        background: var(--mat-card-background-color, #fff);
        border-radius: 12px;
        padding: 16px;
        margin: 16px 0;
        box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
      }

      .dark-mode .completion-checklist {
        background: #1e1e1e;
        box-shadow: 0 1px 3px rgba(0, 0, 0, 0.3);
      }

      .checklist-header {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-bottom: 12px;
        padding-bottom: 8px;
        border-bottom: 1px solid rgba(0, 0, 0, 0.1);
      }

      .dark-mode .checklist-header {
        border-bottom-color: rgba(255, 255, 255, 0.1);
      }

      .checklist-icon {
        color: var(--mat-primary-color, #3f51b5);
        font-size: 20px;
        width: 20px;
        height: 20px;
      }

      .checklist-title {
        font-weight: 500;
        font-size: 14px;
      }

      .checklist-grid {
        display: grid;
        grid-template-columns: repeat(2, 1fr);
        gap: 8px;
      }

      @media (min-width: 600px) {
        .checklist-grid {
          grid-template-columns: repeat(4, 1fr);
        }
      }

      .checklist-item {
        display: flex;
        align-items: center;
        gap: 6px;
        padding: 8px;
        border-radius: 8px;
        background: rgba(0, 0, 0, 0.03);
        transition: all 0.2s ease;
        font-size: 12px;
      }

      .dark-mode .checklist-item {
        background: rgba(255, 255, 255, 0.05);
      }

      .checklist-item.completed {
        background: rgba(76, 175, 80, 0.1);
      }

      .checklist-item.completed .status-icon {
        color: #4caf50;
      }

      .checklist-item.current {
        background: rgba(63, 81, 181, 0.1);
        border: 1px solid var(--mat-primary-color, #3f51b5);
      }

      .checklist-item.current .status-icon {
        color: var(--mat-primary-color, #3f51b5);
      }

      .checklist-item .status-icon {
        font-size: 16px;
        width: 16px;
        height: 16px;
        color: rgba(0, 0, 0, 0.3);
      }

      .dark-mode .checklist-item .status-icon {
        color: rgba(255, 255, 255, 0.3);
      }

      .checklist-item .type-name {
        flex: 1;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      /* ========== FOOTER ========== */
      .capture-footer {
        display: flex;
        justify-content: center;
        padding-top: 8px;
      }

      .cancel-button {
        opacity: 0.7;
      }

      .cancel-button:hover {
        opacity: 1;
      }

      /* ========== RESPONSIVE ========== */
      @media (max-width: 480px) {
        .guided-capture-container {
          padding: 12px;
        }

        .photo-header h2 {
          font-size: 20px;
        }

        .silhouette-card {
          max-width: 280px;
        }

        .instructions {
          font-size: 14px;
        }

        .capture-button {
          width: 100%;
          max-width: 300px;
        }

        .verification-actions {
          flex-direction: column;
          gap: 12px;
        }

        .retake-button,
        .confirm-button {
          width: 100%;
        }

        .completion-checklist {
          padding: 12px;
        }

        .checklist-item {
          font-size: 11px;
          padding: 6px;
        }
      }

      /* ========== ANIMATIONS ========== */
      @keyframes fadeIn {
        from {
          opacity: 0;
          transform: translateY(10px);
        }
        to {
          opacity: 1;
          transform: translateY(0);
        }
      }

      .guidance-phase,
      .verification-phase {
        animation: fadeIn 0.3s ease-out;
      }
    `,
  ],
})
export class GuidedPhotoCaptureComponent implements OnInit, OnDestroy, OnChanges {
  @Input() bookingId!: number;
  @Input() mode: 'guest-checkin' | 'host-checkin' | 'host-checkout' = 'guest-checkin';

  /** Restored capture state from persistence service (passed from parent) */
  @Input() restoredState?: CaptureState;
  @Input() preCompletedPhotoTypes: CheckInPhotoType[] = [];

  // Flag to prevent double initialization
  private hasInitializedFromRestore = false;

  @Output() captureComplete = new EventEmitter<GuestCheckInPhotoSubmissionDTO>();
  @Output() captureCancelled = new EventEmitter<void>();

  protected readonly guidanceService = inject(PhotoGuidanceService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly persistenceService = inject(CheckInPersistenceService);
  private readonly themeService = inject(ThemeService);

  // Captured photos storage
  private readonly capturedPhotos = signal<Map<CheckInPhotoType, PhotoCaptureState>>(new Map());

  // Current capture state
  protected readonly currentPhase = signal<CapturePhase>('guidance');
  protected readonly capturedPreviewUrl = signal<string | null>(null);
  private capturedBlob: Blob | null = null;

  // Dark mode detection
  protected readonly isDarkMode = signal(false);

  // Keep guided capture theme in sync with app theme toggle (not OS preference).
  private readonly darkModeSyncEffect = effect(() => {
    this.isDarkMode.set(this.themeService.theme() === 'dark');
  });

  // Computed from guidance service
  protected readonly currentGuidance = this.guidanceService.currentGuidance;
  protected readonly currentIndex = this.guidanceService.currentIndex;
  protected readonly allGuidance = this.guidanceService.captureSequence;
  protected readonly totalPhotos = computed(() => this.allGuidance().length);
  private readonly preCompletedPhotoTypeSet = computed(
    () => new Set<CheckInPhotoType>(this.preCompletedPhotoTypes),
  );

  // PHASE 1 IMPROVEMENT: Photo type status for completion checklist
  protected readonly photoTypeStatusList = computed(() => {
    const guidance = this.allGuidance();
    const captured = this.capturedPhotos();
    const currentIdx = this.currentIndex();

    return guidance.map((g, idx) => {
      const state = captured.get(g.photoType);
      return {
        type: g.photoType,
        displayName: g.displayName,
        completed: state?.verified === true || this.preCompletedPhotoTypeSet().has(g.photoType),
        isCurrent: idx === currentIdx,
      };
    });
  });

  // PHASE 1 IMPROVEMENT: Count of completed photos
  protected readonly completedPhotosCount = computed(() => {
    const captured = this.capturedPhotos();
    let count = 0;
    captured.forEach((state) => {
      if (state.verified) count++;
    });
    return count + this.preCompletedPhotoTypeSet().size;
  });

  // Progress computation
  protected readonly overallProgress = computed(() => {
    const total = this.totalPhotos();
    if (total === 0) return 0;
    const completed = this.getCompletedCount();
    return (completed / total) * 100;
  });

  // Auto-save effect: persists state after each photo capture
  private readonly autoSaveEffect = effect(() => {
    // Track capturedPhotos signal
    const photos = this.capturedPhotos();

    // Only save if we have photos and a valid bookingId
    if (photos.size > 0 && this.bookingId) {
      this.persistCaptureState();
    }
  });

  /**
   * Handle Input changes - especially for restoredState which may arrive after ngOnInit
   * due to conditional rendering (@if blocks) in parent templates.
   */
  ngOnChanges(changes: SimpleChanges): void {
    // If restoredState is set after init and we haven't already restored
    if (
      changes['restoredState'] &&
      !changes['restoredState'].firstChange &&
      changes['restoredState'].currentValue &&
      !this.hasInitializedFromRestore
    ) {
      console.log('[GuidedCapture] restoredState arrived via ngOnChanges, initializing...');
      this.initializeWithRestoredState();
    }
  }

  ngOnInit(): void {
    // Check if we have restored state to apply (may already be set via @Input)
    if (
      this.restoredState &&
      this.restoredState.capturedPhotos.length > 0 &&
      !this.hasInitializedFromRestore
    ) {
      console.log('[GuidedCapture] restoredState available in ngOnInit, initializing...');
      this.initializeWithRestoredState();
      return;
    }

    // Start the capture sequence based on mode (only if not restoring)
    this.startFreshCaptureSequence();
  }

  /**
   * Start a fresh capture sequence without any restored state.
   */
  private startFreshCaptureSequence(): void {
    switch (this.mode) {
      case 'guest-checkin':
        this.guidanceService.startGuestCheckInCapture().subscribe({
          next: () => {
            this.restoreState();
            this.goToFirstIncompletePhoto();
          },
          error: (err) => {
            this.snackBar.open('Greška pri učitavanju uputstava', 'OK', { duration: 3000 });
            console.error('Failed to start guest capture', err);
          },
        });
        break;

      case 'host-checkin':
        this.guidanceService.startHostCheckInCapture().subscribe({
          next: (sequence) => {
            console.log('[GuidedCapture] Host check-in sequence loaded:', sequence?.length);
            console.log('[GuidedCapture] Current guidance:', this.currentGuidance());
            console.log('[GuidedCapture] Silhouette URL:', this.currentGuidance()?.silhouetteUrl);
            this.restoreState();
            this.goToFirstIncompletePhoto();
          },
          error: (err) => {
            this.snackBar.open('Greška pri učitavanju uputstava', 'OK', { duration: 3000 });
            console.error('Failed to start host capture', err);
          },
        });
        break;

      case 'host-checkout':
        this.guidanceService.startCheckoutCapture().subscribe({
          next: () => {
            this.restoreState();
            this.goToFirstIncompletePhoto();
          },
          error: (err) => {
            this.snackBar.open('Greška pri učitavanju uputstava', 'OK', { duration: 3000 });
            console.error('Failed to start checkout capture', err);
          },
        });
        break;
    }
  }

  /**
   * Initialize component with restored state from persistence.
   * Converts persisted base64 photos back to Blobs and preview URLs.
   */
  private async initializeWithRestoredState(): Promise<void> {
    if (!this.restoredState) return;
    if (this.hasInitializedFromRestore) return; // Prevent double initialization

    this.hasInitializedFromRestore = true;

    console.log(
      `[GuidedCapture] Restoring ${this.restoredState.capturedPhotos.length} photos from persistence`
    );

    // Start the appropriate capture sequence first
    const startCapture$ =
      this.mode === 'guest-checkin'
        ? this.guidanceService.startGuestCheckInCapture()
        : this.mode === 'host-checkin'
        ? this.guidanceService.startHostCheckInCapture()
        : this.guidanceService.startCheckoutCapture();

    startCapture$.subscribe({
      next: async () => {
        // Restore captured photos from persisted state
        const restoredMap = new Map<CheckInPhotoType, PhotoCaptureState>();

        for (const photo of this.restoredState!.capturedPhotos) {
          const blob = this.persistenceService.base64ToBlob(photo.base64Data, photo.mimeType);
          const previewUrl = URL.createObjectURL(blob);

          restoredMap.set(photo.photoType, {
            blob,
            previewUrl,
            verified: photo.verified,
          });

          // Record in guidance service too
          this.guidanceService.recordCapture(photo.photoType, blob, previewUrl);
        }

        this.capturedPhotos.set(restoredMap);

        // Go to the saved index position
        if (this.restoredState!.currentIndex > 0) {
          this.guidanceService.goToPhoto(this.restoredState!.currentIndex);
        }

        this.goToFirstIncompletePhoto();

        // Restore current phase
        this.currentPhase.set(this.restoredState!.currentPhase);

        // Restore the current photo's preview if in captured phase
        const currentGuidance = this.currentGuidance();
        if (currentGuidance && this.restoredState!.currentPhase === 'captured') {
          const state = restoredMap.get(currentGuidance.photoType);
          if (state) {
            this.capturedPreviewUrl.set(state.previewUrl);
            this.capturedBlob = state.blob;
          }
        }

        this.snackBar.open(
          `Nastavak od ${this.restoredState!.capturedPhotos.length} sačuvanih fotografija`,
          'OK',
          { duration: 3000 }
        );
      },
      error: (err) => {
        console.error('[GuidedCapture] Failed to restore state:', err);
        this.snackBar.open('Greška pri vraćanju sesije', 'OK', { duration: 3000 });
      },
    });
  }

  private goToFirstIncompletePhoto(): void {
    const firstIncompleteIndex = this.allGuidance().findIndex(
      (guidance) => !this.isPhotoCompleted(guidance.photoType),
    );

    if (firstIncompleteIndex > 0) {
      this.guidanceService.goToPhoto(firstIncompleteIndex);
    }
  }

  /**
   * Persist current capture state to IndexedDB.
   * Called automatically via effect after each photo capture.
   */
  private async persistCaptureState(): Promise<void> {
    const photos = this.capturedPhotos();
    if (photos.size === 0) return;

    const persistedPhotos: PersistedPhoto[] = [];

    for (const [photoType, state] of photos) {
      if (state.blob && state.verified) {
        try {
          const base64 = await this.persistenceService.blobToBase64(state.blob);
          persistedPhotos.push({
            photoType,
            base64Data: base64,
            mimeType: state.blob.type || 'image/jpeg',
            capturedAt: new Date().toISOString(),
            verified: state.verified,
          });
        } catch (e) {
          console.error(`[GuidedCapture] Failed to convert photo ${photoType} to base64:`, e);
        }
      }
    }

    if (persistedPhotos.length > 0) {
      console.log(
        `[GuidedCapture] Persisting ${
          persistedPhotos.length
        } photos at index ${this.currentIndex()}, phase: ${this.currentPhase()}`
      );

      await this.persistenceService.saveCaptureState(
        this.bookingId,
        this.mode,
        this.currentIndex(),
        this.currentPhase(),
        persistedPhotos
      );

      console.log('[GuidedCapture] State persisted successfully');
    }
  }

  ngOnDestroy(): void {
    // Cleanup all preview URLs
    this.capturedPhotos().forEach((state) => {
      if (state.previewUrl) {
        URL.revokeObjectURL(state.previewUrl);
      }
    });

    if (this.capturedPreviewUrl()) {
      URL.revokeObjectURL(this.capturedPreviewUrl()!);
    }

    // Release persistence lock
    this.persistenceService.releaseLock(this.bookingId);

    this.guidanceService.endCapture();
  }

  // ========== CAPTURE FLOW ==========

  protected openCamera(): void {
    const guidance = this.currentGuidance();
    if (!guidance) return;

    const input = document.getElementById(`camera-input-${guidance.photoType}`) as HTMLInputElement;
    if (input) {
      input.click();
    }
  }

  protected onPhotoSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];

    if (!file) return;

    // Create preview URL
    const previewUrl = URL.createObjectURL(file);
    this.capturedPreviewUrl.set(previewUrl);

    // Store blob for later submission
    this.capturedBlob = file;

    // Transition to verification phase
    this.currentPhase.set('captured');

    // Reset input for potential retake
    input.value = '';
  }

  protected retakePhoto(): void {
    // Cleanup current preview
    if (this.capturedPreviewUrl()) {
      URL.revokeObjectURL(this.capturedPreviewUrl()!);
    }
    this.capturedPreviewUrl.set(null);
    this.capturedBlob = null;

    // Return to guidance phase
    this.currentPhase.set('guidance');
  }

  protected confirmPhoto(): void {
    const guidance = this.currentGuidance();
    if (!guidance || !this.capturedBlob) return;

    // Store the captured photo
    const state: PhotoCaptureState = {
      blob: this.capturedBlob,
      previewUrl: this.capturedPreviewUrl(),
      verified: true,
    };

    this.capturedPhotos.update((map) => {
      const newMap = new Map(map);
      newMap.set(guidance.photoType, state);
      return newMap;
    });

    // Record in guidance service
    this.guidanceService.recordCapture(
      guidance.photoType,
      this.capturedBlob,
      this.capturedPreviewUrl()!
    );

    // Clear current capture state
    this.capturedPreviewUrl.set(null);
    this.capturedBlob = null;

    // Check if this was the last photo
    if (this.isLastPhoto()) {
      this.finishCapture();
    } else {
      // Move to next photo FIRST (before persistence effect runs)
      this.guidanceService.nextPhoto();
      this.currentPhase.set('guidance');

      // Explicitly trigger persistence with updated position
      // This ensures the correct index is saved after navigation
      this.persistCaptureState();
    }
  }

  protected goToPhoto(index: number): void {
    // Save current state if we have a captured photo
    this.cleanupCurrentCapture();

    this.guidanceService.goToPhoto(index);

    // Restore state for the new photo
    this.restoreState();
  }

  protected cancelCapture(): void {
    this.guidanceService.reset();
    this.captureCancelled.emit();
  }

  // ========== STATE MANAGEMENT ==========

  private restoreState(): void {
    const guidance = this.currentGuidance();
    if (!guidance) return;

    const state = this.capturedPhotos().get(guidance.photoType);
    if (state && state.verified) {
      this.capturedPreviewUrl.set(state.previewUrl);
      this.capturedBlob = state.blob;
      this.currentPhase.set('captured');
    } else {
      this.currentPhase.set('guidance');
    }
  }

  private cleanupCurrentCapture(): void {
    // Don't cleanup if photo is already verified (stored in capturedPhotos)
    const guidance = this.currentGuidance();
    if (guidance) {
      const state = this.capturedPhotos().get(guidance.photoType);
      if (!state || !state.verified) {
        // Cleanup unverified capture
        if (this.capturedPreviewUrl()) {
          URL.revokeObjectURL(this.capturedPreviewUrl()!);
        }
        this.capturedPreviewUrl.set(null);
        this.capturedBlob = null;
      }
    }
  }

  // ========== HELPERS ==========

  protected isPhotoCompleted(photoType: CheckInPhotoType): boolean {
    const state = this.capturedPhotos().get(photoType);
    return state?.verified === true || this.preCompletedPhotoTypeSet().has(photoType);
  }

  protected isLastPhoto(): boolean {
    return this.currentIndex() === this.totalPhotos() - 1;
  }

  protected getCompletedCount(): number {
    return (
      Array.from(this.capturedPhotos().values()).filter((s) => s.verified).length +
      this.preCompletedPhotoTypeSet().size
    );
  }

  protected getCategoryLabel(category: string | undefined): string {
    switch (category) {
      case 'exterior':
        return 'Eksterijer';
      case 'interior':
        return 'Enterijer';
      case 'reading':
        return 'Očitavanje';
      default:
        return '';
    }
  }

  protected getPhotoIcon(): string {
    const category = this.currentGuidance()?.category;
    switch (category) {
      case 'exterior':
        return 'directions_car';
      case 'interior':
        return 'airline_seat_recline_normal';
      case 'reading':
        return 'speed';
      default:
        return 'photo_camera';
    }
  }

  // ========== FINISH CAPTURE ==========

  private async finishCapture(): Promise<void> {
    const photos = this.capturedPhotos();

    if (this.getCompletedCount() < this.totalPhotos()) {
      const missing = this.totalPhotos() - this.getCompletedCount();
      this.snackBar.open(`Nedostaje još ${missing} fotografija`, 'OK', { duration: 3000 });
      return;
    }

    // Build submission DTO
    const photoItems: GuestCheckInPhotoSubmissionDTO['photos'] = [];
    const preCompleted = this.preCompletedPhotoTypeSet();

    for (const [photoType, state] of photos) {
      if (preCompleted.has(photoType)) continue;
      if (!state.blob) continue;

      const base64 = await this.blobToBase64(state.blob);
      photoItems.push({
        photoType,
        base64Data: base64,
        filename: `${photoType}_${Date.now()}.jpg`,
        mimeType: 'image/jpeg',
        capturedAt: new Date().toISOString(),
      });
    }

    const submission: GuestCheckInPhotoSubmissionDTO = {
      photos: photoItems,
      clientCapturedAt: new Date().toISOString(),
      deviceInfo: navigator.userAgent,
    };

    this.guidanceService.endCapture();
    this.captureComplete.emit(submission);
  }

  private blobToBase64(blob: Blob): Promise<string> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onloadend = () => {
        const base64 = reader.result as string;
        // Remove data URL prefix
        const base64Data = base64.split(',')[1];
        resolve(base64Data);
      };
      reader.onerror = reject;
      reader.readAsDataURL(blob);
    });
  }
}
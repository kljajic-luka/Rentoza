/**
 * Host Check-In Component
 *
 * Handles the host's portion of check-in:
 * 1. Take 8 required photos (4 exterior, 2 interior, odometer, fuel)
 * 2. Enter odometer reading and fuel level
 * 3. Optional: Set lockbox code for remote handoff
 * 4. Submit for guest review
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
  OnInit,
  OnChanges,
  OnDestroy,
  SimpleChanges,
  DestroyRef,
  HostListener,
  effect,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, debounceTime } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatChipsModule } from '@angular/material/chips';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatSliderModule } from '@angular/material/slider';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';

import { environment } from '@environments/environment';
import { CheckInService } from '../../../core/services/check-in.service';
import { PhotoCompressionService } from '../../../core/services/photo-compression.service';
import { GeolocationService } from '../../../core/services/geolocation.service';
import { PhotoGuidanceService } from '../../../core/services/photo-guidance.service';
import {
  CheckInPersistenceService,
  CaptureState,
} from '../../../core/services/check-in-persistence.service';
import {
  CheckInStatusDTO,
  CheckInPhotoType,
  REQUIRED_HOST_PHOTOS,
  PhotoUploadProgress,
  DamagePhotoSlot,
  MAX_DAMAGE_PHOTOS,
  PhotoSlotViewModel,
  PhotoStatsViewModel,
  PhotoSlot,
} from '../../../core/models/check-in.model';
import { GuestCheckInPhotoSubmissionDTO } from '../../../core/models/photo-guidance.model';
import { LazyImgDirective } from '../../../shared/directives/lazy-img.directive';
import { generateUUID } from '../../../core/utils/uuid';
import { ReadOnlyPickupLocationComponent } from '../components/readonly-pickup-location/readonly-pickup-location.component';
import { PickupLocationData } from '../../../core/models/booking-details.model';
import { GuidedPhotoCaptureComponent } from './guided-photo-capture/guided-photo-capture.component';
import {
  CheckInRecoveryDialogComponent,
  RecoveryDialogData,
  RecoveryDialogResult,
} from './check-in-recovery-dialog/check-in-recovery-dialog.component';

const PHOTO_SLOTS: PhotoSlot[] = [
  { type: 'HOST_EXTERIOR_FRONT', label: 'Prednja strana', icon: 'directions_car', required: true },
  { type: 'HOST_EXTERIOR_REAR', label: 'Zadnja strana', icon: 'directions_car', required: true },
  { type: 'HOST_EXTERIOR_LEFT', label: 'Leva strana', icon: 'directions_car', required: true },
  { type: 'HOST_EXTERIOR_RIGHT', label: 'Desna strana', icon: 'directions_car', required: true },
  { type: 'HOST_INTERIOR_DASHBOARD', label: 'Instrument tabla', icon: 'dashboard', required: true },
  { type: 'HOST_INTERIOR_REAR', label: 'Zadnja sedišta', icon: 'event_seat', required: true },
  { type: 'HOST_ODOMETER', label: 'Kilometraža', icon: 'speed', required: true },
  { type: 'HOST_FUEL_GAUGE', label: 'Nivo goriva', icon: 'local_gas_station', required: true },
];

@Component({
  selector: 'app-host-check-in',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatInputModule,
    MatFormFieldModule,
    MatProgressBarModule,
    MatChipsModule,
    MatExpansionModule,
    MatSliderModule,
    MatSnackBarModule,
    MatDialogModule,
    LazyImgDirective,
    ReadOnlyPickupLocationComponent,
    GuidedPhotoCaptureComponent,
    CheckInRecoveryDialogComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="host-check-in" [class.readonly-mode]="readOnly">
      <!-- Read-only banner -->
      @if (readOnly) {
      <div class="readonly-banner">
        <mat-icon>visibility</mat-icon>
        <span>Pregled unetih podataka (samo za čitanje)</span>
      </div>
      }

      <!-- Phase 4A: Check-in Timing Restriction Banner -->
      @if (status?.timingBlocked) {
      <div class="timing-blocked-banner">
        <mat-icon>schedule</mat-icon>
        <div class="timing-content">
          <span class="timing-title">Check-in još nije dozvoljen</span>
          <span class="timing-message">{{
            status?.timingBlockedMessage || 'Check-in moguć najranije 1 sat pre početka rezervacije'
          }}</span>
          @if (status?.minutesUntilCheckInAllowed) {
          <span class="timing-countdown">
            <mat-icon>hourglass_empty</mat-icon>
            Preostalo: {{ formatMinutes(status?.minutesUntilCheckInAllowed!) }}
          </span>
          }
        </div>
      </div>
      }

      <!-- Phase 4C: No-Show Grace Period Info -->
      @if (status?.noShowGraceMinutes && !readOnly) {
      <div class="grace-period-info" [class.short-trip]="status?.isShortTrip">
        <mat-icon>info_outline</mat-icon>
        <span>
          Gost ima {{ status?.noShowGraceMinutes }} min. tolerancije za kašnjenje
          {{ status?.isShortTrip ? '(kratka rezervacija)' : '' }}
        </span>
      </div>
      }

      <!-- Pickup Location Section (shows where car should be picked up) -->
      @if (pickupLocationData()) {
      <div class="pickup-location-section">
        <div class="section-header small">
          <mat-icon>location_on</mat-icon>
          <div>
            <h3>Lokacija preuzimanja</h3>
            <p>Mesto gde gost preuzima vozilo</p>
          </div>
        </div>
        <app-readonly-pickup-location
          [pickupLocation]="pickupLocationData()!"
          [mode]="'compact'"
          [varianceStatus]="status?.varianceStatus ?? null"
          [varianceMeters]="status?.pickupLocationVarianceMeters ?? null"
        />
        <p class="gps-helper-text">
          <mat-icon>info</mat-icon>
          <span>GPS iz fotografija nam pomaže da održimo revizijski trag lokacije vozila</span>
        </p>
      </div>
      }

      <!-- Header -->
      <div class="section-header">
        <mat-icon>camera_alt</mat-icon>
        <div>
          <h2>Fotografije vozila</h2>
          <p>Slikajte vozilo sa svih strana</p>
        </div>
      </div>

      <!-- Guided Capture Mode (shown when active) -->
      @if (showGuidedCapture() && !readOnly) {
      <app-guided-photo-capture
        [bookingId]="bookingId"
        [mode]="'host-checkin'"
        [restoredState]="_restoredCaptureState()"
        (captureComplete)="onGuidedCaptureComplete($event)"
        (captureCancelled)="onGuidedCaptureCancelled()"
      />
      } @else {
      <!-- Manual Capture Mode (default or after guided capture) -->

      <!-- Start guided capture prompt (only when no photos yet) -->
      @if (!readOnly && photoStats().completed === 0) {
      <mat-card class="capture-prompt-card">
        <mat-card-content>
          <div class="capture-prompt">
            <mat-icon>camera_enhance</mat-icon>
            <div class="prompt-text">
              <h4>Snimite fotografije vozila</h4>
              <p>Vođeno snimanje vas vodi kroz sve potrebne uglove sa prikazom silueta.</p>
            </div>
          </div>
          <div class="capture-actions">
            <button
              mat-raised-button
              color="primary"
              (click)="startGuidedCapture()"
              class="start-capture-btn"
            >
              <mat-icon>photo_camera</mat-icon>
              Započni vođeno snimanje
            </button>
            <button mat-stroked-button (click)="useManualCapture()" class="manual-btn">
              Ručno snimanje
            </button>
          </div>
        </mat-card-content>
      </mat-card>
      }

      <!-- Continue guided capture button (when partially done) -->
      @if (!readOnly && photoStats().completed > 0 && !photoStats().allRequiredComplete) {
      <div class="continue-capture-bar">
        <span>{{ photoStats().completed }}/{{ photoStats().total }} fotografija snimljeno</span>
        <button mat-stroked-button color="primary" (click)="startGuidedCapture()">
          <mat-icon>photo_camera</mat-icon>
          Nastavi snimanje
        </button>
      </div>
      }

      <!-- Required Photo grid (show when in manual mode or has photos) -->
      @if (showManualGrid() || photoStats().completed > 0 || readOnly) {
      <div class="photo-grid">
        @for (vm of photoSlotViewModels(); track vm.slot.type) {
        <div
          class="photo-slot"
          [class.completed]="vm.isCompleted"
          [class.uploading]="vm.isUploading"
          [class.error]="false"
          [class.rejected]="vm.isRejected"
          [class.location-mismatch]="vm.locationMismatch"
          [class.readonly]="readOnly"
          (click)="readOnly ? null : triggerFileInput(vm.slot.type)"
        >
          <!-- Thumbnail or placeholder -->
          @if (vm.previewUrl) {
          <img appLazyImg [lazySrc]="vm.previewUrl" [alt]="vm.slot.label" class="photo-preview" />
          <!-- Minimal success badge (replaces full overlay) -->
          <div class="success-badge">
            <mat-icon>check_circle</mat-icon>
          </div>
          <!-- Remove/Replace button - hidden in readOnly mode -->
          @if (!readOnly) {
          <button
            mat-mini-fab
            color="warn"
            class="remove-photo-btn"
            (click)="removePhoto($event, vm.slot.type)"
            aria-label="Ukloni fotografiju"
          >
            <mat-icon>close</mat-icon>
          </button>
          } } @else {
          <div class="photo-placeholder">
            <mat-icon>{{ vm.slot.icon }}</mat-icon>
            <span>{{ vm.slot.label }}</span>
            @if (vm.slot.required) {
            <span class="required-badge">Obavezno</span>
            }
          </div>
          }

          <!-- Upload progress -->
          @if (vm.isUploading) {
          <div class="upload-progress">
            <mat-progress-bar mode="determinate" [value]="vm.progress"></mat-progress-bar>
            <span>{{ vm.progress }}%</span>
          </div>
          }

          <!-- Rejection state with retry button (EXIF validation failure - ORANGE) -->
          @if (vm.isRejected) {
          <div class="rejection-overlay" [class.location-fraud]="vm.locationMismatch">
            <mat-icon>{{ vm.locationMismatch ? 'location_off' : 'warning' }}</mat-icon>
            <span class="rejection-reason">{{ vm.rejectionReason }}</span>
            @if (vm.distanceMeters) {
            <span class="distance-badge">{{ vm.distanceMeters }}m od automobila</span>
            }
            <span class="rejection-hint">{{ vm.remediationHint }}</span>
            <button
              mat-button
              class="retry-btn rejection-retry"
              (click)="retryUpload($event, vm.slot.type)"
            >
              <mat-icon>camera_alt</mat-icon>
              Pokušaj ponovo
            </button>
          </div>
          }

          <!-- Hidden file input -->
          <input
            type="file"
            accept="image/*"
            capture="environment"
            [id]="'file-' + vm.slot.type"
            (change)="onFileSelected($event, vm.slot.type, vm.slot.type)"
            hidden
          />
        </div>
        }
      </div>

      <!-- Progress summary -->
      <div class="progress-summary">
        <span>{{ photoStats().completed }}/{{ photoStats().total }} fotografija</span>
        @if (photoStats().locationMismatchCount > 0) {
        <span class="location-warning">
          <mat-icon>location_off</mat-icon>
          {{ photoStats().locationMismatchCount }} lokacijska neslaganja
        </span>
        }
        <mat-progress-bar
          mode="determinate"
          [value]="(photoStats().completed / photoStats().total) * 100"
        >
        </mat-progress-bar>
      </div>
      }
      <!-- End photo-grid @if -->
      }
      <!-- End guided capture @else -->

      <!-- Damage Photos Section (hidden in readOnly if no damage photos exist) -->
      @if (!readOnly || damagePhotos().length > 0) {
      <div class="damage-section">
        <div class="section-header small">
          <mat-icon>report_problem</mat-icon>
          <div>
            <h3>Postojeća oštećenja {{ readOnly ? '' : '(opciono)' }}</h3>
            <p>
              {{
                readOnly
                  ? 'Dokumentovana oštećenja'
                  : 'Dokumentujte postojeće ogrebotine, ulubljenja i sl.'
              }}
            </p>
          </div>
        </div>

        <!-- Damage photo grid -->
        @if (damageSlotViewModels().length > 0) {
        <div class="photo-grid damage-grid">
          @for (vm of damageSlotViewModels(); track $index) {
          <div
            class="photo-slot damage-slot"
            [class.completed]="vm.isCompleted"
            [class.uploading]="vm.isUploading"
            [class.error]="false"
            [class.rejected]="vm.isRejected"
            [class.location-mismatch]="vm.locationMismatch"
            [class.readonly]="readOnly"
            (click)="readOnly ? null : triggerFileInput(damagePhotos()[$index].id)"
          >
            @if (vm.previewUrl) {
            <img appLazyImg [lazySrc]="vm.previewUrl" alt="Oštećenje" class="photo-preview" />
            <div class="success-badge">
              <mat-icon>check_circle</mat-icon>
            </div>
            @if (!readOnly) {
            <button
              mat-mini-fab
              color="warn"
              class="remove-photo-btn"
              (click)="removeDamagePhoto($event, damagePhotos()[$index].id)"
              aria-label="Ukloni fotografiju"
            >
              <mat-icon>close</mat-icon>
            </button>
            } } @else {
            <div class="photo-placeholder">
              <mat-icon>add_a_photo</mat-icon>
              <span>Oštećenje</span>
              <button
                mat-icon-button
                class="delete-slot-btn"
                (click)="removeDamagePhoto($event, damagePhotos()[$index].id)"
                aria-label="Ukloni slot"
              >
                <mat-icon>delete</mat-icon>
              </button>
            </div>
            } @if (vm.isUploading) {
            <div class="upload-progress">
              <mat-progress-bar mode="determinate" [value]="vm.progress"></mat-progress-bar>
              <span>{{ vm.progress }}%</span>
            </div>
            } @if (vm.isRejected) {
            <div class="rejection-overlay" [class.location-fraud]="vm.locationMismatch">
              <mat-icon>{{ vm.locationMismatch ? 'location_off' : 'warning' }}</mat-icon>
              <span class="rejection-reason">{{ vm.rejectionReason }}</span>
              @if (vm.distanceMeters) {
              <span class="distance-badge">{{ vm.distanceMeters }}m od automobila</span>
              }
              <span class="rejection-hint">{{ vm.remediationHint }}</span>
              <button
                mat-button
                class="retry-btn rejection-retry"
                (click)="retryUpload($event, damagePhotos()[$index].id)"
              >
                <mat-icon>camera_alt</mat-icon>
                Ponovo
              </button>
            </div>
            }

            <input
              type="file"
              accept="image/*"
              capture="environment"
              [id]="'file-' + damagePhotos()[$index].id"
              (change)="
                onFileSelected($event, damagePhotos()[$index].id, damagePhotos()[$index].photoType)
              "
              hidden
            />
          </div>
          }
        </div>
        }

        <!-- Add damage photo button (hidden in readOnly mode) -->
        @if (!readOnly) { @if (damagePhotos().length < maxDamagePhotos) {
        <button
          mat-stroked-button
          color="accent"
          class="add-damage-btn"
          [class.near-limit]="damagePhotos().length >= 8"
          (click)="addDamagePhoto()"
        >
          <mat-icon>add_a_photo</mat-icon>
          Dodaj fotografiju oštećenja @if (damagePhotos().length >= 8) {
          <span class="limit-counter">({{ damagePhotos().length }}/{{ maxDamagePhotos }})</span>
          }
        </button>
        @if (damagePhotos().length >= 8) {
        <p class="damage-warning-hint">
          <mat-icon>info</mat-icon>
          Preostalo još {{ maxDamagePhotos - damagePhotos().length }} fotografija
        </p>
        } } @else {
        <div class="damage-limit-reached">
          <mat-icon>block</mat-icon>
          <p class="damage-limit-hint">
            Maksimalan broj fotografija oštećenja dostignut ({{ maxDamagePhotos }})
          </p>
        </div>
        } }
      </div>
      }

      <!-- Vehicle details: Edit mode (form) vs Read-only mode (presentation) -->
      @if (readOnly) {
      <!-- Read-only presentation mode -->
      <div class="review-section">
        <div class="section-header small">
          <mat-icon>assignment</mat-icon>
          <div>
            <h3>Podaci o vozilu</h3>
            <p>Uneti podaci pri check-inu</p>
          </div>
        </div>

        <div class="review-data-grid">
          <div class="review-data-item">
            <mat-icon>speed</mat-icon>
            <div class="review-data-content">
              <span class="review-data-label">Kilometraža</span>
              <span class="review-data-value">{{ status?.odometerReading | number }} km</span>
            </div>
          </div>

          <div class="review-data-item">
            <mat-icon>local_gas_station</mat-icon>
            <div class="review-data-content">
              <span class="review-data-label">Nivo goriva</span>
              <div class="fuel-display">
                <div class="fuel-bar">
                  <div class="fuel-fill" [style.width.%]="status?.fuelLevelPercent || 0"></div>
                </div>
                <span class="review-data-value">{{ status?.fuelLevelPercent || 0 }}%</span>
              </div>
            </div>
          </div>

          @if (status?.lockboxAvailable) {
          <div class="review-data-item">
            <mat-icon>lock</mat-icon>
            <div class="review-data-content">
              <span class="review-data-label">Lockbox</span>
              <span class="review-data-value">Dostupan</span>
            </div>
          </div>
          }
        </div>
      </div>
      } @else {
      <!-- Edit mode (form) -->
      <mat-expansion-panel [expanded]="photoStats().allRequiredComplete">
        <mat-expansion-panel-header>
          <mat-panel-title>
            <mat-icon>edit</mat-icon>
            Podaci o vozilu
          </mat-panel-title>
        </mat-expansion-panel-header>

        <form [formGroup]="detailsForm" class="details-form">
          <!-- Odometer -->
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Trenutna kilometraža</mat-label>
            <input
              matInput
              type="number"
              formControlName="odometerReading"
              placeholder="npr. 45230"
            />
            <span matSuffix>km</span>
            <mat-hint>Unesite vrednost sa odometra</mat-hint>
            @if (detailsForm.get('odometerReading')?.hasError('required')) {
            <mat-error>Kilometraža je obavezna</mat-error>
            } @if (detailsForm.get('odometerReading')?.hasError('min')) {
            <mat-error>Kilometraža mora biti pozitivna</mat-error>
            }
          </mat-form-field>

          <!-- Fuel level -->
          <div class="fuel-section">
            <label>Nivo goriva: {{ detailsForm.get('fuelLevelPercent')?.value }}%</label>
            <div class="fuel-slider">
              <mat-icon>local_gas_station</mat-icon>
              <mat-slider min="0" max="100" step="5" discrete class="fuel-slider-input">
                <input matSliderThumb formControlName="fuelLevelPercent" />
              </mat-slider>
              <span class="fuel-value">{{ detailsForm.get('fuelLevelPercent')?.value }}%</span>
            </div>
            <div class="fuel-markers">
              <span>Prazan</span>
              <span>1/4</span>
              <span>1/2</span>
              <span>3/4</span>
              <span>Pun</span>
            </div>
          </div>

          <!-- Lockbox code (optional) -->
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Lockbox kod (opciono)</mat-label>
            <input matInput type="text" formControlName="lockboxCode" placeholder="npr. 1234" />
            <mat-hint>Unesite ako je vozilo dostupno sa lockbox-om</mat-hint>
          </mat-form-field>
        </form>
      </mat-expansion-panel>
      }

      <!-- Submit button (hidden in readOnly mode) -->
      @if (!readOnly) {
      <div class="submit-section">
        <button
          mat-raised-button
          color="primary"
          [disabled]="!canSubmit()"
          (click)="submitHostCheckIn()"
          class="submit-button"
        >
          @if (checkInService.isLoading()) {
          <ng-container>
            <mat-icon class="spin">sync</mat-icon>
            Slanje...
          </ng-container>
          } @else {
          <ng-container>
            <mat-icon>send</mat-icon>
            Pošalji na pregled gostu
          </ng-container>
          }
        </button>

        @if (!canSubmit()) {
        <p class="submit-hint">
          @if (!photoStats().allRequiredComplete) { Slikajte sve obavezne fotografije ({{
            photoStats().completed
          }}/{{ photoStats().total }}) } @else if (detailsForm.invalid) { Popunite podatke o vozilu
          (kilometraža je obavezna) } @else if (checkInService.isLoading()) {
          <mat-icon class="spin">sync</mat-icon>
          Učitavanje u toku... }
        </p>
        }
      </div>
      } @else {
      <!-- Back button in readOnly mode -->
      <div class="submit-section">
        <button
          mat-stroked-button
          color="primary"
          (click)="backFromReview.emit()"
          class="submit-button"
        >
          <mat-icon>arrow_back</mat-icon>
          Nazad na čekanje
        </button>
      </div>
      }
    </div>
  `,
  styles: [
    `
      /* ============================================
         DARK MODE SUPPORT - Theme-aware CSS variables
         ============================================ */
      :host {
        /* Text colors */
        --checkin-text-primary: var(--mat-app-text-color, var(--mdc-theme-on-surface, #212121));
        --checkin-text-secondary: var(--mat-app-on-surface-variant, rgba(0, 0, 0, 0.6));

        /* Surface colors */
        --checkin-surface: var(--mat-app-surface, var(--mdc-theme-surface, #ffffff));
        --checkin-surface-muted: var(
          --mat-app-surface-variant,
          var(--mdc-theme-surface-variant, #fafafa)
        );
        --checkin-border: var(--mat-app-outline-variant, var(--mdc-theme-outline, #e0e0e0));

        /* Brand colors */
        --checkin-primary: var(--mat-app-primary, var(--mdc-theme-primary, #1976d2));

        /* Status colors - light mode */
        --checkin-success: #4caf50;
        --checkin-warn: #ff9800;
        --checkin-error: #f44336;

        /* Legacy variable aliases (backward compatibility) */
        --success-color: var(--checkin-success);
        --warn-color: var(--checkin-warn);
        --rejection-color: var(--checkin-warn);
        --error-bg: rgba(244, 67, 54, 0.08);
      }

      /* Dark mode via system preference */
      @media (prefers-color-scheme: dark) {
        :host {
          --checkin-text-primary: rgba(226, 232, 240, 0.92);
          --checkin-text-secondary: rgba(148, 163, 184, 0.78);
          --checkin-surface: rgba(19, 23, 34, 0.95);
          --checkin-surface-muted: rgba(26, 33, 50, 0.78);
          --checkin-border: rgba(94, 117, 168, 0.35);
          --checkin-primary: #3b82f6;
          --checkin-success: #4ade80;
          --checkin-warn: #fbbf24;
          --checkin-error: #f87171;

          /* Legacy variable aliases - dark mode */
          --success-color: var(--checkin-success);
          --warn-color: var(--checkin-warn);
          --rejection-color: var(--checkin-warn);
          --error-bg: rgba(248, 113, 113, 0.12);
        }
      }

      /* Dark mode via class (Angular theme toggle) */
      :host-context(.dark-theme),
      :host-context(.theme-dark) {
        --checkin-text-primary: rgba(226, 232, 240, 0.92);
        --checkin-text-secondary: rgba(148, 163, 184, 0.78);
        --checkin-surface: rgba(19, 23, 34, 0.95);
        --checkin-surface-muted: rgba(26, 33, 50, 0.78);
        --checkin-border: rgba(94, 117, 168, 0.35);
        --checkin-primary: #3b82f6;
        --checkin-success: #4ade80;
        --checkin-warn: #fbbf24;
        --checkin-error: #f87171;

        /* Legacy variable aliases - dark mode */
        --success-color: var(--checkin-success);
        --warn-color: var(--checkin-warn);
        --rejection-color: var(--checkin-warn);
        --error-bg: rgba(248, 113, 113, 0.12);
      }

      .host-check-in {
        padding: 16px;
      }

      /* Pickup Location Section */
      .pickup-location-section {
        margin-bottom: 24px;
        padding: 16px;
        background: var(--checkin-surface-muted);
        border-radius: 12px;
        border: 1px solid var(--checkin-border);
      }

      .section-header {
        display: flex;
        align-items: center;
        gap: 12px;
        margin-bottom: 20px;
      }

      .section-header.small {
        margin-bottom: 12px;
      }

      .section-header.small h3 {
        margin: 0;
        font-size: 16px;
        color: var(--checkin-text-primary);
      }

      .section-header.small p {
        margin: 2px 0 0;
        font-size: 13px;
        color: var(--checkin-text-secondary);
      }

      .section-header mat-icon {
        font-size: 32px;
        width: 32px;
        height: 32px;
        color: var(--checkin-primary);
      }

      .section-header h2 {
        margin: 0;
        font-size: 18px;
        color: var(--checkin-text-primary);
      }

      .section-header p {
        margin: 4px 0 0;
        font-size: 14px;
        color: var(--checkin-text-secondary);
      }

      /* Photo grid */
      .photo-grid {
        display: grid;
        grid-template-columns: repeat(2, 1fr);
        gap: 12px;
        margin-bottom: 16px;
      }

      .photo-slot {
        aspect-ratio: 4/3;
        border-radius: 12px;
        border: 2px dashed var(--checkin-border);
        overflow: hidden;
        position: relative;
        cursor: pointer;
        transition: all 0.2s ease;
      }

      .photo-slot:active {
        transform: scale(0.98);
      }

      .photo-slot.completed {
        border-color: var(--success-color, #4caf50);
        border-style: solid;
      }

      .photo-slot.uploading {
        border-color: var(--checkin-primary);
      }

      .photo-slot.error {
        border-color: var(--warn-color, #f44336);
      }

      /* Rejection state (EXIF validation failure - ORANGE, distinct from red error) */
      .photo-slot.rejected {
        border-color: var(--rejection-color, #ff9800);
        border-style: dashed;
        border-width: 2px;
      }

      .photo-placeholder {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        height: 100%;
        gap: 4px;
        color: var(--checkin-text-secondary);
        background: var(--checkin-surface-muted);
      }

      .photo-placeholder mat-icon {
        font-size: 36px;
        width: 36px;
        height: 36px;
      }

      .photo-placeholder span {
        font-size: 12px;
        text-align: center;
      }

      .required-badge {
        font-size: 10px;
        padding: 2px 6px;
        background: var(--checkin-primary);
        color: white;
        border-radius: 8px;
      }

      .photo-preview {
        width: 100%;
        height: 100%;
        object-fit: cover;
        /* CRITICAL: Respect EXIF orientation metadata for rotated photos */
        image-orientation: from-image;
      }

      /* Minimal success badge (replaces obstructive overlay) */
      .success-badge {
        position: absolute;
        bottom: 8px;
        left: 8px;
        width: 28px;
        height: 28px;
        border-radius: 50%;
        background: var(--success-color, #4caf50);
        display: flex;
        align-items: center;
        justify-content: center;
        box-shadow: 0 2px 4px rgba(0, 0, 0, 0.2);
      }

      .success-badge mat-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
        color: white;
      }

      .remove-photo-btn {
        position: absolute;
        top: 4px;
        right: 4px;
        z-index: 10;
        width: 28px !important;
        height: 28px !important;
      }

      .remove-photo-btn mat-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
      }

      .upload-progress {
        position: absolute;
        bottom: 0;
        left: 0;
        right: 0;
        background: rgba(0, 0, 0, 0.7);
        padding: 8px;
        color: white;
        text-align: center;
      }

      .error-overlay {
        position: absolute;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        background: rgba(244, 67, 54, 0.9);
        color: white;
        padding: 8px;
        text-align: center;
        gap: 4px;
      }

      .error-overlay mat-icon {
        font-size: 24px;
        width: 24px;
        height: 24px;
      }

      .error-message {
        font-size: 11px;
        line-height: 1.3;
        max-height: 36px;
        overflow: hidden;
      }

      .retry-btn {
        font-size: 12px !important;
        padding: 0 8px !important;
        min-width: auto !important;
        height: 28px !important;
        line-height: 28px !important;
      }

      .retry-btn mat-icon {
        font-size: 16px;
        width: 16px;
        height: 16px;
        margin-right: 4px;
      }

      /* Rejection overlay (EXIF validation failure - ORANGE gradient) */
      .rejection-overlay {
        position: absolute;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        background: linear-gradient(
          135deg,
          rgba(255, 152, 0, 0.95) 0%,
          rgba(255, 167, 38, 0.95) 100%
        );
        color: white;
        padding: 8px;
        text-align: center;
        gap: 4px;
        border-radius: 10px;
      }

      .rejection-overlay mat-icon {
        font-size: 28px;
        width: 28px;
        height: 28px;
        margin-bottom: 4px;
      }

      .rejection-reason {
        font-size: 11px;
        font-weight: 600;
        line-height: 1.3;
        max-height: 32px;
        overflow: hidden;
      }

      .rejection-hint {
        font-size: 10px;
        line-height: 1.2;
        opacity: 0.9;
        max-height: 24px;
        overflow: hidden;
      }

      .rejection-retry {
        background: rgba(255, 255, 255, 0.2);
        border: 1px solid rgba(255, 255, 255, 0.4);
        margin-top: 4px;
      }

      .rejection-retry:hover {
        background: rgba(255, 255, 255, 0.3);
      }

      .retry-counter {
        font-size: 10px;
        opacity: 0.8;
        margin-left: 4px;
      }

      /* ============================================
         CRITICAL FIX #1: Location Mismatch Styling
         ============================================ */

      /* Photo slot with location mismatch (red border indicator) */
      .photo-slot.location-mismatch {
        border: 3px solid var(--checkin-error);
        box-shadow: 0 0 12px rgba(244, 67, 54, 0.3);
      }

      /* Location fraud overlay (distinct from generic rejection) */
      .rejection-overlay.location-fraud {
        background: linear-gradient(
          135deg,
          rgba(244, 67, 54, 0.95) 0%,
          rgba(239, 83, 80, 0.95) 100%
        );
      }

      .rejection-overlay.location-fraud mat-icon {
        font-size: 32px;
        width: 32px;
        height: 32px;
        animation: pulse-location 2s ease-in-out infinite;
      }

      @keyframes pulse-location {
        0%,
        100% {
          transform: scale(1);
          opacity: 1;
        }
        50% {
          transform: scale(1.1);
          opacity: 0.8;
        }
      }

      /* Distance badge (shows meters from car location) */
      .distance-badge {
        display: inline-block;
        padding: 2px 8px;
        background: rgba(255, 255, 255, 0.25);
        border: 1px solid rgba(255, 255, 255, 0.4);
        border-radius: 12px;
        font-size: 10px;
        font-weight: 700;
        margin: 4px 0;
      }

      /* Location warning in progress summary */
      .progress-summary .location-warning {
        display: flex;
        align-items: center;
        gap: 6px;
        color: var(--checkin-error);
        font-size: 13px;
        font-weight: 600;
        padding: 8px;
        background: var(--error-bg);
        border-radius: 6px;
        border: 1px solid var(--checkin-error);
      }

      .progress-summary .location-warning mat-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
      }

      /* Progress summary */
      .progress-summary {
        display: flex;
        flex-direction: column;
        gap: 8px;
        margin-bottom: 20px;
        padding: 12px;
        background: var(--checkin-surface-muted);
        border-radius: 8px;
      }

      .progress-summary span {
        font-size: 14px;
        font-weight: 500;
        color: var(--checkin-text-primary);
      }

      /* Damage section */
      .damage-section {
        margin-bottom: 20px;
        padding: 16px;
        background: var(--checkin-surface-muted);
        border-radius: 12px;
        border: 1px dashed var(--checkin-border);
      }

      .damage-grid {
        margin-bottom: 12px;
      }

      .damage-slot {
        border-color: var(--warn-color, #ff9800);
      }

      .damage-slot.completed {
        border-color: var(--warn-color, #ff9800);
        border-style: solid;
      }

      .delete-slot-btn {
        position: absolute;
        top: 4px;
        right: 4px;
        color: var(--checkin-text-secondary);
      }

      .add-damage-btn {
        width: 100%;
        height: 44px;
      }

      .add-damage-btn mat-icon {
        margin-right: 8px;
      }

      /* Near limit warning (8-9 photos) */
      .add-damage-btn.near-limit {
        border-color: var(--warn-color, #ff9800);
        color: var(--warn-color, #ff9800);
      }

      .add-damage-btn .limit-counter {
        margin-left: 8px;
        font-size: 12px;
        opacity: 0.8;
      }

      .damage-warning-hint {
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 4px;
        font-size: 12px;
        color: var(--warn-color, #ff9800);
        text-align: center;
        margin: 8px 0 0;
      }

      .damage-warning-hint mat-icon {
        font-size: 16px;
        width: 16px;
        height: 16px;
      }

      /* Limit reached state */
      .damage-limit-reached {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 4px;
        padding: 12px;
        background: var(--error-bg, rgba(244, 67, 54, 0.08));
        border-radius: 8px;
        margin-top: 8px;
      }

      .damage-limit-reached mat-icon {
        font-size: 24px;
        width: 24px;
        height: 24px;
        color: var(--warn-color, #f44336);
      }

      .damage-limit-hint {
        font-size: 12px;
        color: var(--checkin-text-secondary);
        text-align: center;
        margin: 0;
      }

      /* Details form */
      mat-expansion-panel {
        margin-bottom: 20px;
      }

      .details-form {
        display: flex;
        flex-direction: column;
        gap: 16px;
        padding: 16px 0;
      }

      .full-width {
        width: 100%;
      }

      .fuel-section {
        display: flex;
        flex-direction: column;
        gap: 8px;
      }

      .fuel-slider {
        display: flex;
        align-items: center;
        gap: 8px;
      }

      .fuel-slider-input {
        flex: 1;
      }

      .fuel-value {
        min-width: 40px;
        text-align: right;
        font-weight: 500;
        color: var(--checkin-text-primary);
      }

      .fuel-markers {
        display: flex;
        justify-content: space-between;
        font-size: 11px;
        color: var(--checkin-text-secondary);
      }

      /* Submit */
      .submit-section {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 8px;
        padding: 16px 0;
      }

      .submit-button {
        width: 100%;
        height: 48px;
        font-size: 16px;
      }

      .submit-button mat-icon {
        margin-right: 8px;
      }

      .submit-hint {
        font-size: 13px;
        color: var(--checkin-text-secondary);
        text-align: center;
        margin: 0;
      }

      .spin {
        animation: spin 1s linear infinite;
      }

      @keyframes spin {
        100% {
          transform: rotate(360deg);
        }
      }

      /* Read-only mode styles */
      .readonly-banner {
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 8px;
        padding: 12px 16px;
        margin-bottom: 16px;
        background: color-mix(in srgb, var(--checkin-primary) 12%, var(--checkin-surface));
        color: var(--checkin-primary);
        border-radius: 8px;
        font-size: 14px;
        font-weight: 500;
      }

      .readonly-banner mat-icon {
        font-size: 20px;
        width: 20px;
        height: 20px;
      }

      /* Phase 4A: Timing Blocked Banner */
      .timing-blocked-banner {
        display: flex;
        align-items: flex-start;
        gap: 12px;
        padding: 16px;
        margin-bottom: 16px;
        background: rgba(239, 68, 68, 0.1);
        border: 1px solid rgba(239, 68, 68, 0.3);
        border-left: 4px solid #ef4444;
        border-radius: 8px;
      }

      .timing-blocked-banner > mat-icon {
        color: #ef4444;
        font-size: 28px;
        width: 28px;
        height: 28px;
      }

      .timing-content {
        display: flex;
        flex-direction: column;
        gap: 4px;
      }

      .timing-title {
        font-weight: 600;
        font-size: 15px;
        color: #dc2626;
      }

      .timing-message {
        font-size: 13px;
        color: var(--checkin-text-secondary);
      }

      .timing-countdown {
        display: flex;
        align-items: center;
        gap: 4px;
        margin-top: 8px;
        font-weight: 500;
        font-size: 14px;
        color: #dc2626;
      }

      .timing-countdown mat-icon {
        font-size: 16px;
        width: 16px;
        height: 16px;
      }

      /* Phase 4C: Grace Period Info */
      .grace-period-info {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 10px 14px;
        margin-bottom: 16px;
        background: rgba(59, 130, 246, 0.1);
        border-radius: 8px;
        font-size: 13px;
        color: #1d4ed8;
      }

      .grace-period-info mat-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
      }

      .grace-period-info.short-trip {
        background: rgba(245, 158, 11, 0.1);
        color: #b45309;
      }

      .readonly-mode .photo-slot {
        cursor: default;
      }

      .readonly-mode .photo-slot:active {
        transform: none;
      }

      .photo-slot.readonly {
        cursor: default;
      }

      .photo-slot.readonly:active {
        transform: none;
      }

      /* Review/Presentation mode styles */
      .review-section {
        margin-bottom: 20px;
        padding: 16px;
        background: var(--checkin-surface);
        border-radius: 12px;
        border: 1px solid var(--checkin-border);
      }

      .review-data-grid {
        display: flex;
        flex-direction: column;
        gap: 16px;
      }

      .review-data-item {
        display: flex;
        align-items: center;
        gap: 16px;
        padding: 12px;
        background: var(--checkin-surface-muted);
        border-radius: 8px;
      }

      .review-data-item mat-icon {
        font-size: 28px;
        width: 28px;
        height: 28px;
        color: var(--checkin-primary);
        flex-shrink: 0;
      }

      .review-data-content {
        flex: 1;
        display: flex;
        flex-direction: column;
        gap: 4px;
      }

      .review-data-label {
        font-size: 12px;
        color: var(--checkin-text-secondary);
        text-transform: uppercase;
        letter-spacing: 0.5px;
      }

      .review-data-value {
        font-size: 20px;
        font-weight: 600;
        color: var(--checkin-text-primary);
      }

      /* Fuel display in review mode */
      .fuel-display {
        display: flex;
        align-items: center;
        gap: 12px;
      }

      .fuel-bar {
        flex: 1;
        height: 12px;
        background: var(--checkin-border);
        border-radius: 6px;
        overflow: hidden;
      }

      .fuel-fill {
        height: 100%;
        background: linear-gradient(
          90deg,
          var(--warn-color, #f44336),
          var(--success-color, #4caf50)
        );
        border-radius: 6px;
        transition: width 0.3s ease;
      }

      /* ============================================
         GUIDED CAPTURE PROMPT STYLES
         ============================================ */
      .capture-prompt-card {
        margin-bottom: 20px;
        border: 2px dashed var(--checkin-border);
        background: var(--checkin-surface-muted);
      }

      .capture-prompt {
        display: flex;
        align-items: flex-start;
        gap: 16px;
        margin-bottom: 16px;
      }

      .capture-prompt mat-icon {
        font-size: 48px;
        width: 48px;
        height: 48px;
        color: var(--checkin-primary);
      }

      .capture-prompt .prompt-text h4 {
        margin: 0 0 8px;
        font-size: 18px;
        font-weight: 600;
        color: var(--checkin-text-primary);
      }

      .capture-prompt .prompt-text p {
        margin: 0;
        font-size: 14px;
        color: var(--checkin-text-secondary);
        line-height: 1.5;
      }

      .capture-actions {
        display: flex;
        gap: 12px;
        flex-wrap: wrap;
      }

      .start-capture-btn {
        min-width: 200px;
      }

      .start-capture-btn mat-icon {
        margin-right: 8px;
      }

      .manual-btn {
        color: var(--checkin-text-secondary);
      }

      /* Continue capture bar */
      .continue-capture-bar {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 16px;
        padding: 12px 16px;
        margin-bottom: 16px;
        background: var(--checkin-surface-muted);
        border: 1px solid var(--checkin-border);
        border-radius: 8px;
      }

      .continue-capture-bar span {
        font-size: 14px;
        font-weight: 500;
        color: var(--checkin-text-primary);
      }

      .continue-capture-bar button mat-icon {
        margin-right: 8px;
      }

      /* Dark mode adjustments for capture prompt */
      @media (prefers-color-scheme: dark) {
        .capture-prompt-card {
          border-color: var(--checkin-border);
          background: var(--checkin-surface-muted);
        }

        .capture-prompt mat-icon {
          color: var(--checkin-primary);
        }
      }

      :host-context(.dark-theme) .capture-prompt-card,
      :host-context(.theme-dark) .capture-prompt-card {
        border-color: var(--checkin-border);
        background: var(--checkin-surface-muted);
      }

      :host-context(.dark-theme) .capture-prompt mat-icon,
      :host-context(.theme-dark) .capture-prompt mat-icon {
        color: var(--checkin-primary);
      }
    `,
  ],
})
export class HostCheckInComponent implements OnInit, OnChanges, OnDestroy {
  @Input() bookingId!: number;
  @Input() status!: CheckInStatusDTO | null;
  @Input() readOnly = false;
  @Output() completed = new EventEmitter<void>();
  @Output() backFromReview = new EventEmitter<void>();

  private fb = inject(FormBuilder);
  private snackBar = inject(MatSnackBar);
  private dialog = inject(MatDialog);
  private destroyRef = inject(DestroyRef);
  checkInService = inject(CheckInService);
  private compressionService = inject(PhotoCompressionService);
  private geolocationService = inject(GeolocationService);
  private photoGuidanceService = inject(PhotoGuidanceService);
  private persistenceService = inject(CheckInPersistenceService);

  // Persistence state
  readonly _restoredCaptureState = signal<CaptureState | undefined>(undefined);
  readonly _hasUnsavedPhotos = signal(false);
  private formChangeSubject = new Subject<void>();

  // Local photo previews (blob URLs or hydrated backend URLs) keyed by slotId
  private localPreviews = signal<Map<string, string>>(new Map());

  // Dynamic damage photo slots
  readonly damagePhotos = signal<DamagePhotoSlot[]>([]);
  readonly maxDamagePhotos = MAX_DAMAGE_PHOTOS;

  // Guided capture state
  private _showGuidedCapture = signal(false);
  private _showManualGrid = signal(false);
  readonly showGuidedCapture = this._showGuidedCapture.asReadonly();
  readonly showManualGrid = this._showManualGrid.asReadonly();

  constructor() {
    // Set up debounced form persistence
    this.formChangeSubject
      .pipe(debounceTime(500), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.persistFormState());
  }

  // ========== SVELTE-INSPIRED VIEW MODELS ==========
  // Single-pass derivation pattern: compute all photo state once per change
  // Eliminates 48+ redundant Map lookups (8 photos × 6 checks per render)

  /**
   * Svelte $derived pattern: Flattened view models for required photos.
   * Computes all derived state (isCompleted, isUploading, previewUrl, etc.) in one pass.
   * Includes Critical Fix #1: Location validation tracking.
   */
  readonly photoSlotViewModels = computed<PhotoSlotViewModel[]>(() => {
    const progress = this.checkInService.uploadProgress();
    const previews = this.localPreviews();
    const isReadOnly = this.readOnly;

    return PHOTO_SLOTS.map((slot) => {
      const slotProgress = progress.get(slot.type);
      const previewUrl = this.normalizePhotoUrl(slot.type, previews, slotProgress);

      // Parse location mismatch from Serbian rejection reason
      const isLocationMismatch = slotProgress?.rejectionReason?.includes('drugog mesta') ?? false;
      const distanceMeters = isLocationMismatch
        ? this.extractDistanceFromRejection(slotProgress?.rejectionReason)
        : null;

      return {
        slot,
        isCompleted: isReadOnly ? previews.has(slot.type) : slotProgress?.state === 'complete',
        isUploading:
          slotProgress?.state === 'uploading' ||
          slotProgress?.state === 'compressing' ||
          slotProgress?.state === 'validating',
        isValidationPending: slotProgress?.state === 'validating',
        isRejected: slotProgress?.state === 'rejected',
        progress: slotProgress?.progress ?? 0,
        previewUrl,
        rejectionReason: slotProgress?.rejectionReason ?? null,
        remediationHint: slotProgress?.remediationHint ?? null,
        locationMismatch: isLocationMismatch,
        distanceMeters,
      };
    });
  });

  /**
   * Svelte $derived pattern: View models for damage photos.
   * Same single-pass derivation as required photos.
   */
  readonly damageSlotViewModels = computed<PhotoSlotViewModel[]>(() => {
    const progress = this.checkInService.uploadProgress();
    const previews = this.localPreviews();
    const isReadOnly = this.readOnly;

    return this.damagePhotos().map((damageSlot) => {
      const slotProgress = progress.get(damageSlot.id);
      const previewUrl = this.normalizePhotoUrl(damageSlot.id, previews, slotProgress);

      const isLocationMismatch = slotProgress?.rejectionReason?.includes('drugog mesta') ?? false;
      const distanceMeters = isLocationMismatch
        ? this.extractDistanceFromRejection(slotProgress?.rejectionReason)
        : null;

      return {
        slot: {
          type: damageSlot.photoType,
          label: 'Oštećenje',
          icon: 'add_a_photo',
          required: false,
        },
        isCompleted: isReadOnly ? previews.has(damageSlot.id) : slotProgress?.state === 'complete',
        isUploading:
          slotProgress?.state === 'uploading' ||
          slotProgress?.state === 'compressing' ||
          slotProgress?.state === 'validating',
        isValidationPending: slotProgress?.state === 'validating',
        isRejected: slotProgress?.state === 'rejected',
        progress: slotProgress?.progress ?? 0,
        previewUrl,
        rejectionReason: slotProgress?.rejectionReason ?? null,
        remediationHint: slotProgress?.remediationHint ?? null,
        locationMismatch: isLocationMismatch,
        distanceMeters,
      };
    });
  });

  /**
   * Svelte $derived pattern: Aggregate stats computed once.
   * Replaces multiple individual computed signals (completedPhotosCount, allRequiredPhotosUploaded).
   */
  readonly photoStats = computed<PhotoStatsViewModel>(() => {
    const viewModels = this.photoSlotViewModels();
    const completed = viewModels.filter((vm) => vm.isCompleted).length;
    const anyUploading = viewModels.some((vm) => vm.isUploading);
    const validationPendingCount = viewModels.filter((vm) => vm.isValidationPending).length;
    const rejectedCount = viewModels.filter((vm) => vm.isRejected).length;
    const locationMismatchCount = viewModels.filter((vm) => vm.locationMismatch).length;

    return {
      completed,
      total: REQUIRED_HOST_PHOTOS.length,
      allRequiredComplete: completed === REQUIRED_HOST_PHOTOS.length,
      anyUploading,
      validationPendingCount,
      rejectedCount,
      locationMismatchCount,
    };
  });

  // Form validity as a signal for reactive computed properties
  private formValidSignal = signal(false);

  /**
   * Computed signal for pickup location data.
   * Maps CheckInStatusDTO fields to PickupLocationData interface.
   */
  pickupLocationData = computed<PickupLocationData | null>(() => {
    const s = this.status;
    if (!s?.pickupLatitude || !s?.pickupLongitude) {
      return null;
    }
    return {
      latitude: s.pickupLatitude,
      longitude: s.pickupLongitude,
      address: s.pickupAddress,
      city: s.pickupCity,
      zipCode: s.pickupZipCode,
      isEstimated: s.estimatedLocation,
    };
  });

  // Form
  detailsForm: FormGroup = this.fb.group({
    odometerReading: [null, [Validators.required, Validators.min(0)]],
    fuelLevelPercent: [50],
    lockboxCode: [''],
  });

  /**
   * Handle input changes - hydrate data when entering read-only mode.
   */
  ngOnChanges(changes: SimpleChanges): void {
    // Handle readOnly mode changes
    if (changes['readOnly']) {
      if (this.readOnly) {
        this.detailsForm.disable();
      } else {
        this.detailsForm.enable();
      }
    }

    // Hydrate photos from status when status changes in read-only mode
    if ((changes['status'] || changes['readOnly']) && this.readOnly && this.status) {
      this.hydratePhotosFromStatus();
    }
  }

  /**
   * Populate localPreviews with full URLs from status.vehiclePhotos.
   * This ensures images load correctly in read-only/review mode.
   */
  private hydratePhotosFromStatus(): void {
    if (!this.status?.vehiclePhotos) return;

    const newPreviews = new Map<string, string>();

    for (const photo of this.status.vehiclePhotos) {
      // Construct full URL from backend storage key
      let fullUrl = photo.url;

      // If URL is relative, prepend the API base URL
      // Backend returns storage keys like "checkin/session-id/filename.jpg"
      // Controller expects: /api/checkin/photos/{sessionId}/{filename}
      // So we strip the "checkin/" prefix from storage key
      if (fullUrl && !fullUrl.startsWith('http')) {
        const baseUrl = environment.baseApiUrl.replace(/\/$/, ''); // Remove trailing slash
        // Strip "checkin/" prefix if present (storage key format)
        const pathSegment = fullUrl.replace(/^checkin\//, '');
        fullUrl = `${baseUrl}/checkin/photos/${pathSegment}`;
      }

      if (fullUrl) {
        // Use photoType as the key (matches slot.type for required photos)
        newPreviews.set(photo.photoType, fullUrl);
      }
    }

    // Set all hydrated previews at once
    this.localPreviews.set(newPreviews);

    console.log('[HostCheckIn] Hydrated photos from status:', {
      photoCount: this.status.vehiclePhotos.length,
      previewsSet: newPreviews.size,
    });
  }

  // Computed
  canSubmit = computed(() => {
    const stats = this.photoStats();
    const formValid = this.formValidSignal();
    const loading = this.checkInService.isLoading();

    // Debug logging
    console.log('[HostCheckIn] canSubmit check:', {
      allPhotosUploaded: stats.allRequiredComplete,
      formValid,
      formErrors: {
        odometerReading: this.detailsForm.get('odometerReading')?.errors,
        odometerValue: this.detailsForm.get('odometerReading')?.value,
        fuelLevelPercent: this.detailsForm.get('fuelLevelPercent')?.value,
      },
      isLoading: loading,
      completedCount: stats.completed,
      locationMismatches: stats.locationMismatchCount,
    });

    return stats.allRequiredComplete && formValid && !loading;
  });

  async ngOnInit(): Promise<void> {
    // Subscribe to form status changes and update the signal
    this.detailsForm.statusChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.formValidSignal.set(this.detailsForm.valid);
    });

    // Also update on value changes to catch all cases
    this.detailsForm.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.formValidSignal.set(this.detailsForm.valid);
      // Trigger debounced form persistence
      this.formChangeSubject.next();
    });

    // Check for saved session on component init (if not in readOnly mode)
    if (!this.readOnly) {
      // CRITICAL: Wait for persistence service to be ready before checking
      await this.persistenceService.waitForReady();

      // Check for saved session and show recovery dialog if found
      await this.checkForSavedSession();

      // Acquire lock for this booking
      await this.persistenceService.acquireLock(this.bookingId);
    }
  }

  ngOnDestroy(): void {
    // Release lock when component is destroyed
    this.persistenceService.releaseLock(this.bookingId);
    this.formChangeSubject.complete();
  }

  /**
   * Warn user before leaving if there are unsaved photos.
   */
  @HostListener('window:beforeunload', ['$event'])
  onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this._hasUnsavedPhotos()) {
      event.preventDefault();
      event.returnValue =
        'Imate nesnimljene fotografije. Da li ste sigurni da želite da napustite stranicu?';
    }
  }

  /**
   * Check for a previously saved session and offer recovery.
   */
  private async checkForSavedSession(): Promise<void> {
    try {
      const savedSession = await this.persistenceService.checkForSavedSession(
        this.bookingId,
        'host-checkin'
      );

      if (!savedSession.exists) return;

      // Show recovery dialog
      const dialogRef = this.dialog.open(CheckInRecoveryDialogComponent, {
        width: '400px',
        disableClose: true,
        data: {
          sessionInfo: savedSession,
          bookingId: this.bookingId,
          mode: 'host-checkin',
        } satisfies RecoveryDialogData,
      });

      const result = (await dialogRef.afterClosed().toPromise()) as
        | RecoveryDialogResult
        | undefined;

      if (result?.action === 'resume') {
        this._restoredCaptureState.set(result.captureState);
        this._hasUnsavedPhotos.set(result.captureState.capturedPhotos.length > 0);
        if (result.captureState.capturedPhotos.length > 0) {
          this._showGuidedCapture.set(true);
        }
        await this.restoreFormState();
        this.snackBar.open('Prethodna sesija uspešno vraćena', 'OK', { duration: 3000 });
      } else if (result?.action === 'takeover') {
        await this.persistenceService.requestTakeover(this.bookingId);
        this._restoredCaptureState.set(result.captureState);
        this._hasUnsavedPhotos.set(result.captureState.capturedPhotos.length > 0);
        if (result.captureState.capturedPhotos.length > 0) {
          this._showGuidedCapture.set(true);
        }
        await this.restoreFormState();
        this.snackBar.open('Prethodna sesija uspešno vraćena', 'OK', { duration: 3000 });
      } else if (result?.action === 'start-fresh') {
        await this.persistenceService.clearBookingData(this.bookingId, 'host-checkin');
      }
      // 'cancel' or undefined - do nothing
    } catch (err) {
      console.error('[HostCheckIn] Error checking for saved session:', err);
    }
  }

  /**
   * Persist form state to IndexedDB with debouncing.
   */
  private async persistFormState(): Promise<void> {
    if (this.readOnly) return;

    try {
      await this.persistenceService.saveFormState(this.bookingId, 'host-checkin', {
        odometerReading: this.detailsForm.get('odometerReading')?.value,
        fuelLevelPercent: this.detailsForm.get('fuelLevelPercent')?.value,
        lockboxCode: this.detailsForm.get('lockboxCode')?.value,
      });
    } catch (err) {
      console.warn('[HostCheckIn] Failed to persist form state:', err);
    }
  }

  /**
   * Restore form state from IndexedDB.
   */
  private async restoreFormState(): Promise<void> {
    try {
      const formState = await this.persistenceService.loadFormState(this.bookingId, 'host-checkin');
      if (formState) {
        this.detailsForm.patchValue({
          odometerReading: formState.odometerReading ?? null,
          fuelLevelPercent: formState.fuelLevelPercent ?? 50,
          lockboxCode: formState.lockboxCode ?? '',
        });
      }
    } catch (err) {
      console.warn('[HostCheckIn] Failed to restore form state:', err);
    }
  }

  // Methods
  triggerFileInput(slotId: string): void {
    const input = document.getElementById(`file-${slotId}`) as HTMLInputElement;
    if (input) {
      input.click();
    }
  }

  /**
   * Remove a photo to allow re-uploading a different one.
   */
  removePhoto(event: Event, slotId: string): void {
    event.stopPropagation(); // Don't trigger file input

    // Clear local preview
    this.localPreviews.update((map) => {
      const newMap = new Map(map);
      const oldUrl = newMap.get(slotId);
      if (oldUrl) {
        URL.revokeObjectURL(oldUrl); // Clean up blob URL
      }
      newMap.delete(slotId);
      return newMap;
    });

    // Clear upload progress state (also cancels any in-flight upload)
    this.checkInService.clearPhotoProgress(slotId);

    this.snackBar.open(`Fotografija uklonjena. Možete dodati novu.`, '', {
      duration: 2000,
    });
  }

  /**
   * Handle file selection - fire-and-forget pattern.
   * Upload starts immediately in background; no await/blocking.
   */
  onFileSelected(event: Event, slotId: string, photoType: CheckInPhotoType): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];

    if (!file) return;

    // Create local preview immediately (optimistic UI)
    const previewUrl = URL.createObjectURL(file);
    this.localPreviews.update((map) => {
      const newMap = new Map(map);
      // Revoke old URL if exists (prevents memory leak on re-upload)
      const oldUrl = newMap.get(slotId);
      if (oldUrl) {
        URL.revokeObjectURL(oldUrl);
      }
      newMap.set(slotId, previewUrl);
      return newMap;
    });

    // Fire-and-forget: upload starts in background, no await
    // CheckInService handles compression, GPS injection, progress tracking, error handling
    this.checkInService.uploadPhoto(this.bookingId, file, slotId, photoType);

    // Reset input to allow re-selecting same file
    input.value = '';
  }

  /**
   * Retry a failed upload by clearing state and re-triggering file input.
   */
  retryUpload(event: Event, slotId: string): void {
    event.stopPropagation();
    this.checkInService.clearPhotoProgress(slotId);
    this.triggerFileInput(slotId);
  }

  // ========== DAMAGE PHOTOS ==========

  /**
   * Add a new damage photo slot.
   */
  addDamagePhoto(): void {
    if (this.damagePhotos().length >= this.maxDamagePhotos) {
      this.snackBar.open(`Maksimalno ${this.maxDamagePhotos} fotografija oštećenja`, '', {
        duration: 3000,
      });
      return;
    }

    const newSlot: DamagePhotoSlot = {
      id: `damage-${generateUUID()}`,
      photoType: 'HOST_DAMAGE_PREEXISTING',
    };

    this.damagePhotos.update((slots) => [...slots, newSlot]);
  }

  /**
   * Remove a damage photo slot.
   */
  removeDamagePhoto(event: Event, slotId: string): void {
    event.stopPropagation();

    // Clear upload state and local preview
    this.removePhoto(event, slotId);

    // Remove from damage photos array
    this.damagePhotos.update((slots) => slots.filter((s) => s.id !== slotId));
  }

  // ========== SVELTE-INSPIRED HELPER METHODS ==========
  // Pure functions for view model derivation (called only during computed signal evaluation)

  /**
   * Normalize photo URL for preview display.
   * Prefers local blob URLs, falls back to backend URLs.
   */
  private normalizePhotoUrl(
    slotId: string,
    previews: Map<string, string>,
    slotProgress: PhotoUploadProgress | undefined
  ): string | null {
    // Always prefer local blob preview
    const localPreview = previews.get(slotId);
    if (localPreview) {
      return localPreview;
    }
    // Fallback to backend URL
    if (slotProgress?.result?.url) {
      const url = slotProgress.result.url;
      if (url.startsWith('/')) {
        return `${environment.baseApiUrl}${url}`;
      }
      return url;
    }
    return null;
  }

  /**
   * Extract distance in meters from Serbian rejection reason.
   * Example: "Fotografija je napravljena na drugom mestu (1234m od automobila)"
   * Returns: 1234
   */
  private extractDistanceFromRejection(rejectionReason?: string): number | null {
    if (!rejectionReason) return null;
    const match = rejectionReason.match(/(\d+)m od automobila/);
    return match ? parseInt(match[1], 10) : null;
  }

  // =========================================================================
  // Phase 4A: Timing Display Helper Methods
  // =========================================================================

  /**
   * Format minutes into a human-readable time string.
   * Used for displaying timing restrictions to users.
   */
  formatMinutes(minutes: number): string {
    if (minutes < 60) {
      return `${minutes} min`;
    }
    const hours = Math.floor(minutes / 60);
    const remainingMinutes = minutes % 60;
    if (remainingMinutes === 0) {
      return `${hours} sat${hours === 1 ? '' : hours < 5 ? 'a' : 'i'}`;
    }
    return `${hours}h ${remainingMinutes}min`;
  }

  // ========== GUIDED CAPTURE METHODS ==========

  /**
   * Start the guided photo capture flow.
   * This provides step-by-step guidance with silhouettes.
   */
  startGuidedCapture(): void {
    this._showGuidedCapture.set(true);
    this._showManualGrid.set(false);
  }

  /**
   * Switch to manual capture mode (traditional grid).
   */
  useManualCapture(): void {
    this._showManualGrid.set(true);
    this._showGuidedCapture.set(false);
  }

  /**
   * Handle completion of guided photo capture.
   * Converts submission data and uploads each photo individually.
   */
  onGuidedCaptureComplete(submission: GuestCheckInPhotoSubmissionDTO): void {
    console.log('[HostCheckIn] Guided capture complete:', submission.photos.length, 'photos');

    // Process each photo from the guided capture
    for (const photo of submission.photos) {
      // Convert base64 to blob
      const byteString = atob(photo.base64Data);
      const arrayBuffer = new ArrayBuffer(byteString.length);
      const uint8Array = new Uint8Array(arrayBuffer);
      for (let i = 0; i < byteString.length; i++) {
        uint8Array[i] = byteString.charCodeAt(i);
      }
      const blob = new Blob([uint8Array], { type: photo.mimeType || 'image/jpeg' });
      const file = new File([blob], `${photo.photoType}.jpg`, {
        type: photo.mimeType || 'image/jpeg',
      });

      // Create preview URL
      const previewUrl = URL.createObjectURL(blob);
      this.localPreviews.update((map) => {
        const newMap = new Map(map);
        const oldUrl = newMap.get(photo.photoType);
        if (oldUrl) URL.revokeObjectURL(oldUrl);
        newMap.set(photo.photoType, previewUrl);
        return newMap;
      });

      // Upload to backend
      this.checkInService.uploadPhoto(
        this.bookingId,
        file,
        photo.photoType,
        photo.photoType as CheckInPhotoType
      );
    }

    // Exit guided capture mode
    this._showGuidedCapture.set(false);
    this._showManualGrid.set(true); // Show grid to see results

    this.snackBar.open(`${submission.photos.length} fotografija uspešno snimljeno!`, 'OK', {
      duration: 3000,
      panelClass: 'success-snackbar',
    });
  }

  /**
   * Handle cancellation of guided capture.
   */
  onGuidedCaptureCancelled(): void {
    this._showGuidedCapture.set(false);
    this.photoGuidanceService.resetCapture();
  }

  submitHostCheckIn(): void {
    if (!this.canSubmit()) return;

    const { odometerReading, fuelLevelPercent, lockboxCode } = this.detailsForm.value;

    this.checkInService
      .submitHostCheckIn(
        this.bookingId,
        odometerReading,
        fuelLevelPercent,
        lockboxCode || undefined
      )
      .subscribe({
        next: () => {
          // Clear persisted session data on successful submission
          this.persistenceService.clearBookingData(this.bookingId, 'host-checkin').catch((err) => {
            console.warn('[HostCheckIn] Failed to clear persistence data:', err);
          });
          this._hasUnsavedPhotos.set(false);

          this.snackBar.open('Check-in uspešno poslat gostu!', 'OK', {
            duration: 3000,
            panelClass: 'success-snackbar',
          });
          this.completed.emit();
        },
        error: (err) => {
          const message = err.error?.message || 'Slanje nije uspelo. Pokušajte ponovo.';
          this.snackBar.open(message, 'OK', { duration: 5000 });
        },
      });
  }
}

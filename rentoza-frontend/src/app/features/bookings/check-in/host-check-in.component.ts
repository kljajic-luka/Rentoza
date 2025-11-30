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
  SimpleChanges,
  DestroyRef,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
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

import { environment } from '@environments/environment';
import { CheckInService } from '../../../core/services/check-in.service';
import { PhotoCompressionService } from '../../../core/services/photo-compression.service';
import { GeolocationService } from '../../../core/services/geolocation.service';
import {
  CheckInStatusDTO,
  CheckInPhotoType,
  REQUIRED_HOST_PHOTOS,
  PhotoUploadProgress,
  DamagePhotoSlot,
  MAX_DAMAGE_PHOTOS,
} from '../../../core/models/check-in.model';

interface PhotoSlot {
  type: CheckInPhotoType;
  label: string;
  icon: string;
  required: boolean;
}

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
      <!-- Header -->
      <div class="section-header">
        <mat-icon>camera_alt</mat-icon>
        <div>
          <h2>Fotografije vozila</h2>
          <p>Slikajte vozilo sa svih strana</p>
        </div>
      </div>

      <!-- Required Photo grid -->
      <div class="photo-grid">
        @for (slot of photoSlots; track slot.type) {
        <div
          class="photo-slot"
          [class.completed]="isPhotoCompleted(slot.type)"
          [class.uploading]="isPhotoUploading(slot.type)"
          [class.error]="isPhotoError(slot.type)"
          [class.readonly]="readOnly"
          (click)="readOnly ? null : triggerFileInput(slot.type)"
        >
          <!-- Thumbnail or placeholder -->
          @if (getPhotoPreview(slot.type)) {
          <img [src]="getPhotoPreview(slot.type)" [alt]="slot.label" class="photo-preview" />
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
            (click)="removePhoto($event, slot.type)"
            aria-label="Ukloni fotografiju"
          >
            <mat-icon>close</mat-icon>
          </button>
          } } @else {
          <div class="photo-placeholder">
            <mat-icon>{{ slot.icon }}</mat-icon>
            <span>{{ slot.label }}</span>
            @if (slot.required) {
            <span class="required-badge">Obavezno</span>
            }
          </div>
          }

          <!-- Upload progress -->
          @if (isPhotoUploading(slot.type)) {
          <div class="upload-progress">
            <mat-progress-bar
              mode="determinate"
              [value]="getUploadProgress(slot.type)"
            ></mat-progress-bar>
            <span>{{ getUploadProgress(slot.type) }}%</span>
          </div>
          }

          <!-- Error state with retry button -->
          @if (isPhotoError(slot.type)) {
          <div class="error-overlay">
            <mat-icon>error</mat-icon>
            <span class="error-message">{{ getPhotoError(slot.type) }}</span>
            <button mat-button class="retry-btn" (click)="retryUpload($event, slot.type)">
              <mat-icon>refresh</mat-icon>
              Pokušaj ponovo
            </button>
          </div>
          }

          <!-- Hidden file input -->
          <input
            type="file"
            accept="image/*"
            capture="environment"
            [id]="'file-' + slot.type"
            (change)="onFileSelected($event, slot.type, slot.type)"
            hidden
          />
        </div>
        }
      </div>

      <!-- Progress summary -->
      <div class="progress-summary">
        <span>{{ completedPhotosCount() }}/{{ requiredPhotosCount }} fotografija</span>
        <mat-progress-bar
          mode="determinate"
          [value]="(completedPhotosCount() / requiredPhotosCount) * 100"
        >
        </mat-progress-bar>
      </div>

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
        @if (damagePhotos().length > 0) {
        <div class="photo-grid damage-grid">
          @for (slot of damagePhotos(); track slot.id) {
          <div
            class="photo-slot damage-slot"
            [class.completed]="isPhotoCompleted(slot.id)"
            [class.uploading]="isPhotoUploading(slot.id)"
            [class.error]="isPhotoError(slot.id)"
            [class.readonly]="readOnly"
            (click)="readOnly ? null : triggerFileInput(slot.id)"
          >
            @if (getPhotoPreview(slot.id)) {
            <img [src]="getPhotoPreview(slot.id)" alt="Oštećenje" class="photo-preview" />
            <div class="success-badge">
              <mat-icon>check_circle</mat-icon>
            </div>
            @if (!readOnly) {
            <button
              mat-mini-fab
              color="warn"
              class="remove-photo-btn"
              (click)="removeDamagePhoto($event, slot.id)"
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
                (click)="removeDamagePhoto($event, slot.id)"
                aria-label="Ukloni slot"
              >
                <mat-icon>delete</mat-icon>
              </button>
            </div>
            } @if (isPhotoUploading(slot.id)) {
            <div class="upload-progress">
              <mat-progress-bar
                mode="determinate"
                [value]="getUploadProgress(slot.id)"
              ></mat-progress-bar>
              <span>{{ getUploadProgress(slot.id) }}%</span>
            </div>
            } @if (isPhotoError(slot.id)) {
            <div class="error-overlay">
              <mat-icon>error</mat-icon>
              <span class="error-message">{{ getPhotoError(slot.id) }}</span>
              <button mat-button class="retry-btn" (click)="retryUpload($event, slot.id)">
                <mat-icon>refresh</mat-icon>
                Ponovo
              </button>
            </div>
            }

            <input
              type="file"
              accept="image/*"
              capture="environment"
              [id]="'file-' + slot.id"
              (change)="onFileSelected($event, slot.id, slot.photoType)"
              hidden
            />
          </div>
          }
        </div>
        }

        <!-- Add damage photo button (hidden in readOnly mode) -->
        @if (!readOnly) { @if (damagePhotos().length < maxDamagePhotos) {
        <button mat-stroked-button color="accent" class="add-damage-btn" (click)="addDamagePhoto()">
          <mat-icon>add_a_photo</mat-icon>
          Dodaj fotografiju oštećenja
        </button>
        } @else {
        <p class="damage-limit-hint">
          Maksimalan broj fotografija oštećenja dostignut ({{ maxDamagePhotos }})
        </p>
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
      <mat-expansion-panel [expanded]="allRequiredPhotosUploaded()">
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
          @if (!allRequiredPhotosUploaded()) { Slikajte sve obavezne fotografije ({{
            completedPhotosCount()
          }}/{{ requiredPhotosCount }}) } @else if (detailsForm.invalid) { Popunite podatke o vozilu
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
      .host-check-in {
        padding: 16px;
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
      }

      .section-header.small p {
        margin: 2px 0 0;
        font-size: 13px;
      }

      .section-header mat-icon {
        font-size: 32px;
        width: 32px;
        height: 32px;
        color: var(--primary-color, #1976d2);
      }

      .section-header h2 {
        margin: 0;
        font-size: 18px;
      }

      .section-header p {
        margin: 4px 0 0;
        font-size: 14px;
        color: var(--color-text-muted, #757575);
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
        border: 2px dashed var(--color-border-subtle, #ccc);
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
        border-color: var(--primary-color, #1976d2);
      }

      .photo-slot.error {
        border-color: var(--warn-color, #f44336);
      }

      .photo-placeholder {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        height: 100%;
        gap: 4px;
        color: var(--color-text-muted, #757575);
        background: var(--color-surface-muted, #fafafa);
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
        background: var(--primary-color, #1976d2);
        color: white;
        border-radius: 8px;
      }

      .photo-preview {
        width: 100%;
        height: 100%;
        object-fit: cover;
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

      /* Progress summary */
      .progress-summary {
        display: flex;
        flex-direction: column;
        gap: 8px;
        margin-bottom: 20px;
        padding: 12px;
        background: var(--color-surface-muted, #f5f5f5);
        border-radius: 8px;
      }

      .progress-summary span {
        font-size: 14px;
        font-weight: 500;
        color: var(--color-text-primary, #212121);
      }

      /* Damage section */
      .damage-section {
        margin-bottom: 20px;
        padding: 16px;
        background: var(--color-surface-muted, #fafafa);
        border-radius: 12px;
        border: 1px dashed var(--color-border-subtle, #ddd);
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
        color: var(--color-text-muted, #757575);
      }

      .add-damage-btn {
        width: 100%;
        height: 44px;
      }

      .add-damage-btn mat-icon {
        margin-right: 8px;
      }

      .damage-limit-hint {
        font-size: 12px;
        color: var(--color-text-muted, #757575);
        text-align: center;
        margin: 8px 0 0;
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
        color: var(--color-text-primary, #212121);
      }

      .fuel-markers {
        display: flex;
        justify-content: space-between;
        font-size: 11px;
        color: var(--color-text-muted, #757575);
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
        color: var(--color-text-muted, #757575);
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
        background: var(--info-bg, rgba(25, 118, 210, 0.12));
        color: var(--info-color, #1565c0);
        border-radius: 8px;
        font-size: 14px;
        font-weight: 500;
      }

      .readonly-banner mat-icon {
        font-size: 20px;
        width: 20px;
        height: 20px;
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
        background: var(--color-surface, white);
        border-radius: 12px;
        border: 1px solid var(--color-border-subtle, #e0e0e0);
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
        background: var(--color-surface-muted, #fafafa);
        border-radius: 8px;
      }

      .review-data-item mat-icon {
        font-size: 28px;
        width: 28px;
        height: 28px;
        color: var(--primary-color, #1976d2);
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
        color: var(--color-text-muted, #757575);
        text-transform: uppercase;
        letter-spacing: 0.5px;
      }

      .review-data-value {
        font-size: 20px;
        font-weight: 600;
        color: var(--color-text-primary, #212121);
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
        background: var(--color-border-subtle, #e0e0e0);
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
    `,
  ],
})
export class HostCheckInComponent implements OnInit, OnChanges {
  @Input() bookingId!: number;
  @Input() status!: CheckInStatusDTO | null;
  @Input() readOnly = false;
  @Output() completed = new EventEmitter<void>();
  @Output() backFromReview = new EventEmitter<void>();

  private fb = inject(FormBuilder);
  private snackBar = inject(MatSnackBar);
  private destroyRef = inject(DestroyRef);
  checkInService = inject(CheckInService);
  private compressionService = inject(PhotoCompressionService);
  private geolocationService = inject(GeolocationService);

  photoSlots = PHOTO_SLOTS;
  requiredPhotosCount = REQUIRED_HOST_PHOTOS.length;

  // Local photo previews (blob URLs or hydrated backend URLs) keyed by slotId
  private localPreviews = signal<Map<string, string>>(new Map());

  // Dynamic damage photo slots
  readonly damagePhotos = signal<DamagePhotoSlot[]>([]);
  readonly maxDamagePhotos = MAX_DAMAGE_PHOTOS;

  // Form validity as a signal for reactive computed properties
  private formValidSignal = signal(false);

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
  completedPhotosCount = computed(() => {
    // In read-only mode, count from hydrated previews
    if (this.readOnly) {
      return this.localPreviews().size;
    }
    // In edit mode, count from upload progress
    const progress = this.checkInService.uploadProgress();
    let count = 0;
    progress.forEach((p) => {
      if (p.state === 'complete') count++;
    });
    return count;
  });

  allRequiredPhotosUploaded = computed(() => {
    // In read-only mode, check hydrated previews
    if (this.readOnly) {
      const previews = this.localPreviews();
      return REQUIRED_HOST_PHOTOS.every((type) => previews.has(type));
    }
    // In edit mode, check upload progress
    const progress = this.checkInService.uploadProgress();
    return REQUIRED_HOST_PHOTOS.every((type) => {
      const p = progress.get(type);
      return p?.state === 'complete';
    });
  });

  canSubmit = computed(() => {
    const allPhotos = this.allRequiredPhotosUploaded();
    const formValid = this.formValidSignal();
    const loading = this.checkInService.isLoading();

    // Debug logging
    console.log('[HostCheckIn] canSubmit check:', {
      allPhotosUploaded: allPhotos,
      formValid,
      formErrors: {
        odometerReading: this.detailsForm.get('odometerReading')?.errors,
        odometerValue: this.detailsForm.get('odometerReading')?.value,
        fuelLevelPercent: this.detailsForm.get('fuelLevelPercent')?.value,
      },
      isLoading: loading,
      completedCount: this.completedPhotosCount(),
    });

    return allPhotos && formValid && !loading;
  });

  ngOnInit(): void {
    // Subscribe to form status changes and update the signal
    this.detailsForm.statusChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.formValidSignal.set(this.detailsForm.valid);
    });

    // Also update on value changes to catch all cases
    this.detailsForm.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.formValidSignal.set(this.detailsForm.valid);
    });
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
      id: `damage-${crypto.randomUUID()}`,
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

  isPhotoCompleted(slotId: string): boolean {
    // In read-only mode, check if we have a hydrated preview
    if (this.readOnly) {
      return this.localPreviews().has(slotId);
    }
    // In edit mode, check upload progress
    const progress = this.checkInService.uploadProgress().get(slotId);
    return progress?.state === 'complete';
  }

  isPhotoUploading(slotId: string): boolean {
    const progress = this.checkInService.uploadProgress().get(slotId);
    return (
      progress?.state === 'uploading' ||
      progress?.state === 'compressing' ||
      progress?.state === 'validating'
    );
  }

  isPhotoError(slotId: string): boolean {
    const progress = this.checkInService.uploadProgress().get(slotId);
    return progress?.state === 'error';
  }

  getUploadProgress(slotId: string): number {
    return this.checkInService.uploadProgress().get(slotId)?.progress ?? 0;
  }

  getPhotoError(slotId: string): string {
    return this.checkInService.uploadProgress().get(slotId)?.error ?? '';
  }

  getPhotoPreview(slotId: string): string | null {
    // Always prefer local blob preview (backend URL may not be accessible from dev server)
    const localPreview = this.localPreviews().get(slotId);
    if (localPreview) {
      return localPreview;
    }
    // Fallback to backend URL only if no local preview (e.g., page reload)
    const progress = this.checkInService.uploadProgress().get(slotId);
    if (progress?.result?.url) {
      // Prepend API base URL if relative path
      const url = progress.result.url;
      if (url.startsWith('/')) {
        return `${environment.baseApiUrl}${url}`;
      }
      return url;
    }
    return null;
  }

  private getSlotLabel(slotId: string): string {
    return PHOTO_SLOTS.find((s) => s.type === slotId)?.label ?? slotId;
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

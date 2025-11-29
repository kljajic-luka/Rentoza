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
    <div class="host-check-in">
      <!-- Header -->
      <div class="section-header">
        <mat-icon>camera_alt</mat-icon>
        <div>
          <h2>Fotografije vozila</h2>
          <p>Slikajte vozilo sa svih strana</p>
        </div>
      </div>

      <!-- Photo grid -->
      <div class="photo-grid">
        @for (slot of photoSlots; track slot.type) {
        <div
          class="photo-slot"
          [class.completed]="isPhotoCompleted(slot.type)"
          [class.uploading]="isPhotoUploading(slot.type)"
          [class.error]="isPhotoError(slot.type)"
          (click)="triggerFileInput(slot.type)"
        >
          <!-- Thumbnail or placeholder -->
          @if (getPhotoPreview(slot.type)) {
          <img [src]="getPhotoPreview(slot.type)" [alt]="slot.label" class="photo-preview" />
          <div class="photo-overlay">
            <mat-icon>check_circle</mat-icon>
          </div>
          <!-- Remove/Replace button -->
          <button
            mat-mini-fab
            color="warn"
            class="remove-photo-btn"
            (click)="removePhoto($event, slot.type)"
            aria-label="Ukloni fotografiju"
          >
            <mat-icon>close</mat-icon>
          </button>
          } @else {
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

          <!-- Error state -->
          @if (isPhotoError(slot.type)) {
          <div class="error-overlay">
            <mat-icon>error</mat-icon>
            <span>{{ getPhotoError(slot.type) }}</span>
            <button mat-button (click)="$event.stopPropagation()">Pokušaj ponovo</button>
          </div>
          }

          <!-- Hidden file input -->
          <input
            type="file"
            accept="image/*"
            capture="environment"
            [id]="'file-' + slot.type"
            (change)="onFileSelected($event, slot.type)"
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

      <!-- Vehicle details form -->
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

      <!-- Submit button -->
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

      .photo-overlay {
        position: absolute;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        display: flex;
        align-items: center;
        justify-content: center;
        background: rgba(76, 175, 80, 0.8);
        color: white;
      }

      .photo-overlay mat-icon {
        font-size: 48px;
        width: 48px;
        height: 48px;
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
    `,
  ],
})
export class HostCheckInComponent implements OnInit {
  @Input() bookingId!: number;
  @Input() status!: CheckInStatusDTO | null;
  @Output() completed = new EventEmitter<void>();

  private fb = inject(FormBuilder);
  private snackBar = inject(MatSnackBar);
  private destroyRef = inject(DestroyRef);
  checkInService = inject(CheckInService);
  private compressionService = inject(PhotoCompressionService);
  private geolocationService = inject(GeolocationService);

  photoSlots = PHOTO_SLOTS;
  requiredPhotosCount = REQUIRED_HOST_PHOTOS.length;

  // Local photo previews (blob URLs)
  private localPreviews = signal<Map<CheckInPhotoType, string>>(new Map());

  // Form validity as a signal for reactive computed properties
  private formValidSignal = signal(false);

  // Form
  detailsForm: FormGroup = this.fb.group({
    odometerReading: [null, [Validators.required, Validators.min(0)]],
    fuelLevelPercent: [50],
    lockboxCode: [''],
  });

  // Computed
  completedPhotosCount = computed(() => {
    const progress = this.checkInService.uploadProgress();
    let count = 0;
    progress.forEach((p) => {
      if (p.state === 'complete') count++;
    });
    return count;
  });

  allRequiredPhotosUploaded = computed(() => {
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
  triggerFileInput(photoType: CheckInPhotoType): void {
    const input = document.getElementById(`file-${photoType}`) as HTMLInputElement;
    if (input) {
      input.click();
    }
  }

  /**
   * Remove a photo to allow re-uploading a different one.
   */
  removePhoto(event: Event, photoType: CheckInPhotoType): void {
    event.stopPropagation(); // Don't trigger file input

    // Clear local preview
    this.localPreviews.update((map) => {
      const newMap = new Map(map);
      const oldUrl = newMap.get(photoType);
      if (oldUrl) {
        URL.revokeObjectURL(oldUrl); // Clean up blob URL
      }
      newMap.delete(photoType);
      return newMap;
    });

    // Clear upload progress state
    this.checkInService.clearPhotoProgress(photoType);

    this.snackBar.open(`${this.getSlotLabel(photoType)} uklonjena. Možete dodati novu.`, '', {
      duration: 2000,
    });
  }

  async onFileSelected(event: Event, photoType: CheckInPhotoType): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];

    if (!file) return;

    try {
      // Create local preview immediately
      const previewUrl = URL.createObjectURL(file);
      this.localPreviews.update((map) => {
        const newMap = new Map(map);
        newMap.set(photoType, previewUrl);
        return newMap;
      });

      // Upload with compression
      await this.checkInService.uploadPhoto(this.bookingId, file, photoType);

      this.snackBar.open(`${this.getSlotLabel(photoType)} postavljena`, '', {
        duration: 2000,
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Upload nije uspeo';
      this.snackBar.open(message, 'OK', { duration: 5000 });
    } finally {
      // Reset input to allow re-selecting same file
      input.value = '';
    }
  }

  isPhotoCompleted(photoType: CheckInPhotoType): boolean {
    const progress = this.checkInService.uploadProgress().get(photoType);
    return progress?.state === 'complete';
  }

  isPhotoUploading(photoType: CheckInPhotoType): boolean {
    const progress = this.checkInService.uploadProgress().get(photoType);
    return (
      progress?.state === 'uploading' ||
      progress?.state === 'compressing' ||
      progress?.state === 'validating'
    );
  }

  isPhotoError(photoType: CheckInPhotoType): boolean {
    const progress = this.checkInService.uploadProgress().get(photoType);
    return progress?.state === 'error';
  }

  getUploadProgress(photoType: CheckInPhotoType): number {
    return this.checkInService.uploadProgress().get(photoType)?.progress ?? 0;
  }

  getPhotoError(photoType: CheckInPhotoType): string {
    return this.checkInService.uploadProgress().get(photoType)?.error ?? '';
  }

  getPhotoPreview(photoType: CheckInPhotoType): string | null {
    // Always prefer local blob preview (backend URL may not be accessible from dev server)
    const localPreview = this.localPreviews().get(photoType);
    if (localPreview) {
      return localPreview;
    }
    // Fallback to backend URL only if no local preview (e.g., page reload)
    const progress = this.checkInService.uploadProgress().get(photoType);
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

  private getSlotLabel(photoType: CheckInPhotoType): string {
    return PHOTO_SLOTS.find((s) => s.type === photoType)?.label ?? photoType;
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

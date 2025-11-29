/**
 * Guest Check-In Component
 *
 * Handles the guest's portion of check-in:
 * 1. Review host's photos of vehicle condition
 * 2. Acknowledge condition is acceptable OR mark damage hotspots
 * 3. Optionally reveal lockbox code if remote handoff
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
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatChipsModule } from '@angular/material/chips';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { CheckInService } from '../../../core/services/check-in.service';
import { GeolocationService } from '../../../core/services/geolocation.service';
import {
  CheckInStatusDTO,
  CheckInPhotoDTO,
  HotspotLocation,
  HotspotMarkingDTO,
} from '../../../core/models/check-in.model';
import { VehicleWireframeComponent } from './vehicle-wireframe.component';

@Component({
  selector: 'app-guest-check-in',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatDialogModule,
    MatInputModule,
    MatFormFieldModule,
    MatCheckboxModule,
    MatChipsModule,
    MatSnackBarModule,
    MatProgressSpinnerModule,
    VehicleWireframeComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="guest-check-in">
      <!-- Vehicle info header -->
      @if (status?.car) {
      <mat-card class="vehicle-card">
        @if (status?.car?.imageUrl) {
        <img [src]="status!.car.imageUrl" [alt]="vehicleTitle()" class="vehicle-image" />
        }
        <mat-card-content>
          <h3>{{ vehicleTitle() }}</h3>
          <div class="vehicle-details">
            <span><mat-icon>speed</mat-icon> {{ status?.odometerReading | number }} km</span>
            <span><mat-icon>local_gas_station</mat-icon> {{ status?.fuelLevelPercent }}%</span>
          </div>
        </mat-card-content>
      </mat-card>
      }

      <!-- Host photos section -->
      <div class="section-header">
        <mat-icon>photo_library</mat-icon>
        <div>
          <h2>Fotografije vozila</h2>
          <p>Pregledajte stanje vozila pre preuzimanja</p>
        </div>
      </div>

      <!-- Photo gallery -->
      <div class="photo-gallery">
        @for (photo of status?.vehiclePhotos; track photo.photoId) {
        <div class="photo-item" (click)="openPhotoViewer(photo)">
          <img [src]="photo.url" [alt]="getPhotoLabel(photo.photoType)" />
          <div class="photo-label">{{ getPhotoLabel(photo.photoType) }}</div>

          <!-- EXIF validation badge -->
          @if (photo.exifValidationStatus !== 'VALID') {
          <div
            class="validation-badge"
            [class.warning]="photo.exifValidationStatus === 'VALID_WITH_WARNINGS'"
            [class.invalid]="photo.exifValidationStatus.startsWith('REJECTED')"
          >
            <mat-icon>
              {{ photo.exifValidationStatus === 'VALID_WITH_WARNINGS' ? 'warning' : 'error' }}
            </mat-icon>
          </div>
          }
        </div>
        }
      </div>

      <!-- Condition acknowledgment -->
      <mat-card class="condition-card">
        <mat-card-content>
          <h3>Potvrdite stanje vozila</h3>

          <form [formGroup]="conditionForm">
            <!-- Accept condition checkbox -->
            <mat-checkbox
              formControlName="conditionAccepted"
              color="primary"
              class="accept-checkbox"
            >
              Pregledao/la sam fotografije i prihvatam trenutno stanje vozila
            </mat-checkbox>

            <!-- Mark damage option -->
            @if (!conditionForm.get('conditionAccepted')?.value) {
            <div class="damage-section">
              <p class="damage-prompt">Uočili ste oštećenje? Označite ga na vozilu:</p>

              <app-vehicle-wireframe
                [hotspots]="markedHotspots()"
                (hotspotClicked)="onHotspotClicked($event)"
                class="wireframe"
              >
              </app-vehicle-wireframe>

              <!-- Comment for damage -->
              @if (markedHotspots().length > 0) {
              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Opis oštećenja</mat-label>
                <textarea
                  matInput
                  formControlName="conditionComment"
                  rows="3"
                  placeholder="Opišite uočeno oštećenje..."
                ></textarea>
              </mat-form-field>
              }
            </div>
            }
          </form>
        </mat-card-content>
      </mat-card>

      <!-- Lockbox section (if available) -->
      @if (status?.lockboxAvailable && !lockboxCode()) {
      <mat-card class="lockbox-card">
        <mat-card-content>
          <div class="lockbox-header">
            <mat-icon>lock</mat-icon>
            <div>
              <h4>Lockbox pristup</h4>
              <p>Vozilo je dostupno sa lockbox-om</p>
            </div>
          </div>
          <button
            mat-stroked-button
            color="primary"
            [disabled]="!status?.geofenceValid || checkInService.isLoading()"
            (click)="revealLockboxCode()"
          >
            @if (!status?.geofenceValid) {
            <ng-container>
              <mat-icon>location_off</mat-icon>
              Pristupite bliže vozilu
            </ng-container>
            } @else {
            <ng-container>
              <mat-icon>visibility</mat-icon>
              Prikaži kod
            </ng-container>
            }
          </button>
          @if (!status?.geofenceValid && status?.geofenceDistanceMeters) {
          <p class="distance-info">
            Udaljenost: {{ status?.geofenceDistanceMeters | number : '1.0-0' }}m
          </p>
          }
        </mat-card-content>
      </mat-card>
      }

      <!-- Revealed lockbox code -->
      @if (lockboxCode()) {
      <mat-card class="lockbox-revealed">
        <mat-card-content>
          <mat-icon>lock_open</mat-icon>
          <div class="code">{{ lockboxCode() }}</div>
          <p>Unesite ovaj kod u lockbox</p>
        </mat-card-content>
      </mat-card>
      }

      <!-- Submit button -->
      <div class="submit-section">
        <button
          mat-raised-button
          color="primary"
          [disabled]="!canSubmit()"
          (click)="submitAcknowledgment()"
          class="submit-button"
        >
          @if (checkInService.isLoading()) {
          <mat-spinner diameter="24"></mat-spinner>
          } @else if (markedHotspots().length > 0) {
          <ng-container>
            <mat-icon>report</mat-icon>
            Prijavi oštećenje
          </ng-container>
          } @else {
          <ng-container>
            <mat-icon>check</mat-icon>
            Potvrdi stanje vozila
          </ng-container>
          }
        </button>

        @if (!canSubmit() && !geolocationService.hasPosition()) {
        <p class="submit-hint">
          <mat-icon>location_off</mat-icon>
          Potreban je pristup lokaciji za potvrdu
        </p>
        }
      </div>
    </div>
  `,
  styles: [
    `
      .guest-check-in {
        padding: 16px;
      }

      /* Vehicle card */
      .vehicle-card {
        margin-bottom: 20px;
        background: var(--color-surface, #ffffff);
      }

      .vehicle-image {
        width: 100%;
        height: 160px;
        object-fit: cover;
      }

      .vehicle-card h3 {
        margin: 12px 0 8px;
        color: var(--color-text-primary, #212121);
      }

      .vehicle-details {
        display: flex;
        gap: 16px;
        color: var(--color-text-muted, #757575);
      }

      .vehicle-details span {
        display: flex;
        align-items: center;
        gap: 4px;
      }

      .vehicle-details mat-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
      }

      /* Section header */
      .section-header {
        display: flex;
        align-items: center;
        gap: 12px;
        margin-bottom: 16px;
      }

      .section-header mat-icon {
        font-size: 28px;
        width: 28px;
        height: 28px;
        color: var(--primary-color, #1976d2);
      }

      .section-header h2 {
        margin: 0;
        font-size: 18px;
        color: var(--color-text-primary, #212121);
      }

      .section-header p {
        margin: 4px 0 0;
        font-size: 14px;
        color: var(--color-text-muted, #757575);
      }

      /* Photo gallery */
      .photo-gallery {
        display: grid;
        grid-template-columns: repeat(2, 1fr);
        gap: 8px;
        margin-bottom: 20px;
      }

      .photo-item {
        position: relative;
        aspect-ratio: 4/3;
        border-radius: 8px;
        overflow: hidden;
        cursor: pointer;
        border: 1px solid var(--color-border-subtle, #e0e0e0);
      }

      .photo-item img {
        width: 100%;
        height: 100%;
        object-fit: cover;
      }

      .photo-label {
        position: absolute;
        bottom: 0;
        left: 0;
        right: 0;
        padding: 4px 8px;
        background: rgba(0, 0, 0, 0.6);
        color: white;
        font-size: 11px;
        text-align: center;
      }

      .validation-badge {
        position: absolute;
        top: 4px;
        right: 4px;
        padding: 4px;
        border-radius: 50%;
        background: rgba(0, 0, 0, 0.5);
      }

      .validation-badge.warning {
        background: var(--warn-bg, #fff3e0);
        color: var(--warn-color, #ff9800);
      }

      .validation-badge.invalid {
        background: var(--error-bg, #ffebee);
        color: var(--error-color, #f44336);
      }

      .validation-badge mat-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
      }

      /* Condition card */
      .condition-card {
        margin-bottom: 20px;
        background: var(--color-surface, #ffffff);
      }

      .condition-card h3 {
        margin: 0 0 16px;
        color: var(--color-text-primary, #212121);
      }

      .accept-checkbox {
        display: flex;
        margin-bottom: 16px;
      }

      .damage-section {
        padding: 16px;
        background: var(--color-surface-muted, #f5f5f5);
        border-radius: 8px;
      }

      .damage-prompt {
        margin: 0 0 12px;
        font-weight: 500;
        color: var(--color-text-primary, #212121);
      }

      .wireframe {
        margin-bottom: 16px;
      }

      .full-width {
        width: 100%;
      }

      /* Lockbox */
      .lockbox-card {
        margin-bottom: 20px;
        background: var(--color-surface, #ffffff);
      }

      .lockbox-header {
        display: flex;
        align-items: center;
        gap: 12px;
        margin-bottom: 12px;
      }

      .lockbox-header mat-icon {
        font-size: 32px;
        width: 32px;
        height: 32px;
        color: var(--primary-color, #1976d2);
      }

      .lockbox-header h4 {
        margin: 0;
        color: var(--color-text-primary, #212121);
      }

      .lockbox-header p {
        margin: 4px 0 0;
        font-size: 13px;
        color: var(--color-text-muted, #757575);
      }

      .distance-info {
        margin: 8px 0 0;
        font-size: 12px;
        color: var(--color-text-muted, #757575);
      }

      .lockbox-revealed {
        margin-bottom: 20px;
        text-align: center;
        background: var(--success-bg, #e8f5e9);
      }

      .lockbox-revealed mat-icon {
        font-size: 48px;
        width: 48px;
        height: 48px;
        color: var(--success-color, #4caf50);
        margin-bottom: 8px;
      }

      .lockbox-revealed .code {
        font-size: 36px;
        font-weight: bold;
        font-family: monospace;
        letter-spacing: 4px;
        margin: 8px 0;
        color: var(--color-text-primary, #212121);
      }

      .lockbox-revealed p {
        margin: 0;
        color: var(--color-text-muted, #757575);
      }

      /* Submit */
      .submit-section {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 12px;
        padding: 16px 0;
      }

      .submit-button {
        width: 100%;
        height: 48px;
        font-size: 16px;
      }

      .submit-hint {
        display: flex;
        align-items: center;
        gap: 4px;
        font-size: 13px;
        color: var(--warn-color, #ff9800);
        margin: 0;
      }
    `,
  ],
})
export class GuestCheckInComponent {
  @Input() bookingId!: number;
  @Input() status!: CheckInStatusDTO | null;
  @Output() completed = new EventEmitter<void>();

  private fb = inject(FormBuilder);
  private snackBar = inject(MatSnackBar);
  private dialog = inject(MatDialog);
  checkInService = inject(CheckInService);
  geolocationService = inject(GeolocationService);

  // State
  private _markedHotspots = signal<HotspotMarkingDTO[]>([]);
  private _lockboxCode = signal<string | null>(null);

  markedHotspots = this._markedHotspots.asReadonly();
  lockboxCode = this._lockboxCode.asReadonly();

  // Form
  conditionForm: FormGroup = this.fb.group({
    conditionAccepted: [false],
    conditionComment: [''],
  });

  // Computed
  vehicleTitle = computed(() => {
    const car = this.status?.car;
    if (!car) return 'Vozilo';
    return `${car.brand} ${car.model} (${car.year})`;
  });

  canSubmit = computed(() => {
    const hasPosition = this.geolocationService.hasPosition();
    const isLoading = this.checkInService.isLoading();
    const accepted = this.conditionForm.get('conditionAccepted')?.value;
    const hasHotspots = this._markedHotspots().length > 0;

    return hasPosition && !isLoading && (accepted || hasHotspots);
  });

  // Photo labels
  private photoLabels: Record<string, string> = {
    HOST_EXTERIOR_FRONT: 'Prednja strana',
    HOST_EXTERIOR_REAR: 'Zadnja strana',
    HOST_EXTERIOR_LEFT: 'Leva strana',
    HOST_EXTERIOR_RIGHT: 'Desna strana',
    HOST_INTERIOR_DASHBOARD: 'Instrument tabla',
    HOST_INTERIOR_REAR: 'Zadnja sedišta',
    HOST_ODOMETER: 'Kilometraža',
    HOST_FUEL_GAUGE: 'Nivo goriva',
    HOST_DAMAGE_PREEXISTING: 'Postojeće oštećenje',
  };

  getPhotoLabel(photoType: string): string {
    return this.photoLabels[photoType] ?? photoType;
  }

  openPhotoViewer(photo: CheckInPhotoDTO): void {
    // TODO: Open full-screen photo viewer dialog
    console.log('Open photo viewer', photo);
  }

  onHotspotClicked(location: HotspotLocation): void {
    const existing = this._markedHotspots().find((h) => h.location === location);

    if (existing) {
      // Remove if already marked
      this._markedHotspots.update((hotspots) => hotspots.filter((h) => h.location !== location));
    } else {
      // Add new hotspot
      this._markedHotspots.update((hotspots) => [...hotspots, { location, description: '' }]);
    }

    // Uncheck condition accepted if hotspots marked
    if (this._markedHotspots().length > 0) {
      this.conditionForm.patchValue({ conditionAccepted: false });
    }
  }

  revealLockboxCode(): void {
    this.checkInService.revealLockboxCode(this.bookingId).subscribe({
      next: (result) => {
        this._lockboxCode.set(result.lockboxCode);
        this.snackBar.open('Lockbox kod je prikazan', '', { duration: 2000 });
      },
      error: (err) => {
        const message = err.error?.message || 'Nije moguće prikazati kod. Proverite lokaciju.';
        this.snackBar.open(message, 'OK', { duration: 5000 });
      },
    });
  }

  submitAcknowledgment(): void {
    const accepted = this.conditionForm.get('conditionAccepted')?.value || false;
    const comment = this.conditionForm.get('conditionComment')?.value || undefined;
    const hotspots = this._markedHotspots();

    this.checkInService
      .acknowledgeCondition(
        this.bookingId,
        accepted,
        comment,
        hotspots.map((h) => ({
          location: h.location,
          description: h.description || comment || '',
        }))
      )
      .subscribe({
        next: () => {
          const message = accepted ? 'Stanje vozila potvrđeno!' : 'Prijava oštećenja poslata!';
          this.snackBar.open(message, 'OK', {
            duration: 3000,
            panelClass: 'success-snackbar',
          });
          this.completed.emit();
        },
        error: (err) => {
          const message = err.error?.message || 'Potvrda nije uspela. Pokušajte ponovo.';
          this.snackBar.open(message, 'OK', { duration: 5000 });
        },
      });
  }
}

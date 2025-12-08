/**
 * Guest Checkout Component
 *
 * Handles guest checkout workflow:
 * - Review check-in photos
 * - Upload return photos
 * - Enter end odometer/fuel readings
 * - Submit checkout
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
import {
  FormsModule,
  ReactiveFormsModule,
  FormBuilder,
  FormGroup,
  Validators,
} from '@angular/forms';

import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatDividerModule } from '@angular/material/divider';
import { MatStepperModule } from '@angular/material/stepper';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { CheckoutService } from '@core/services/checkout.service';
import { CheckOutStatusDTO, CHECKOUT_PHOTO_SLOTS } from '@core/models/checkout.model';
import { CheckInPhotoType, PHOTO_TYPE_LABELS } from '@core/models/check-in.model';
import { environment } from '@environments/environment';

@Component({
  selector: 'app-guest-checkout',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatInputModule,
    MatFormFieldModule,
    MatProgressBarModule,
    MatDividerModule,
    MatStepperModule,
    MatSnackBarModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="guest-checkout">
      <mat-stepper [linear]="true" #stepper>
        <!-- Step 1: Review Check-in Photos -->
        <mat-step [completed]="step1Complete()">
          <ng-template matStepLabel>Pregledaj check-in</ng-template>
          <div class="step-content">
            <h3>Fotografije sa check-in-a</h3>
            <p class="hint">
              Pregledajte fotografije koje je domaćin napravio na početku putovanja.
            </p>

            @if (status?.checkInPhotos?.length) {
            <div class="photo-grid">
              @for (photo of status!.checkInPhotos; track photo.photoId) {
              <div class="photo-item" (click)="openPhoto(photo.url)">
                <img [src]="getPhotoUrl(photo.url)" [alt]="getPhotoLabel(photo.photoType)" />
                <span class="label">{{ getPhotoLabel(photo.photoType) }}</span>
              </div>
              }
            </div>
            } @else {
            <div class="empty-state">
              <mat-icon>photo_library</mat-icon>
              <p>Nema fotografija za prikaz</p>
            </div>
            }

            <div class="readings-comparison">
              <div class="reading">
                <mat-icon>speed</mat-icon>
                <span
                  >Početna kilometraža:
                  <strong>{{ status?.startOdometer || 'N/A' }} km</strong></span
                >
              </div>
              <div class="reading">
                <mat-icon>local_gas_station</mat-icon>
                <span
                  >Početno gorivo: <strong>{{ status?.startFuelLevel || 'N/A' }}%</strong></span
                >
              </div>
            </div>

            <div class="step-actions">
              <button
                mat-raised-button
                color="primary"
                matStepperNext
                (click)="markStep1Complete()"
              >
                Nastavi
                <mat-icon>arrow_forward</mat-icon>
              </button>
            </div>
          </div>
        </mat-step>

        <!-- Step 2: Upload Return Photos -->
        <mat-step [completed]="step2Complete()">
          <ng-template matStepLabel>Fotografije povratka</ng-template>
          <div class="step-content">
            <h3>Fotografirajte vozilo</h3>
            <p class="hint">Slikajte vozilo sa svih strana kao dokaz stanja pri povratku.</p>

            <div class="photo-upload-grid">
              @for (slot of photoSlots; track slot.type) {
              <div class="upload-slot" [class.completed]="isPhotoUploaded(slot.type)">
                <div class="slot-header">
                  <mat-icon>{{ slot.icon }}</mat-icon>
                  <span>{{ slot.label }}</span>
                  @if (slot.required) {
                  <span class="required">*</span>
                  }
                </div>

                @if (getUploadProgress(slot.type); as progress) { @if (progress.state ===
                'complete') {
                <div class="uploaded-preview">
                  <img
                    [src]="progress.previewUrl || getPhotoUrl(progress.result?.url || '')"
                    alt=""
                  />
                  <button mat-icon-button class="replace-btn" (click)="triggerUpload(slot.type)">
                    <mat-icon>refresh</mat-icon>
                  </button>
                </div>
                } @else if (progress.state === 'error') {
                <div class="upload-error">
                  <mat-icon color="warn">error</mat-icon>
                  <span>{{ progress.error }}</span>
                  <button mat-button (click)="triggerUpload(slot.type)">Pokušaj ponovo</button>
                </div>
                } @else {
                <div class="upload-progress">
                  <mat-progress-bar
                    mode="determinate"
                    [value]="progress.progress"
                  ></mat-progress-bar>
                  <span>{{ getProgressLabel(progress.state) }}</span>
                </div>
                } } @else {
                <button mat-stroked-button class="upload-btn" (click)="triggerUpload(slot.type)">
                  <mat-icon>add_a_photo</mat-icon>
                  Dodaj fotografiju
                </button>
                }

                <input
                  type="file"
                  accept="image/*"
                  capture="environment"
                  [id]="'upload-' + slot.type"
                  (change)="onFileSelected($event, slot.type)"
                  hidden
                />
              </div>
              }
            </div>

            <div class="step-actions">
              <button mat-button matStepperPrevious>
                <mat-icon>arrow_back</mat-icon>
                Nazad
              </button>
              <button
                mat-raised-button
                color="primary"
                matStepperNext
                [disabled]="!allRequiredPhotosUploaded()"
              >
                Nastavi
                <mat-icon>arrow_forward</mat-icon>
              </button>
            </div>
          </div>
        </mat-step>

        <!-- Step 3: Enter Readings & Submit -->
        <mat-step [completed]="step3Complete()">
          <ng-template matStepLabel>Završi checkout</ng-template>
          <div class="step-content">
            <h3>Unesite završne podatke</h3>
            <p class="hint">Unesite završnu kilometražu i nivo goriva.</p>

            <form [formGroup]="readingsForm" class="readings-form">
              <mat-form-field appearance="outline">
                <mat-label>Završna kilometraža (km)</mat-label>
                <input
                  matInput
                  type="number"
                  formControlName="endOdometer"
                  [min]="status?.startOdometer || 0"
                />
                <mat-icon matSuffix>speed</mat-icon>
                @if (readingsForm.get('endOdometer')?.errors?.['min']) {
                <mat-error
                  >Kilometraža ne može biti manja od početne ({{
                    status?.startOdometer
                  }}
                  km)</mat-error
                >
                }
              </mat-form-field>

              <mat-form-field appearance="outline">
                <mat-label>Nivo goriva (%)</mat-label>
                <input matInput type="number" formControlName="endFuelLevel" min="0" max="100" />
                <mat-icon matSuffix>local_gas_station</mat-icon>
                <mat-hint>0-100%</mat-hint>
              </mat-form-field>

              <mat-form-field appearance="outline">
                <mat-label>Komentar (opciono)</mat-label>
                <textarea matInput formControlName="comment" rows="3"></textarea>
              </mat-form-field>
            </form>

            <mat-divider></mat-divider>

            <!-- Summary -->
            <div class="summary">
              <h4>Pregled putovanja</h4>
              <div class="summary-item">
                <span>Pređeno kilometara:</span>
                <strong>{{ calculateMileage() }} km</strong>
              </div>
              <div class="summary-item">
                <span>Razlika goriva:</span>
                <strong [class.negative]="calculateFuelDiff() < 0"
                  >{{ calculateFuelDiff() }}%</strong
                >
              </div>
            </div>

            <div class="step-actions">
              <button mat-button matStepperPrevious>
                <mat-icon>arrow_back</mat-icon>
                Nazad
              </button>
              <button
                mat-raised-button
                color="primary"
                [disabled]="!readingsForm.valid || isSubmitting()"
                (click)="submitCheckout()"
              >
                <span [class.hidden]="isSubmitting()">
                  <mat-icon>check</mat-icon>
                  Završi checkout
                </span>
                <mat-progress-bar *ngIf="isSubmitting()" mode="indeterminate"></mat-progress-bar>
              </button>
            </div>
          </div>
        </mat-step>
      </mat-stepper>
    </div>
  `,
  styles: [
    `
      .guest-checkout {
        padding: 16px 0;
      }

      .step-content {
        padding: 24px 0;

        h3 {
          margin: 0 0 8px;
          font-size: 1.25rem;
        }

        .hint {
          color: var(--text-secondary);
          margin-bottom: 24px;
        }
      }

      .photo-grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
        gap: 12px;
        margin-bottom: 24px;
      }

      .photo-item {
        position: relative;
        aspect-ratio: 4/3;
        border-radius: 8px;
        overflow: hidden;
        cursor: pointer;
        background: var(--surface-color);

        img {
          width: 100%;
          height: 100%;
          object-fit: cover;
        }

        .label {
          position: absolute;
          bottom: 0;
          left: 0;
          right: 0;
          padding: 4px 8px;
          background: rgba(0, 0, 0, 0.6);
          color: white;
          font-size: 0.75rem;
        }
      }

      .readings-comparison {
        display: flex;
        gap: 24px;
        flex-wrap: wrap;
        margin-bottom: 24px;

        .reading {
          display: flex;
          align-items: center;
          gap: 8px;
          padding: 12px 16px;
          background: var(--surface-color);
          border-radius: 8px;

          mat-icon {
            color: var(--primary-color);
          }
        }
      }

      .empty-state {
        text-align: center;
        padding: 48px;
        background: var(--surface-color);
        border-radius: 8px;

        mat-icon {
          font-size: 48px;
          width: 48px;
          height: 48px;
          color: var(--text-secondary);
        }

        p {
          margin: 16px 0 0;
          color: var(--text-secondary);
        }
      }

      .photo-upload-grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
        gap: 16px;
        margin-bottom: 24px;
      }

      .upload-slot {
        padding: 16px;
        background: var(--surface-color);
        border-radius: 12px;
        border: 2px dashed var(--border-color);
        transition: border-color 0.2s;

        &.completed {
          border-style: solid;
          border-color: var(--success-color, #4caf50);
        }

        .slot-header {
          display: flex;
          align-items: center;
          gap: 8px;
          margin-bottom: 12px;

          mat-icon {
            font-size: 20px;
            width: 20px;
            height: 20px;
            color: var(--text-secondary);
          }

          .required {
            color: var(--warn-color);
          }
        }

        .upload-btn {
          width: 100%;
        }

        .uploaded-preview {
          position: relative;
          aspect-ratio: 4/3;
          border-radius: 8px;
          overflow: hidden;

          img {
            width: 100%;
            height: 100%;
            object-fit: cover;
          }

          .replace-btn {
            position: absolute;
            top: 4px;
            right: 4px;
            background: rgba(0, 0, 0, 0.5);
            color: white;
          }
        }

        .upload-progress {
          display: flex;
          flex-direction: column;
          gap: 8px;

          span {
            font-size: 0.75rem;
            color: var(--text-secondary);
          }
        }

        .upload-error {
          display: flex;
          flex-direction: column;
          align-items: center;
          gap: 8px;
          text-align: center;

          span {
            font-size: 0.75rem;
            color: var(--warn-color);
          }
        }
      }

      .readings-form {
        display: flex;
        flex-direction: column;
        gap: 16px;
        max-width: 400px;
      }

      .summary {
        margin: 24px 0;
        padding: 16px;
        background: var(--surface-color);
        border-radius: 8px;

        h4 {
          margin: 0 0 16px;
        }

        .summary-item {
          display: flex;
          justify-content: space-between;
          padding: 8px 0;
          border-bottom: 1px solid var(--border-color);

          &:last-child {
            border-bottom: none;
          }

          .negative {
            color: var(--warn-color);
          }
        }
      }

      .step-actions {
        display: flex;
        justify-content: space-between;
        gap: 16px;
        margin-top: 24px;
        padding-top: 16px;
        border-top: 1px solid var(--border-color);
      }

      .hidden {
        display: none;
      }
    `,
  ],
})
export class GuestCheckoutComponent {
  @Input() bookingId!: number;
  @Input() status: CheckOutStatusDTO | null = null;
  @Output() completed = new EventEmitter<void>();

  private checkoutService = inject(CheckoutService);
  private fb = inject(FormBuilder);
  private snackBar = inject(MatSnackBar);

  photoSlots = CHECKOUT_PHOTO_SLOTS;

  readingsForm: FormGroup = this.fb.group({
    endOdometer: [null, [Validators.required, Validators.min(0)]],
    endFuelLevel: [null, [Validators.required, Validators.min(0), Validators.max(100)]],
    comment: [''],
  });

  // Step completion signals
  private _step1Complete = signal(false);
  private _step3Complete = signal(false);
  private _isSubmitting = signal(false);

  step1Complete = this._step1Complete.asReadonly();
  step3Complete = this._step3Complete.asReadonly();
  isSubmitting = this._isSubmitting.asReadonly();

  // Computed signals
  step2Complete = computed(() => this.allRequiredPhotosUploaded());

  ngOnInit(): void {
    // Set min validator for odometer
    if (this.status?.startOdometer) {
      this.readingsForm
        .get('endOdometer')
        ?.setValidators([Validators.required, Validators.min(this.status.startOdometer)]);
    }
  }

  markStep1Complete(): void {
    this._step1Complete.set(true);
  }

  getPhotoUrl(url: string): string {
    if (!url) return '';
    if (url.startsWith('http')) return url;

    const baseUrl = environment.baseApiUrl.replace(/\/$/, '');

    // Handle check-in photos
    if (url.startsWith('checkin/')) {
      const pathSegment = url.replace(/^checkin\//, '');
      return `${baseUrl}/checkin/photos/${pathSegment}`;
    }

    // Handle checkout photos
    if (url.startsWith('checkout/')) {
      const pathSegment = url.replace(/^checkout\//, '');
      return `${baseUrl}/checkout/photos/${pathSegment}`;
    }

    // Fallback
    return `${baseUrl}/${url}`;
  }

  getPhotoLabel(photoType: CheckInPhotoType): string {
    return PHOTO_TYPE_LABELS[photoType] || photoType;
  }

  openPhoto(url: string): void {
    window.open(this.getPhotoUrl(url), '_blank');
  }

  // ========== Photo Upload ==========

  triggerUpload(photoType: CheckInPhotoType): void {
    const input = document.getElementById(`upload-${photoType}`) as HTMLInputElement;
    input?.click();
  }

  onFileSelected(event: Event, photoType: CheckInPhotoType): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;

    this.checkoutService.uploadPhoto(this.bookingId, file, photoType, photoType);
    input.value = '';
  }

  getUploadProgress(photoType: CheckInPhotoType) {
    return this.checkoutService.uploadProgress().get(photoType);
  }

  isPhotoUploaded(photoType: CheckInPhotoType): boolean {
    const progress = this.checkoutService.uploadProgress().get(photoType);
    return progress?.state === 'complete';
  }

  allRequiredPhotosUploaded(): boolean {
    const required = CHECKOUT_PHOTO_SLOTS.filter((s) => s.required).map((s) => s.type);
    return required.every((type) => this.isPhotoUploaded(type));
  }

  getProgressLabel(state: string): string {
    switch (state) {
      case 'compressing':
        return 'Kompresija...';
      case 'uploading':
        return 'Upload...';
      case 'validating':
        return 'Validacija...';
      default:
        return '';
    }
  }

  // ========== Calculations ==========

  calculateMileage(): number {
    const start = this.status?.startOdometer || 0;
    const end = this.readingsForm.get('endOdometer')?.value || start;
    return Math.max(0, end - start);
  }

  calculateFuelDiff(): number {
    const start = this.status?.startFuelLevel || 0;
    const end = this.readingsForm.get('endFuelLevel')?.value || start;
    return end - start;
  }

  // ========== Submit ==========

  submitCheckout(): void {
    if (!this.readingsForm.valid || !this.allRequiredPhotosUploaded()) {
      return;
    }

    this._isSubmitting.set(true);
    const { endOdometer, endFuelLevel, comment } = this.readingsForm.value;

    this.checkoutService
      .submitGuestCheckout(this.bookingId, endOdometer, endFuelLevel, comment)
      .subscribe({
        next: () => {
          this._step3Complete.set(true);
          this._isSubmitting.set(false);
          this.snackBar.open('Checkout uspešno završen!', 'OK', { duration: 3000 });
          this.completed.emit();
        },
        error: (err) => {
          this._isSubmitting.set(false);
          this.snackBar.open(err?.error?.message || 'Greška pri slanju checkout-a', 'OK', {
            duration: 5000,
          });
        },
      });
  }
}

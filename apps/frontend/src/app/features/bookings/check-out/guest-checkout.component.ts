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
  OnInit,
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
import { MatSliderModule } from '@angular/material/slider';
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
    MatSliderModule,
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
                @for (photo of status!.checkInPhotos; track photo.photoType + '-' + photo.photoId) {
                  <div class="photo-item" (click)="photo.url ? openPhoto(photo.url) : null">
                    @if (photo.url) {
                      <img
                        #photoImg
                        [src]="getPhotoUrl(photo.url)"
                        [alt]="getPhotoLabel(photo.photoType)"
                        (error)="photoImg.hidden = true; photoErrPlaceholder.hidden = false"
                      />
                      <div class="photo-placeholder" #photoErrPlaceholder hidden>
                        <mat-icon>broken_image</mat-icon>
                      </div>
                    } @else {
                      <div class="photo-placeholder">
                        <mat-icon>broken_image</mat-icon>
                      </div>
                    }
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
            <div class="section-header">
              <mat-icon>camera_alt</mat-icon>
              <div>
                <h3>Fotografije vozila</h3>
                <p class="hint">Slikajte vozilo sa svih strana kao dokaz stanja pri povratku.</p>
              </div>
            </div>

            <!-- Premium photo grid matching check-in style -->
            <div class="photo-grid">
              @for (slot of photoSlots; track slot.type) {
                <div
                  class="photo-slot"
                  [class.completed]="isPhotoUploaded(slot.type)"
                  [class.uploading]="
                    getUploadProgress(slot.type)?.state === 'uploading' ||
                    getUploadProgress(slot.type)?.state === 'compressing'
                  "
                  (click)="triggerUpload(slot.type)"
                >
                  @if (getUploadProgress(slot.type); as progress) {
                    @if (progress.state === 'complete') {
                      <img
                        [src]="progress.previewUrl || getPhotoUrl(progress.result?.url || '')"
                        [alt]="slot.label"
                        class="photo-preview"
                      />
                      <div class="success-badge">
                        <mat-icon>check_circle</mat-icon>
                      </div>
                      <button
                        mat-mini-fab
                        color="warn"
                        class="remove-photo-btn"
                        (click)="triggerUpload(slot.type); $event.stopPropagation()"
                        aria-label="Zameni fotografiju"
                      >
                        <mat-icon>refresh</mat-icon>
                      </button>
                    } @else if (progress.state === 'error') {
                      <div class="error-overlay">
                        <mat-icon>error</mat-icon>
                        <span class="error-message">{{ progress.error }}</span>
                        <button mat-button class="retry-btn">Pokušaj ponovo</button>
                      </div>
                    } @else {
                      <div class="upload-progress">
                        <mat-progress-bar
                          mode="determinate"
                          [value]="progress.progress"
                        ></mat-progress-bar>
                        <span>{{ getProgressLabel(progress.state) }}</span>
                      </div>
                    }
                  } @else {
                    <div class="photo-placeholder">
                      <mat-icon>{{ slot.icon }}</mat-icon>
                      <span>{{ slot.label }}</span>
                      @if (slot.required) {
                        <span class="required-badge">Obavezno</span>
                      }
                    </div>
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

            <!-- Progress summary -->
            <div class="progress-summary">
              <span>{{ getUploadedCount() }}/{{ photoSlots.length }} fotografija</span>
              <mat-progress-bar
                mode="determinate"
                [value]="(getUploadedCount() / photoSlots.length) * 100"
              ></mat-progress-bar>
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
              <!-- Odometer -->
              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Završna kilometraža</mat-label>
                <input
                  matInput
                  type="number"
                  formControlName="endOdometer"
                  [min]="status?.startOdometer || 0"
                  placeholder="npr. 45230"
                />
                <span matSuffix>km</span>
                <mat-hint>Unesite vrednost sa odometra</mat-hint>
                @if (readingsForm.get('endOdometer')?.errors?.['min']) {
                  <mat-error
                    >Kilometraža ne može biti manja od početne ({{
                      status?.startOdometer
                    }}
                    km)</mat-error
                  >
                }
              </mat-form-field>

              <!-- Fuel level slider (matching check-in style) -->
              <div class="fuel-section">
                <label>Nivo goriva: {{ readingsForm.get('endFuelLevel')?.value }}%</label>
                <div class="fuel-slider">
                  <mat-icon>local_gas_station</mat-icon>
                  <mat-slider min="0" max="100" step="5" discrete class="fuel-slider-input">
                    <input matSliderThumb formControlName="endFuelLevel" />
                  </mat-slider>
                  <span class="fuel-value">{{ readingsForm.get('endFuelLevel')?.value }}%</span>
                </div>
                <div class="fuel-markers">
                  <span>Prazan</span>
                  <span>1/4</span>
                  <span>1/2</span>
                  <span>3/4</span>
                  <span>Pun</span>
                </div>
              </div>

              <!-- Comment -->
              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Komentar (opciono)</mat-label>
                <textarea matInput formControlName="comment" rows="3"></textarea>
                <mat-hint>Opišite stanje vozila ili napomene</mat-hint>
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

              <!-- Phase 4D: Late Fee Display -->
              @if (status && status.lateFeeAmount && status.lateFeeAmount > 0) {
                <div class="summary-item late-fee">
                  <span>
                    <mat-icon>schedule</mat-icon>
                    Naknada za kašnjenje (Tier {{ status.lateFeeTier || '?' }}):
                  </span>
                  <strong class="fee-amount">{{ status.lateFeeAmount }} RSD</strong>
                </div>
              }

              <!-- Phase 4F: Improper Return Warning -->
              @if (status && status.improperReturnFlag) {
                <div class="improper-return-warning">
                  <mat-icon>warning</mat-icon>
                  <div class="warning-content">
                    <span class="warning-title">Nepravilan povratak detektovan</span>
                    <span class="warning-code">{{
                      getImproperReturnLabel(status.improperReturnCode)
                    }}</span>
                    @if (status.improperReturnNotes) {
                      <span class="warning-notes">{{ status.improperReturnNotes }}</span>
                    }
                  </div>
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
          color: var(--text-secondary, rgba(0, 0, 0, 0.6));
          margin: 4px 0 0;
          font-size: 14px;
        }
      }

      /* Section header matching check-in style */
      .section-header {
        display: flex;
        align-items: center;
        gap: 12px;
        margin-bottom: 20px;

        mat-icon {
          font-size: 32px;
          width: 32px;
          height: 32px;
          color: var(--brand-primary);
        }

        h3 {
          margin: 0;
          font-size: 18px;
        }
      }

      /* Premium photo grid matching check-in */
      .photo-grid {
        display: grid;
        grid-template-columns: repeat(2, 1fr);
        gap: 12px;
        margin-bottom: 16px;
      }

      .photo-slot {
        aspect-ratio: 4/3;
        border-radius: 12px;
        border: 2px dashed var(--border-color, #e0e0e0);
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
        border-color: var(--brand-primary);
      }

      .photo-placeholder {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        height: 100%;
        gap: 4px;
        color: var(--text-secondary, rgba(0, 0, 0, 0.6));
        background: var(--surface-color, #fafafa);
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
        background: var(--brand-primary);
        color: white;
        border-radius: 8px;
      }

      .photo-preview {
        width: 100%;
        height: 100%;
        object-fit: cover;
      }

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
      }

      .retry-btn {
        font-size: 12px !important;
        padding: 0 8px !important;
        min-width: auto !important;
        height: 28px !important;
      }

      /* Progress summary */
      .progress-summary {
        display: flex;
        flex-direction: column;
        gap: 8px;
        margin-bottom: 16px;

        span {
          font-size: 14px;
          color: var(--text-secondary, rgba(0, 0, 0, 0.6));
        }
      }

      /* Check-in review photos */
      .photo-item {
        position: relative;
        aspect-ratio: 4/3;
        border-radius: 8px;
        overflow: hidden;
        cursor: pointer;
        background: var(--surface-color, #fafafa);

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
          background: var(--surface-color, #fafafa);
          border-radius: 8px;

          mat-icon {
            color: var(--brand-primary);
          }
        }
      }

      .empty-state {
        text-align: center;
        padding: 48px;
        background: var(--surface-color, #fafafa);
        border-radius: 8px;

        mat-icon {
          font-size: 48px;
          width: 48px;
          height: 48px;
          color: var(--text-secondary, rgba(0, 0, 0, 0.6));
        }

        p {
          margin: 16px 0 0;
          color: var(--text-secondary, rgba(0, 0, 0, 0.6));
        }
      }

      /* Readings form */
      .readings-form {
        display: flex;
        flex-direction: column;
        gap: 16px;
        max-width: 500px;
      }

      .full-width {
        width: 100%;
      }

      /* Fuel slider matching check-in style */
      .fuel-section {
        padding: 16px;
        background: var(--surface-color, #fafafa);
        border-radius: 12px;

        label {
          font-weight: 500;
          margin-bottom: 12px;
          display: block;
        }
      }

      .fuel-slider {
        display: flex;
        align-items: center;
        gap: 12px;

        mat-icon {
          color: var(--brand-primary);
        }

        .fuel-slider-input {
          flex: 1;
        }

        .fuel-value {
          font-weight: 600;
          min-width: 48px;
          text-align: right;
        }
      }

      .fuel-markers {
        display: flex;
        justify-content: space-between;
        margin-top: 4px;
        padding: 0 40px;
        font-size: 11px;
        color: var(--text-secondary, rgba(0, 0, 0, 0.6));
      }

      /* Trip summary */
      .summary {
        margin: 24px 0;
        padding: 16px;
        background: var(--surface-color, #fafafa);
        border-radius: 8px;

        h4 {
          margin: 0 0 16px;
        }

        .summary-item {
          display: flex;
          justify-content: space-between;
          padding: 8px 0;
          border-bottom: 1px solid var(--border-color, #e0e0e0);

          &:last-child {
            border-bottom: none;
          }

          .negative {
            color: var(--warn-color, #f44336);
          }
        }

        /* Phase 4D: Late Fee Display */
        .summary-item.late-fee {
          background: rgba(239, 68, 68, 0.05);
          margin: 8px -12px;
          padding: 12px;
          border-radius: 8px;
          border-bottom: none;

          span {
            display: flex;
            align-items: center;
            gap: 8px;
            color: #dc2626;
          }

          mat-icon {
            font-size: 18px;
            width: 18px;
            height: 18px;
          }

          .fee-amount {
            color: #dc2626;
            font-size: 16px;
          }
        }

        /* Phase 4F: Improper Return Warning */
        .improper-return-warning {
          display: flex;
          align-items: flex-start;
          gap: 12px;
          margin-top: 16px;
          padding: 12px;
          background: rgba(245, 158, 11, 0.1);
          border: 1px solid rgba(245, 158, 11, 0.3);
          border-radius: 8px;

          > mat-icon {
            color: #b45309;
            font-size: 24px;
            width: 24px;
            height: 24px;
          }

          .warning-content {
            display: flex;
            flex-direction: column;
            gap: 4px;
          }

          .warning-title {
            font-weight: 600;
            color: #b45309;
            font-size: 14px;
          }

          .warning-code {
            font-size: 13px;
            color: var(--text-secondary);
          }

          .warning-notes {
            font-size: 12px;
            color: var(--text-secondary);
            font-style: italic;
          }
        }
      }

      .step-actions {
        display: flex;
        justify-content: space-between;
        gap: 16px;
        margin-top: 24px;
        padding-top: 16px;
        border-top: 1px solid var(--border-color, #e0e0e0);
      }

      .hidden {
        display: none;
      }

      /* ============================================
         DARK MODE SUPPORT
         ============================================ */

      /* Dark mode via Angular theme class */
      :host-context(.dark-theme),
      :host-context(.theme-dark) {
        .section-header {
          mat-icon {
            color: #64b5f6;
          }

          h3 {
            color: rgba(255, 255, 255, 0.92);
          }

          .hint {
            color: rgba(255, 255, 255, 0.7);
          }
        }

        .photo-slot {
          border-color: rgba(255, 255, 255, 0.2);
        }

        .photo-slot.completed {
          border-color: #4ade80;
        }

        .photo-placeholder {
          background: rgba(255, 255, 255, 0.05);
          color: rgba(255, 255, 255, 0.7);
        }

        .required-badge {
          background: var(--brand-primary);
        }

        .progress-summary span {
          color: rgba(255, 255, 255, 0.87);
        }

        .readings-comparison .reading {
          background: rgba(255, 255, 255, 0.05);

          mat-icon {
            color: #64b5f6;
          }

          span,
          strong {
            color: rgba(255, 255, 255, 0.87);
          }
        }

        .fuel-section {
          background: rgba(255, 255, 255, 0.05);

          label {
            color: rgba(255, 255, 255, 0.87);
          }

          mat-icon {
            color: #64b5f6;
          }

          .fuel-value {
            color: rgba(255, 255, 255, 0.92);
          }
        }

        .fuel-markers span {
          color: rgba(255, 255, 255, 0.6);
        }

        .summary {
          background: rgba(255, 255, 255, 0.05);

          h4 {
            color: rgba(255, 255, 255, 0.92);
          }

          .summary-item {
            border-bottom-color: rgba(255, 255, 255, 0.1);
            color: rgba(255, 255, 255, 0.87);
          }
        }

        .step-actions {
          border-top-color: rgba(255, 255, 255, 0.1);
        }

        .step-content {
          h3 {
            color: rgba(255, 255, 255, 0.92);
          }

          .hint {
            color: rgba(255, 255, 255, 0.7);
          }
        }
      }
    `,
  ],
})
export class GuestCheckoutComponent implements OnInit {
  @Input() bookingId!: number;
  @Input() status: CheckOutStatusDTO | null = null;
  @Output() completed = new EventEmitter<void>();

  private checkoutService = inject(CheckoutService);
  private fb = inject(FormBuilder);
  private snackBar = inject(MatSnackBar);

  photoSlots = CHECKOUT_PHOTO_SLOTS;

  readingsForm: FormGroup = this.fb.group({
    endOdometer: [null, [Validators.required, Validators.min(0)]],
    endFuelLevel: [50, [Validators.required, Validators.min(0), Validators.max(100)]], // Default 50% like check-in
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

  /**
   * Resolve photo URL for display.
   *
   * The backend now returns Supabase signed URLs (https://...) for all photos.
   * This method handles both:
   *   - Signed URLs (absolute) — returned as-is
   *   - Legacy storage key paths — proxied through backend API endpoints
   */
  getPhotoUrl(url: string): string {
    if (!url) return '';

    // Signed URLs from Supabase (the expected production path)
    if (url.startsWith('http')) return url;

    // Legacy fallback: relative storage key paths (pre-signed-URL migration)
    const baseUrl = environment.baseApiUrl.replace(/\/$/, '');

    if (url.startsWith('checkin/')) {
      const pathSegment = url.replace(/^checkin\//, '');
      return `${baseUrl}/checkin/photos/${pathSegment}`;
    }

    if (url.startsWith('guest-checkin/')) {
      const pathSegment = url.replace(/^guest-checkin\//, '');
      return `${baseUrl}/guest-checkin/photos/${pathSegment}`;
    }

    if (url.startsWith('checkout/')) {
      const pathSegment = url.replace(/^checkout\//, '');
      return `${baseUrl}/checkout/photos/${pathSegment}`;
    }

    if (url.startsWith('host-checkout/')) {
      const pathSegment = url.replace(/^host-checkout\//, '');
      return `${baseUrl}/host-checkout/photos/${pathSegment}`;
    }

    if (url.startsWith('bookings/')) {
      // Unexpected: backend should return signed URLs. Log and use checkin fallback.
      console.warn('[GuestCheckout] Unexpected bookings/ storage key in URL:', url);
      return `${baseUrl}/checkin/photos/${url}`;
    }

    // Fallback for any other format
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

  /**
   * Get count of uploaded photos for progress display.
   */
  getUploadedCount(): number {
    return this.photoSlots.filter((slot) => this.isPhotoUploaded(slot.type)).length;
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

  // =========================================================================
  // Phase 4F: Improper Return Display Helpers
  // =========================================================================

  /**
   * Get human-readable label for improper return codes.
   */
  getImproperReturnLabel(code?: string): string {
    switch (code) {
      case 'LOW_FUEL':
        return 'Nizak nivo goriva';
      case 'EXCESSIVE_MILEAGE':
        return 'Prekoračena kilometraža';
      case 'CLEANING_REQUIRED':
        return 'Potrebno čišćenje vozila';
      case 'SMOKING_DETECTED':
        return 'Detektovano pušenje u vozilu';
      case 'WRONG_LOCATION':
        return 'Vozilo vraćeno na pogrešnu lokaciju';
      default:
        return code || 'Nepoznat razlog';
    }
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
          const message = err?.error?.message || '';
          // C-2: Backend photo validation gate - inform user about missing photos
          if (message.toLowerCase().includes('fotografij') || message.toLowerCase().includes('photo')) {
            this.snackBar.open(
              message || 'Nedovoljno fotografija za završetak checkout-a. Molimo dodajte sve obavezne fotografije.',
              'OK',
              { duration: 7000 },
            );
          } else {
            this.snackBar.open(message || 'Greška pri slanju checkout-a', 'OK', {
              duration: 5000,
            });
          }
        },
      });
  }
}

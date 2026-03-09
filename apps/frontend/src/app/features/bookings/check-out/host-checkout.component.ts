/**
 * Host Checkout Component
 *
 * Handles host checkout confirmation:
 * - Review guest's return photos
 * - Compare with check-in photos
 * - Report damage or confirm condition
 */
import {
  Component,
  Input,
  Output,
  EventEmitter,
  inject,
  signal,
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
import { MatTabsModule } from '@angular/material/tabs';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';

import { CheckoutService } from '@core/services/checkout.service';
import { CheckOutStatusDTO } from '@core/models/checkout.model';
import { CheckInPhotoType, PHOTO_TYPE_LABELS } from '@core/models/check-in.model';
import { environment } from '@environments/environment';

@Component({
  selector: 'app-host-checkout',
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
    MatTabsModule,
    MatCheckboxModule,
    MatSnackBarModule,
    MatDialogModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="host-checkout">
      <mat-card>
        <mat-card-header>
          <mat-icon mat-card-avatar>directions_car</mat-icon>
          <mat-card-title>Potvrda povratka vozila</mat-card-title>
          <mat-card-subtitle>Pregledajte fotografije i potvrdite stanje</mat-card-subtitle>
        </mat-card-header>

        <mat-card-content>
          <!-- Trip Summary -->
          <div class="trip-summary">
            <div class="summary-row">
              <span>Pređena kilometraža:</span>
              <strong>{{ status?.totalMileage ?? 'N/A' }} km</strong>
            </div>
            <div class="summary-row">
              <span>Razlika goriva:</span>
              <strong [class.negative]="(status?.fuelDifference ?? 0) < 0">
                {{ status?.fuelDifference ?? 'N/A' }}%
              </strong>
            </div>
            @if (status && status.lateReturnMinutes && status.lateReturnMinutes > 0) {
              <div class="summary-row late">
                <span>Kašnjenje:</span>
                <strong>{{ status.lateReturnMinutes }} min</strong>
              </div>
            }
          </div>

          <mat-divider></mat-divider>

          <!-- Photo Comparison Tabs -->
          <mat-tab-group class="photo-tabs">
            <mat-tab label="Check-in fotografije">
              <div class="photo-section">
                @if (status?.checkInPhotos?.length) {
                  <div class="photo-grid">
                    @for (
                      photo of status!.checkInPhotos;
                      track photo.photoType + '-' + photo.photoId
                    ) {
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
                    <p>Nema check-in fotografija</p>
                  </div>
                }
              </div>
            </mat-tab>

            <mat-tab label="Fotografije povratka">
              <div class="photo-section">
                @if (status?.checkoutPhotos?.length) {
                  <div class="photo-grid">
                    @for (
                      photo of status!.checkoutPhotos;
                      track photo.photoType + '-' + photo.photoId
                    ) {
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
                    <p>Nema fotografija povratka</p>
                  </div>
                }
              </div>
            </mat-tab>
          </mat-tab-group>

          <mat-divider></mat-divider>

          <!-- Confirmation Form -->
          <div class="confirmation-section">
            <h3>Potvrdite stanje vozila</h3>

            @if (!showDamageForm()) {
              <div class="condition-check">
                <mat-checkbox [(ngModel)]="conditionAccepted" color="primary">
                  Potvrđujem da je vozilo vraćeno u ispravnom stanju bez novih oštećenja
                </mat-checkbox>
              </div>

              <div class="action-buttons">
                <button
                  mat-raised-button
                  color="primary"
                  [disabled]="!conditionAccepted || isSubmitting()"
                  (click)="confirmCheckout()"
                >
                  <mat-icon>check_circle</mat-icon>
                  Potvrdi checkout
                </button>
                <button mat-stroked-button color="warn" (click)="showDamageReport()">
                  <mat-icon>report_problem</mat-icon>
                  Prijavi oštećenje
                </button>
              </div>
            } @else {
              <!-- Damage Report Form -->
              <div class="damage-form">
                <h4>
                  <mat-icon color="warn">report_problem</mat-icon>
                  Prijava oštećenja
                </h4>

                <form [formGroup]="damageForm">
                  <mat-form-field appearance="outline" class="full-width">
                    <mat-label>Opis oštećenja</mat-label>
                    <textarea
                      matInput
                      formControlName="description"
                      rows="4"
                      placeholder="Opišite uočena oštećenja..."
                    ></textarea>
                  </mat-form-field>

                  <mat-form-field appearance="outline">
                    <mat-label>Procenjena šteta (RSD)</mat-label>
                    <input matInput type="number" formControlName="estimatedCost" min="0" />
                    <span matSuffix>RSD</span>
                    <mat-hint>Iznos ne može biti veći od depozita. Prijave preko 50.000 RSD zahtevaju pregled administratora.</mat-hint>
                  </mat-form-field>

                  <!-- Damage Photo Upload -->
                  <div class="damage-photos">
                    <p>Fotografije oštećenja (opciono)</p>
                    <div class="photo-upload-row">
                      @for (i of [0, 1, 2]; track i) {
                        <div class="damage-upload-slot">
                          @if (getDamagePhotoProgress(i); as progress) {
                            @if (progress.state === 'complete') {
                              <div class="uploaded-preview">
                                <img
                                  [src]="
                                    progress.previewUrl || getPhotoUrl(progress.result?.url || '')
                                  "
                                  alt=""
                                />
                              </div>
                            } @else if (progress.previewUrl) {
                              <div class="uploaded-preview uploading">
                                <img [src]="progress.previewUrl" alt="" class="uploading-preview" />
                                <mat-progress-bar
                                  mode="determinate"
                                  [value]="progress.progress"
                                ></mat-progress-bar>
                              </div>
                            } @else {
                              <mat-progress-bar
                                mode="determinate"
                                [value]="progress.progress"
                              ></mat-progress-bar>
                            }
                          } @else {
                            <button mat-icon-button (click)="triggerDamageUpload(i)">
                              <mat-icon>add_a_photo</mat-icon>
                            </button>
                          }
                          <input
                            type="file"
                            accept="image/*"
                            [id]="'damage-upload-' + i"
                            (change)="onDamageFileSelected($event, i)"
                            hidden
                          />
                        </div>
                      }
                    </div>
                  </div>

                  <mat-form-field appearance="outline" class="full-width">
                    <mat-label>Link ka proceni mehaničara (opciono)</mat-label>
                    <input
                      matInput
                      formControlName="repairQuoteUrl"
                      placeholder="https://... link ka dokumentu procene popravke"
                    />
                    <mat-hint>Preporučeno za štete preko 50.000 RSD</mat-hint>
                  </mat-form-field>

                  <mat-form-field appearance="outline" class="full-width">
                    <mat-label>Dodatne napomene</mat-label>
                    <textarea matInput formControlName="notes" rows="2"></textarea>
                  </mat-form-field>
                </form>

                <div class="action-buttons">
                  <button mat-button (click)="cancelDamageReport()">Odustani</button>
                  <button
                    mat-raised-button
                    color="warn"
                    [disabled]="!damageForm.valid || isSubmitting()"
                    (click)="submitDamageReport()"
                  >
                    <span [class.hidden]="isSubmitting()">
                      <mat-icon>send</mat-icon>
                      Prijavi i završi checkout
                    </span>
                    <mat-progress-bar
                      *ngIf="isSubmitting()"
                      mode="indeterminate"
                    ></mat-progress-bar>
                  </button>
                </div>
              </div>
            }
          </div>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [
    `
      .host-checkout {
        padding: 16px 0;
      }

      mat-card-header {
        margin-bottom: 16px;

        mat-icon[mat-card-avatar] {
          background: var(--primary-color);
          color: white;
          border-radius: 50%;
          padding: 8px;
          font-size: 24px;
          width: 40px;
          height: 40px;
        }
      }

      .trip-summary {
        display: flex;
        flex-wrap: wrap;
        gap: 24px;
        padding: 16px;
        background: var(--surface-color);
        border-radius: 8px;
        margin-bottom: 16px;

        .summary-row {
          display: flex;
          gap: 8px;

          &.late {
            color: var(--warn-color);
          }

          .negative {
            color: var(--warn-color);
          }
        }
      }

      .photo-tabs {
        margin: 16px 0;
      }

      .photo-section {
        padding: 16px 0;
      }

      .photo-grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
        gap: 12px;
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

        .photo-placeholder {
          display: flex;
          align-items: center;
          justify-content: center;
          width: 100%;
          height: 100%;
          background: var(--surface-variant, #f5f5f5);
          color: var(--text-secondary, #999);

          mat-icon {
            font-size: 32px;
            width: 32px;
            height: 32px;
          }
        }

        .label {
          position: absolute;
          bottom: 0;
          left: 0;
          right: 0;
          padding: 4px 8px;
          background: rgba(0, 0, 0, 0.6);
          color: white;
          font-size: 0.7rem;
        }
      }

      .empty-state {
        text-align: center;
        padding: 32px;
        color: var(--text-secondary);
      }

      .confirmation-section {
        padding: 16px 0;

        h3 {
          margin: 0 0 16px;
        }
      }

      .condition-check {
        margin-bottom: 24px;
      }

      .action-buttons {
        display: flex;
        gap: 16px;
        flex-wrap: wrap;
      }

      .damage-form {
        h4 {
          display: flex;
          align-items: center;
          gap: 8px;
          margin: 0 0 16px;
        }

        .full-width {
          width: 100%;
        }

        .damage-photos {
          margin: 16px 0;

          p {
            margin: 0 0 8px;
            font-size: 0.875rem;
            color: var(--text-secondary);
          }
        }

        .photo-upload-row {
          display: flex;
          gap: 12px;
        }

        .damage-upload-slot {
          width: 80px;
          height: 80px;
          border: 2px dashed var(--border-color);
          border-radius: 8px;
          display: flex;
          align-items: center;
          justify-content: center;
          overflow: hidden;

          .uploaded-preview {
            width: 100%;
            height: 100%;

            img {
              width: 100%;
              height: 100%;
              object-fit: cover;
            }
          }
        }
      }

      .hidden {
        display: none;
      }
    `,
  ],
})
export class HostCheckoutComponent {
  @Input() bookingId!: number;
  @Input() status: CheckOutStatusDTO | null = null;
  @Output() confirmed = new EventEmitter<void>();

  private checkoutService = inject(CheckoutService);
  private fb = inject(FormBuilder);
  private snackBar = inject(MatSnackBar);

  conditionAccepted = false;
  private _showDamageForm = signal(false);
  private _isSubmitting = signal(false);

  showDamageForm = this._showDamageForm.asReadonly();
  isSubmitting = this._isSubmitting.asReadonly();

  damageForm: FormGroup = this.fb.group({
    description: ['', Validators.required],
    estimatedCost: [null, [Validators.required, Validators.min(0)]],
    repairQuoteUrl: [''],
    notes: [''],
  });

  // Track uploaded damage photo IDs
  private damagePhotoIds: number[] = [];

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
      console.warn('[HostCheckout] Unexpected bookings/ storage key in URL:', url);
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

  showDamageReport(): void {
    this._showDamageForm.set(true);
  }

  cancelDamageReport(): void {
    this._showDamageForm.set(false);
    this.damageForm.reset();
  }

  // ========== Damage Photo Upload ==========

  triggerDamageUpload(index: number): void {
    const input = document.getElementById(`damage-upload-${index}`) as HTMLInputElement;
    input?.click();
  }

  onDamageFileSelected(event: Event, index: number): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;

    const slotId = `damage-${index}`;
    this.checkoutService.uploadHostPhoto(
      this.bookingId,
      file,
      slotId,
      'HOST_CHECKOUT_DAMAGE_EVIDENCE',
    );
    input.value = '';
  }

  getDamagePhotoProgress(index: number) {
    return this.checkoutService.uploadProgress().get(`damage-${index}`);
  }

  // ========== Submit ==========

  confirmCheckout(): void {
    if (!this.conditionAccepted) return;

    this._isSubmitting.set(true);

    this.checkoutService
      .confirmHostCheckout(
        this.bookingId,
        true, // conditionAccepted
      )
      .subscribe({
        next: () => {
          this._isSubmitting.set(false);
          this.snackBar.open('Checkout uspešno potvrđen!', 'OK', { duration: 3000 });
          this.confirmed.emit();
        },
        error: (err) => {
          this._isSubmitting.set(false);
          this.snackBar.open(err?.error?.message || 'Greška pri potvrđivanju checkout-a', 'OK', {
            duration: 5000,
          });
        },
      });
  }

  submitDamageReport(): void {
    if (!this.damageForm.valid) return;

    this._isSubmitting.set(true);
    const { description, estimatedCost, repairQuoteUrl, notes } = this.damageForm.value;

    // Collect uploaded damage photo IDs
    const photoIds: number[] = [];
    for (let i = 0; i < 3; i++) {
      const progress = this.checkoutService.uploadProgress().get(`damage-${i}`);
      if (progress?.state === 'complete' && progress.result?.photoId) {
        photoIds.push(progress.result.photoId);
      }
    }

    this.checkoutService
      .confirmHostCheckout(
        this.bookingId,
        false, // conditionAccepted = false due to damage
        {
          description,
          estimatedCostRsd: estimatedCost,
          photoIds,
          repairQuoteDocumentUrl: repairQuoteUrl || undefined,
        },
        notes,
      )
      .subscribe({
        next: () => {
          this._isSubmitting.set(false);
          this.snackBar.open('Oštećenje prijavljeno. Gost će biti obavešten.', 'OK', {
            duration: 3000,
          });
          this.confirmed.emit();
        },
        error: (err) => {
          this._isSubmitting.set(false);
          const message = err?.error?.message || '';
          // H-4/C-5: Show specific validation errors (deposit cap, admin review threshold)
          this.snackBar.open(message || 'Greška pri prijavi oštećenja', 'OK', {
            duration: message.includes('depozit') || message.includes('administrator') ? 7000 : 5000,
          });
        },
      });
  }
}
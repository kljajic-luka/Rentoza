/**
 * Checkout Damage Dispute Component
 *
 * Shown when host has reported damage at checkout (CHECKOUT_DAMAGE_DISPUTE status).
 * - Guest view: Can accept or dispute the damage claim
 * - Host view: Waiting for guest response, shows claim status
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
import { FormsModule } from '@angular/forms';

import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatDividerModule } from '@angular/material/divider';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { CheckOutStatusDTO } from '@core/models/checkout.model';
import { CheckoutService } from '@core/services/checkout.service';

@Component({
  selector: 'app-checkout-damage-dispute',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatFormFieldModule,
    MatInputModule,
    MatDividerModule,
    MatSnackBarModule,
    MatProgressBarModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="damage-dispute-container">
      <!-- Alert Banner -->
      <div class="alert-banner">
        <mat-icon>warning</mat-icon>
        <div>
          <h3>Prijavljeno oštećenje pri checkout-u</h3>
          <p>Domaćin je prijavio oštećenje na vozilu. Depozit je zadržan dok se spor ne reši.</p>
        </div>
      </div>

      <!-- Damage Details -->
      <mat-card class="damage-details-card">
        <mat-card-header>
          <mat-icon mat-card-avatar class="damage-icon">report_problem</mat-icon>
          <mat-card-title>Detalji prijave</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          @if (status) {
            <div class="detail-row">
              <span class="label">Opis oštećenja:</span>
              <span class="value">{{ status.damageDescription || 'Nije naveden' }}</span>
            </div>
            <div class="detail-row">
              <span class="label">Procenjena šteta:</span>
              <span class="value amount">{{
                status.damageClaimAmount
                  ? (status.damageClaimAmount | number: '1.0-0') + ' RSD'
                  : 'Nije procenjeno'
              }}</span>
            </div>
            <div class="detail-row">
              <span class="label">Status:</span>
              <span
                class="value status-badge"
                [class]="'status-' + (status.damageClaimStatus || 'unknown')"
              >
                {{ getClaimStatusLabel(status.damageClaimStatus) }}
              </span>
            </div>
          }
        </mat-card-content>
      </mat-card>

      <!-- C-6: Admin Review Required Banner -->
      @if (status && status.damageClaimAmount && status.damageClaimAmount > 50000) {
        <div class="admin-review-banner">
          <mat-icon>admin_panel_settings</mat-icon>
          <div>
            <h4>Potreban pregled administratora</h4>
            <p>Prijava štete preko 50.000 RSD zahteva pregled administratora pre odobravanja.</p>
          </div>
        </div>
      }

      <!-- Guest Actions -->
      @if (role === 'GUEST') {
        @if (!showDisputeForm()) {
          <div class="guest-actions">
            <mat-card class="action-card">
              <mat-card-header>
                <mat-card-title>Vaše opcije</mat-card-title>
                <mat-card-subtitle>Izaberite kako želite da postupite</mat-card-subtitle>
              </mat-card-header>
              <mat-card-content>
                <div class="action-buttons">
                  <button
                    mat-raised-button
                    color="primary"
                    (click)="acceptClaim()"
                    [disabled]="isProcessing()"
                  >
                    @if (isProcessing()) {
                      <mat-spinner diameter="20"></mat-spinner>
                    } @else {
                      <mat-icon>check_circle</mat-icon>
                    }
                    Prihvatam prijavu
                  </button>
                  <button
                    mat-raised-button
                    color="warn"
                    (click)="showDisputeForm.set(true)"
                    [disabled]="isProcessing()"
                  >
                    <mat-icon>gavel</mat-icon>
                    Ospori prijavu
                  </button>
                </div>
                <p class="action-note">
                  <mat-icon>info</mat-icon>
                  Prihvatanjem prijave, depozit će biti zadržan za pokrivanje troškova.
                  Osporavanjem, admin tim će pregledati slučaj.
                </p>
              </mat-card-content>
            </mat-card>
          </div>
        }

        <!-- Dispute Form -->
        @if (showDisputeForm()) {
          <mat-card class="dispute-form-card">
            <mat-card-header>
              <mat-card-title>Osporite prijavu</mat-card-title>
              <mat-card-subtitle>Opišite zašto osporavate prijavu oštećenja</mat-card-subtitle>
            </mat-card-header>
            <mat-card-content>
              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Razlog osporavanja</mat-label>
                <textarea
                  matInput
                  [(ngModel)]="disputeReason"
                  rows="4"
                  placeholder="Opišite detaljno zašto smatrate da prijava nije opravdana..."
                  required
                ></textarea>
                <mat-hint>Minimum 20 karaktera</mat-hint>
              </mat-form-field>

              <!-- Evidence Photo Upload -->
              <div class="evidence-photos">
                <p><mat-icon>photo_camera</mat-icon> Dokazi (fotografije) - opciono</p>
                <div class="photo-upload-row">
                  @for (i of [0, 1, 2]; track i) {
                    <div class="evidence-upload-slot">
                      @if (getEvidenceProgress(i); as progress) {
                        @if (progress.state === 'complete') {
                          <div class="uploaded-preview">
                            <mat-icon color="primary">check_circle</mat-icon>
                          </div>
                        } @else {
                          <mat-progress-bar
                            mode="determinate"
                            [value]="progress.progress"
                          ></mat-progress-bar>
                        }
                      } @else {
                        <button mat-icon-button (click)="triggerEvidenceUpload(i)">
                          <mat-icon>add_a_photo</mat-icon>
                        </button>
                      }
                      <input
                        type="file"
                        accept="image/*"
                        [id]="'evidence-upload-' + i"
                        (change)="onEvidenceFileSelected($event, i)"
                        hidden
                      />
                    </div>
                  }
                </div>
              </div>
            </mat-card-content>
            <mat-card-actions align="end">
              <button mat-button (click)="showDisputeForm.set(false)" [disabled]="isProcessing()">
                Otkaži
              </button>
              <button
                mat-raised-button
                color="warn"
                (click)="submitDispute()"
                [disabled]="isProcessing() || disputeReason.length < 20"
              >
                @if (isProcessing()) {
                  <mat-spinner diameter="20"></mat-spinner>
                } @else {
                  <mat-icon>send</mat-icon>
                }
                Pošalji osporavanje
              </button>
            </mat-card-actions>
          </mat-card>
        }
      }

      <!-- Host View -->
      @if (role === 'HOST') {
        <mat-card class="host-waiting-card">
          <mat-card-content>
            <div class="waiting-status">
              <mat-icon class="waiting-icon">hourglass_empty</mat-icon>
              <h3>Čekanje na odgovor gosta</h3>
              <p>Gost ima 7 dana da prihvati ili ospori prijavu.</p>
              <p class="deposit-note">
                <mat-icon>lock</mat-icon>
                Depozit je zadržan do rešavanja spora.
              </p>
            </div>
          </mat-card-content>
        </mat-card>
      }
    </div>
  `,
  styles: [
    `
      .damage-dispute-container {
        display: flex;
        flex-direction: column;
        gap: 16px;
      }

      .alert-banner {
        display: flex;
        gap: 16px;
        padding: 16px 20px;
        background: #fff3e0;
        border-left: 4px solid #ff9800;
        border-radius: 8px;
        align-items: flex-start;

        mat-icon {
          color: #ff9800;
          font-size: 32px;
          width: 32px;
          height: 32px;
          flex-shrink: 0;
        }

        h3 {
          margin: 0 0 4px;
          font-size: 1.1rem;
        }
        p {
          margin: 0;
          color: #666;
          font-size: 0.9rem;
        }
      }

      .damage-icon {
        background: #ffebee;
        color: #d32f2f !important;
      }

      .detail-row {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 8px 0;
        border-bottom: 1px solid #eee;
      }

      .label {
        color: #666;
        font-weight: 500;
      }
      .value {
        font-weight: 500;
      }
      .value.amount {
        color: #d32f2f;
        font-size: 1.1rem;
      }

      .status-badge {
        padding: 4px 12px;
        border-radius: 16px;
        font-size: 0.85rem;
      }

      .status-CHECKOUT_PENDING {
        background: #fff3e0;
        color: #e65100;
      }
      .status-CHECKOUT_GUEST_ACCEPTED {
        background: #e8f5e9;
        color: #2e7d32;
      }
      .status-CHECKOUT_GUEST_DISPUTED {
        background: #fce4ec;
        color: #c62828;
      }
      .status-CHECKOUT_ADMIN_APPROVED {
        background: #e3f2fd;
        color: #1565c0;
      }
      .status-CHECKOUT_ADMIN_REJECTED {
        background: #fafafa;
        color: #616161;
      }

      .action-buttons {
        display: flex;
        gap: 16px;
        margin: 16px 0;
      }

      .action-note {
        display: flex;
        align-items: center;
        gap: 8px;
        color: #666;
        font-size: 0.85rem;
        mat-icon {
          font-size: 18px;
          width: 18px;
          height: 18px;
        }
      }

      .full-width {
        width: 100%;
      }

      .evidence-photos {
        margin: 12px 0;
        p {
          display: flex;
          align-items: center;
          gap: 6px;
          color: #666;
          font-size: 0.9rem;
          margin-bottom: 8px;
          mat-icon {
            font-size: 18px;
            width: 18px;
            height: 18px;
          }
        }
      }

      .photo-upload-row {
        display: flex;
        gap: 12px;
      }

      .evidence-upload-slot {
        width: 64px;
        height: 64px;
        border: 2px dashed #ccc;
        border-radius: 8px;
        display: flex;
        align-items: center;
        justify-content: center;
      }

      .uploaded-preview {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 100%;
        height: 100%;
      }

      .waiting-status {
        display: flex;
        flex-direction: column;
        align-items: center;
        text-align: center;
        padding: 24px;
        gap: 8px;
      }

      .waiting-icon {
        font-size: 48px;
        width: 48px;
        height: 48px;
        color: #ff9800;
      }

      .deposit-note {
        display: flex;
        align-items: center;
        gap: 6px;
        color: #d32f2f;
        font-weight: 500;
        mat-icon {
          font-size: 18px;
          width: 18px;
          height: 18px;
        }
      }

      .admin-review-banner {
        display: flex;
        gap: 16px;
        padding: 16px 20px;
        background: #e3f2fd;
        border-left: 4px solid #1565c0;
        border-radius: 8px;
        align-items: flex-start;

        mat-icon {
          color: #1565c0;
          font-size: 28px;
          width: 28px;
          height: 28px;
          flex-shrink: 0;
        }

        h4 {
          margin: 0 0 4px;
          font-size: 1rem;
          color: #1565c0;
        }
        p {
          margin: 0;
          color: #666;
          font-size: 0.85rem;
        }
      }

      :host-context(.dark-theme) {
        .alert-banner {
          background: #3e2723;
        }
        .detail-row {
          border-bottom-color: #333;
        }
        .admin-review-banner {
          background: #0d2137;
        }
      }
    `,
  ],
})
export class CheckoutDamageDisputeComponent {
  @Input() bookingId!: number;
  @Input() status: CheckOutStatusDTO | null = null;
  @Input() role: 'GUEST' | 'HOST' = 'GUEST';
  @Output() resolved = new EventEmitter<void>();

  private checkoutService = inject(CheckoutService);
  private snackBar = inject(MatSnackBar);

  showDisputeForm = signal(false);
  isProcessing = signal(false);
  disputeReason = '';

  // Evidence photo upload support
  triggerEvidenceUpload(index: number): void {
    const input = document.getElementById(`evidence-upload-${index}`) as HTMLInputElement;
    input?.click();
  }

  onEvidenceFileSelected(event: Event, index: number): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;

    const slotId = `evidence-${index}`;
    // Use guest checkout photo upload for evidence (CHECKOUT_DAMAGE_NEW type)
    this.checkoutService.uploadPhoto(this.bookingId, file, slotId, 'CHECKOUT_DAMAGE_NEW');
    input.value = '';
  }

  getEvidenceProgress(index: number) {
    return this.checkoutService.uploadProgress().get(`evidence-${index}`);
  }

  private collectEvidencePhotoIds(): number[] {
    const ids: number[] = [];
    for (let i = 0; i < 3; i++) {
      const progress = this.checkoutService.uploadProgress().get(`evidence-${i}`);
      if (progress?.state === 'complete' && progress.result?.photoId) {
        ids.push(progress.result.photoId);
      }
    }
    return ids;
  }

  getClaimStatusLabel(status: string | null): string {
    const labels: Record<string, string> = {
      CHECKOUT_PENDING: 'Čeka odgovor gosta',
      CHECKOUT_GUEST_ACCEPTED: 'Gost prihvatio',
      CHECKOUT_GUEST_DISPUTED: 'Gost osporio - čeka admin',
      CHECKOUT_ADMIN_APPROVED: 'Admin odobrio',
      CHECKOUT_ADMIN_REJECTED: 'Admin odbio',
      CHECKOUT_TIMEOUT_ESCALATED: 'Eskalirano (timeout)',
    };
    return labels[status || ''] || status || 'Nepoznato';
  }

  acceptClaim(): void {
    this.isProcessing.set(true);
    this.checkoutService.acceptDamageClaim(this.bookingId).subscribe({
      next: () => {
        this.isProcessing.set(false);
        this.snackBar.open('Prijava prihvaćena. Checkout se završava.', 'Zatvori', {
          duration: 4000,
        });
        this.resolved.emit();
      },
      error: (err) => {
        this.isProcessing.set(false);
        const message = err?.error?.message || '';
        // C-6: Show specific error when admin review is required for high-value claims
        this.snackBar.open(
          message || 'Greška pri prihvatanju prijave.',
          'Zatvori',
          { duration: message.includes('administrator') ? 7000 : 4000 },
        );
      },
    });
  }

  submitDispute(): void {
    if (this.disputeReason.length < 20) return;

    this.isProcessing.set(true);
    const evidencePhotoIds = this.collectEvidencePhotoIds();
    this.checkoutService
      .disputeDamageClaim(
        this.bookingId,
        this.disputeReason,
        evidencePhotoIds.length > 0 ? evidencePhotoIds : undefined,
      )
      .subscribe({
        next: () => {
          this.isProcessing.set(false);
          this.snackBar.open('Osporavanje poslato admin timu na pregled.', 'Zatvori', {
            duration: 4000,
          });
          this.resolved.emit();
        },
        error: (err) => {
          this.isProcessing.set(false);
          this.snackBar.open(
            err?.error?.message || 'Greška pri slanju osporavanja.',
            'Zatvori',
            { duration: 5000 },
          );
        },
      });
  }
}
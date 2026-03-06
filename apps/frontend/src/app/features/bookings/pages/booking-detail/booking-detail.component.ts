/**
 * Booking Detail Page Component
 *
 * Displays full booking details and provides access to check-in workflow.
 */
import {
  Component,
  OnInit,
  inject,
  signal,
  computed,
  ChangeDetectionStrategy,
  DestroyRef,
  ViewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatDividerModule } from '@angular/material/divider';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';

import { BookingService, RentalAgreementDTO } from '../../../../core/services/booking.service';
import { BookingDetails, PickupLocationData } from '../../../../core/models/booking-details.model';
import { AuthService } from '../../../../core/auth/auth.service';
import { ReadOnlyPickupLocationComponent } from '../../components/readonly-pickup-location';
import {
  ChargeLifecycleStatus,
  DepositLifecycleStatus,
  getGuestPaymentSummary,
  getGuestDepositSummary,
  getChargeStatusIcon,
  getChargeStatusColor,
  getDepositStatusIcon,
  getDepositStatusColor,
  isChargeStatusActionRequired,
} from '../../../../core/payment/payment-status.mapper';
import { PaymentFormComponent } from '@core/payment/payment-form/payment-form.component';
import {
  BookingReauthorizeResponse,
  TripExtension,
} from '@core/models/trip-extension.model';
import {
  formatDateTimeSerbiaValue,
  getSerbiaDateInputValue,
  parseSerbiaDateTime,
} from '@core/utils/serbia-time.util';

@Component({
  selector: 'app-booking-detail',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatDividerModule,
    MatProgressSpinnerModule,
    MatChipsModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    ReadOnlyPickupLocationComponent,
    PaymentFormComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="booking-detail-page">
      <!-- Loading -->
      @if (isLoading()) {
        <div class="loading-container">
          <mat-spinner diameter="48"></mat-spinner>
          <p>Učitavanje rezervacije...</p>
        </div>
      }

      <!-- Error -->
      @if (error()) {
        <div class="error-container">
          <mat-icon>error</mat-icon>
          <h2>Greška</h2>
          <p>{{ error() }}</p>
          <button mat-raised-button color="primary" (click)="loadBooking()">Pokušaj ponovo</button>
        </div>
      }

      <!-- Content -->
      @if (booking() && !isLoading()) {
        <header class="page-header">
          <button mat-icon-button (click)="goBack()">
            <mat-icon>arrow_back</mat-icon>
          </button>
          <h1>Rezervacija #{{ booking()?.id }}</h1>
        </header>

        <!-- Status badge -->
        <mat-chip-set class="status-chips">
          <mat-chip [class]="'status-' + booking()?.status?.toLowerCase()">
            {{ getStatusLabel(booking()?.status) }}
          </mat-chip>
        </mat-chip-set>

        <!-- Live countdown banner -->
        @if (countdownText()) {
          <div class="countdown-banner" [class.checkout-prep]="isCheckoutPrep()">
            <mat-icon>{{ countdownIcon() }}</mat-icon>
            <span>{{ countdownText() }}</span>
          </div>
        }

        <!-- Car info -->
        <mat-card class="car-card">
          @if (booking()?.primaryImageUrl) {
            <img [src]="booking()?.primaryImageUrl" [alt]="carTitle()" class="car-image" />
          }
          <mat-card-content>
            <h3>{{ carTitle() }}</h3>
            <p class="car-owner">
              <mat-icon>person</mat-icon>
              {{ booking()?.hostName || 'Domaćin' }}
            </p>
          </mat-card-content>
        </mat-card>

        <!-- Dates -->
        <mat-card class="dates-card">
          <mat-card-content>
            <div class="date-row">
              <mat-icon>event</mat-icon>
              <div>
                <span class="label">Od</span>
                <span class="value">{{ formatBookingDateTime(booking()?.startTime) }}</span>
              </div>
            </div>
            <mat-divider></mat-divider>
            <div class="date-row">
              <mat-icon>event</mat-icon>
              <div>
                <span class="label">Do</span>
                <span class="value">{{ formatBookingDateTime(booking()?.endTime) }}</span>
              </div>
            </div>
          </mat-card-content>
        </mat-card>

        <!-- Pickup Location -->
        @if (pickupLocationData()) {
          <div class="pickup-location-section">
            <app-readonly-pickup-location
              [pickupLocation]="pickupLocationData()"
              [showDeliveryInfo]="hasDeliveryInfo()"
              [deliveryDistance]="booking()?.deliveryDistanceKm ?? null"
              [deliveryFee]="booking()?.deliveryFeeCalculated ?? null"
              mode="standard"
            />
          </div>
        }

        <!-- Pricing -->
        <mat-card class="pricing-card">
          <mat-card-content>
            <h4>Cena</h4>
            <div class="price-row">
              <span>Ukupno</span>
              <span class="price">{{ booking()?.totalPrice | currency: 'EUR' }}</span>
            </div>
          </mat-card-content>
        </mat-card>

        <!-- Payment Status -->
        @if (paymentSummary()) {
          <mat-card class="payment-status-card">
            <mat-card-content>
              <h4>Status placanja</h4>
              <div class="status-row" [class]="'status-color--' + chargeStatusColor()">
                <mat-icon>{{ chargeStatusIcon() }}</mat-icon>
                <span>{{ paymentSummary() }}</span>
              </div>
              @if (isPaymentActionRequired()) {
                @if (showPaymentReauth()) {
                  <div class="payment-action-card">
                    <p class="status-action-hint">
                      <mat-icon>info</mat-icon>
                      Karticu treba ponovo autorizovati da bi rezervacija ostala finansijski pokrivena.
                    </p>

                    <app-payment-form
                      (validityChange)="onReauthCardValidityChanged($event)"
                    />

                    <button
                      mat-raised-button
                      color="primary"
                      (click)="submitReauthorization()"
                      [disabled]="isReauthorizingPayment() || !reauthCardValid()"
                    >
                      @if (isReauthorizingPayment()) {
                        <mat-spinner diameter="20"></mat-spinner>
                      } @else {
                        <ng-container>
                          <mat-icon>lock_reset</mat-icon>
                          Ponovo autorizuj karticu
                        </ng-container>
                      }
                    </button>
                  </div>
                } @else {
                  <p class="status-action-hint">
                    <mat-icon>info</mat-icon>
                    Potrebna je vasa akcija — kontaktirajte podrsku
                  </p>
                }
              }
            </mat-card-content>
          </mat-card>
        }

        <!-- Deposit Status -->
        @if (depositSummary()) {
          <mat-card class="deposit-status-card">
            <mat-card-content>
              <h4>Sigurnosni depozit</h4>
              <div class="status-row" [class]="'status-color--' + depositStatusColor()">
                <mat-icon>{{ depositStatusIcon() }}</mat-icon>
                <span>{{ depositSummary() }}</span>
              </div>
            </mat-card-content>
          </mat-card>
        }

        <!-- Rental Agreement -->
        @if (agreement()) {
          <mat-card class="agreement-card">
            <mat-card-content>
              <h4>Ugovor o iznajmljivanju</h4>

              <!-- Agreement terms snapshot -->
              @if (agreement()?.termsSnapshot) {
                <div class="agreement-terms">
                  <div class="terms-section">
                    <span class="terms-label">Početak:</span>
                    <span>{{ agreement()?.termsSnapshot?.['startTime'] }}</span>
                  </div>
                  <div class="terms-section">
                    <span class="terms-label">Kraj:</span>
                    <span>{{ agreement()?.termsSnapshot?.['endTime'] }}</span>
                  </div>
                  <div class="terms-section">
                    <span class="terms-label">Ukupna cena:</span>
                    <span>{{ agreement()?.termsSnapshot?.['totalPrice'] }} RSD</span>
                  </div>
                  @if (agreement()?.termsSnapshot?.['securityDeposit']) {
                    <div class="terms-section">
                      <span class="terms-label">Depozit:</span>
                      <span>{{ agreement()?.termsSnapshot?.['securityDeposit'] }} RSD</span>
                    </div>
                  }
                  <div class="terms-section">
                    <span class="terms-label">Osiguranje:</span>
                    <span>{{ agreement()?.termsSnapshot?.['insuranceType'] }}</span>
                  </div>
                </div>
              }

              <!-- Vehicle snapshot -->
              @if (agreement()?.vehicleSnapshot) {
                <div class="agreement-vehicle">
                  <span class="terms-label">Vozilo:</span>
                  <span
                    >{{ agreement()?.vehicleSnapshot?.['brand'] }}
                    {{ agreement()?.vehicleSnapshot?.['model'] }} ({{
                      agreement()?.vehicleSnapshot?.['year']
                    }})</span
                  >
                  @if (agreement()?.vehicleSnapshot?.['licensePlate']) {
                    <span class="license-plate">{{
                      agreement()?.vehicleSnapshot?.['licensePlate']
                    }}</span>
                  }
                </div>
              }

              <mat-divider></mat-divider>

              <div class="agreement-status">
                <div class="agreement-party">
                  <mat-icon [class.accepted]="agreement()?.ownerAccepted">
                    {{ agreement()?.ownerAccepted ? 'check_circle' : 'radio_button_unchecked' }}
                  </mat-icon>
                  <span>Vlasnik: {{ agreement()?.ownerAccepted ? 'Prihvatio' : 'Čeka' }}</span>
                </div>
                <div class="agreement-party">
                  <mat-icon [class.accepted]="agreement()?.renterAccepted">
                    {{ agreement()?.renterAccepted ? 'check_circle' : 'radio_button_unchecked' }}
                  </mat-icon>
                  <span>Gost: {{ agreement()?.renterAccepted ? 'Prihvatio' : 'Čeka' }}</span>
                </div>
              </div>
              @if (canAcceptAgreement()) {
                <div class="acceptance-confirm">
                  <mat-checkbox
                    [checked]="agreementConfirmed()"
                    (change)="agreementConfirmed.set($event.checked)"
                    color="primary"
                  >
                    Pročitao/la sam i prihvatam uslove ugovora
                  </mat-checkbox>
                </div>
                <button
                  mat-raised-button
                  color="primary"
                  (click)="acceptAgreement()"
                  [disabled]="isAcceptingAgreement() || !agreementConfirmed()"
                  class="accept-agreement-btn"
                >
                  @if (isAcceptingAgreement()) {
                    <mat-spinner diameter="20"></mat-spinner>
                  } @else {
                    <ng-container>
                      <mat-icon>gavel</mat-icon>
                      Prihvatam uslove ugovora
                    </ng-container>
                  }
                </button>
              }
              @if (agreement()?.status === 'FULLY_ACCEPTED') {
                <div class="agreement-accepted-banner">
                  <mat-icon>verified</mat-icon>
                  <span>Ugovor prihvaćen od obe strane</span>
                </div>
              }
            </mat-card-content>
          </mat-card>
        }

        @if (showExtensionSection()) {
          <mat-card class="extension-card">
            <mat-card-content>
              <h4>Produženje putovanja</h4>

              @if (pendingExtension()) {
                <div class="extension-panel extension-panel--pending">
                  <div class="extension-summary-row">
                    <span class="extension-summary-label">Trenutni zahtev</span>
                    <mat-chip [class]="getExtensionStatusClass(pendingExtension()!.status)">
                      {{ pendingExtension()!.statusDisplay }}
                    </mat-chip>
                  </div>

                  <div class="extension-meta-grid">
                    <div>
                      <span class="extension-meta-label">Novi datum završetka</span>
                      <span>{{ pendingExtension()!.requestedEndDate | date: 'dd.MM.yyyy' }}</span>
                    </div>
                    <div>
                      <span class="extension-meta-label">Dodatni trošak</span>
                      <span>{{ pendingExtension()!.additionalCost || 0 | currency: 'RSD' : 'symbol' : '1.0-0' }}</span>
                    </div>
                  </div>

                  @if (pendingExtension()!.reason) {
                    <p class="extension-note">{{ pendingExtension()!.reason }}</p>
                  }

                  @if (pendingExtension()!.hostResponse) {
                    <p class="extension-response">
                      <strong>Odgovor domaćina:</strong> {{ pendingExtension()!.hostResponse }}
                    </p>
                  }

                  @if (isRenterView() && canContinueExtensionPayment()) {
                    <button
                      mat-raised-button
                      color="primary"
                      (click)="continueExtensionPayment()"
                      [disabled]="isUpdatingExtension()"
                    >
                      <mat-icon>open_in_new</mat-icon>
                      Nastavi plaćanje produženja
                    </button>
                  }

                  @if (isRenterView() && canCancelPendingExtension()) {
                    <button
                      mat-stroked-button
                      color="warn"
                      (click)="cancelPendingExtension()"
                      [disabled]="isUpdatingExtension()"
                    >
                      <mat-icon>cancel</mat-icon>
                      Otkaži zahtev
                    </button>
                  }

                  @if (isHostView() && canRespondToPendingExtension()) {
                    <mat-form-field appearance="outline" class="extension-field">
                      <mat-label>Odgovor domaćina</mat-label>
                      <textarea
                        matInput
                        rows="3"
                        [ngModel]="hostExtensionResponse()"
                        (ngModelChange)="hostExtensionResponse.set($event)"
                        placeholder="Opciona poruka za gosta"
                      ></textarea>
                    </mat-form-field>

                    <div class="extension-actions-row">
                      <button
                        mat-raised-button
                        color="primary"
                        (click)="approvePendingExtension()"
                        [disabled]="isUpdatingExtension()"
                      >
                        <mat-icon>check</mat-icon>
                        Odobri produženje
                      </button>
                      <button
                        mat-stroked-button
                        color="warn"
                        (click)="declinePendingExtension()"
                        [disabled]="isUpdatingExtension()"
                      >
                        <mat-icon>close</mat-icon>
                        Odbij produženje
                      </button>
                    </div>
                  }
                </div>
              }

              @if (canRequestExtension()) {
                <div class="extension-panel">
                  <mat-form-field appearance="outline" class="extension-field">
                    <mat-label>Novi datum završetka</mat-label>
                    <input
                      matInput
                      type="date"
                      [min]="minimumExtensionDate()"
                      [ngModel]="requestedExtensionDate()"
                      (ngModelChange)="requestedExtensionDate.set($event)"
                    />
                  </mat-form-field>

                  <mat-form-field appearance="outline" class="extension-field">
                    <mat-label>Razlog produženja</mat-label>
                    <textarea
                      matInput
                      rows="3"
                      [ngModel]="extensionReason()"
                      (ngModelChange)="extensionReason.set($event)"
                      placeholder="Npr. treba mi vozilo još jedan dan"
                    ></textarea>
                  </mat-form-field>

                  <button
                    mat-raised-button
                    color="primary"
                    (click)="submitExtensionRequest()"
                    [disabled]="isSubmittingExtension()"
                  >
                    @if (isSubmittingExtension()) {
                      <mat-spinner diameter="20"></mat-spinner>
                    } @else {
                      <ng-container>
                        <mat-icon>event_repeat</mat-icon>
                        Zatraži produženje
                      </ng-container>
                    }
                  </button>
                </div>
              }

              @if (extensions().length > 0) {
                <div class="extension-history">
                  <div class="extension-history-title">Istorija zahteva</div>
                  @for (extension of extensions(); track extension.id) {
                    <div class="extension-history-item">
                      <div>
                        <div class="extension-history-date">
                          {{ extension.requestedEndDate | date: 'dd.MM.yyyy' }}
                        </div>
                        <div class="extension-history-subtitle">
                          {{ extension.additionalCost || 0 | currency: 'RSD' : 'symbol' : '1.0-0' }}
                        </div>
                      </div>
                      <mat-chip [class]="getExtensionStatusClass(extension.status)">
                        {{ extension.statusDisplay }}
                      </mat-chip>
                    </div>
                  }
                </div>
              }
            </mat-card-content>
          </mat-card>
        }

        <!-- Actions -->
        <div class="actions">
          @if (canCheckIn()) {
            <button
              mat-raised-button
              color="primary"
              [routerLink]="['/bookings', booking()?.id, 'check-in']"
              class="check-in-button"
            >
              <mat-icon>login</mat-icon>
              Započni Check-in
            </button>
          }
          @if (canReview()) {
            <button
              mat-stroked-button
              color="accent"
              [routerLink]="['/bookings', booking()?.id, 'review']"
            >
              <mat-icon>rate_review</mat-icon>
              Ostavi recenziju
            </button>
          }

          <button mat-stroked-button [routerLink]="['/messages']">
            <mat-icon>chat</mat-icon>
            Poruke
          </button>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .booking-detail-page {
        padding: 16px;
        max-width: 600px;
        margin: 0 auto;
      }

      .loading-container,
      .error-container {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        min-height: 300px;
        gap: 16px;
        text-align: center;
      }

      .error-container mat-icon {
        font-size: 64px;
        width: 64px;
        height: 64px;
        color: var(--warn-color, #f44336);
      }

      .page-header {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-bottom: 16px;
      }

      .page-header h1 {
        margin: 0;
        font-size: 20px;
      }

      .status-chips {
        margin-bottom: 16px;
      }

      .countdown-banner {
        display: flex;
        align-items: center;
        gap: 10px;
        padding: 12px 16px;
        border-radius: 12px;
        background: rgba(59, 130, 246, 0.12);
        color: #1d4ed8;
        font-weight: 600;
        font-size: 15px;
        margin-bottom: 16px;
      }

      .countdown-banner mat-icon {
        font-size: 20px;
        width: 20px;
        height: 20px;
      }

      .countdown-banner.checkout-prep {
        background: rgba(245, 124, 0, 0.14);
        color: #e65100;
        animation: pulse-detail 2s ease-in-out infinite;
      }

      @keyframes pulse-detail {
        0%,
        100% {
          opacity: 1;
        }
        50% {
          opacity: 0.78;
        }
      }

      .status-confirmed {
        background: var(--success-bg, #e8f5e9) !important;
        color: var(--success-color, #4caf50) !important;
      }

      .status-pending {
        background: var(--warn-bg, #fff3e0) !important;
        color: var(--warn-color, #ff9800) !important;
      }

      .status-cancellation_pending_settlement {
        background: #fff3e0 !important;
        color: #e65100 !important;
      }

      .status-active,
      .status-in_trip {
        background: var(--info-bg, #e3f2fd) !important;
        color: var(--info-color, var(--brand-primary)) !important;
      }

      .status-completed {
        background: #f5f5f5 !important;
        color: #757575 !important;
      }

      mat-card {
        margin-bottom: 16px;
      }

      .car-card .car-image {
        width: 100%;
        height: 180px;
        object-fit: cover;
      }

      .car-card h3 {
        margin: 12px 0 8px;
      }

      .car-owner {
        display: flex;
        align-items: center;
        gap: 4px;
        color: var(--text-secondary, #757575);
        font-size: 14px;
        margin: 0;
      }

      .car-owner mat-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
      }

      .dates-card mat-card-content {
        padding: 0 !important;
      }

      .date-row {
        display: flex;
        align-items: center;
        gap: 12px;
        padding: 16px;
      }

      .date-row mat-icon {
        color: var(--brand-primary);
      }

      .date-row .label {
        display: block;
        font-size: 12px;
        color: var(--text-secondary, #757575);
      }

      .date-row .value {
        font-weight: 500;
      }

      .pricing-card h4 {
        margin: 0 0 12px;
      }

      .price-row {
        display: flex;
        justify-content: space-between;
        align-items: center;
      }

      .price {
        font-size: 20px;
        font-weight: bold;
        color: var(--brand-primary);
      }

      .actions {
        display: flex;
        flex-direction: column;
        gap: 12px;
        margin-top: 24px;
      }

      .check-in-button {
        height: 48px;
      }

      .payment-status-card,
      .deposit-status-card {
        margin-bottom: 16px;
      }

      .payment-status-card h4,
      .deposit-status-card h4 {
        margin: 0 0 12px;
        font-size: 14px;
        font-weight: 500;
        color: rgba(0, 0, 0, 0.6);
      }

      .status-row {
        display: flex;
        align-items: center;
        gap: 10px;
        padding: 8px 12px;
        border-radius: 8px;
        font-size: 14px;
        font-weight: 500;
      }

      .status-row mat-icon {
        font-size: 20px;
        width: 20px;
        height: 20px;
      }

      .status-color--success {
        background: #e8f5e9;
        color: #2e7d32;
      }

      .status-color--info {
        background: #e3f2fd;
        color: #1565c0;
      }

      .status-color--warn {
        background: #fff3e0;
        color: #e65100;
      }

      .status-color--error {
        background: #ffebee;
        color: #c62828;
      }

      .status-color--neutral {
        background: #f5f5f5;
        color: #616161;
      }

      .status-action-hint {
        display: flex;
        align-items: center;
        gap: 6px;
        margin: 10px 0 0;
        font-size: 12px;
        color: #e65100;
      }

      .status-action-hint mat-icon {
        font-size: 16px;
        width: 16px;
        height: 16px;
      }

      .payment-action-card {
        display: flex;
        flex-direction: column;
        gap: 12px;
        margin-top: 12px;
      }

      .extension-card h4 {
        margin: 0 0 12px;
        font-size: 14px;
        font-weight: 500;
        color: rgba(0, 0, 0, 0.6);
      }

      .extension-panel {
        display: flex;
        flex-direction: column;
        gap: 12px;
        padding: 12px;
        border: 1px solid rgba(0, 0, 0, 0.08);
        border-radius: 12px;
        margin-bottom: 12px;
      }

      .extension-panel--pending {
        background: #fafafa;
      }

      .extension-summary-row,
      .extension-history-item {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 12px;
      }

      .extension-summary-label,
      .extension-history-title,
      .extension-meta-label {
        font-size: 12px;
        color: rgba(0, 0, 0, 0.6);
      }

      .extension-meta-grid {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 12px;
      }

      .extension-meta-grid div {
        display: flex;
        flex-direction: column;
        gap: 4px;
      }

      .extension-note,
      .extension-response {
        margin: 0;
        font-size: 13px;
        color: rgba(0, 0, 0, 0.75);
      }

      .extension-field {
        width: 100%;
      }

      .extension-actions-row {
        display: flex;
        flex-wrap: wrap;
        gap: 12px;
      }

      .extension-history {
        display: flex;
        flex-direction: column;
        gap: 8px;
      }

      .extension-history-date {
        font-weight: 600;
      }

      .extension-history-subtitle {
        font-size: 12px;
        color: rgba(0, 0, 0, 0.6);
      }

      .extension-status-pending,
      .extension-status-payment_pending {
        background: #fff3e0 !important;
        color: #e65100 !important;
      }

      .extension-status-approved {
        background: #e8f5e9 !important;
        color: #2e7d32 !important;
      }

      .extension-status-declined,
      .extension-status-cancelled,
      .extension-status-expired {
        background: #ffebee !important;
        color: #c62828 !important;
      }

      .agreement-card {
        margin-bottom: 16px;
      }

      .agreement-card h4 {
        margin: 0 0 12px;
        font-size: 14px;
        font-weight: 500;
        color: rgba(0, 0, 0, 0.6);
      }

      .agreement-terms {
        display: flex;
        flex-direction: column;
        gap: 6px;
        padding: 12px;
        background: #fafafa;
        border-radius: 8px;
        margin-bottom: 12px;
        font-size: 13px;
      }

      .terms-section {
        display: flex;
        justify-content: space-between;
        align-items: center;
      }

      .terms-label {
        font-weight: 500;
        color: rgba(0, 0, 0, 0.6);
      }

      .agreement-vehicle {
        display: flex;
        align-items: center;
        gap: 8px;
        font-size: 13px;
        margin-bottom: 12px;
      }

      .license-plate {
        padding: 2px 8px;
        background: #e3f2fd;
        border-radius: 4px;
        font-weight: 600;
        font-size: 12px;
      }

      .acceptance-confirm {
        margin: 8px 0;
      }

      .agreement-status {
        display: flex;
        flex-direction: column;
        gap: 8px;
        margin-bottom: 12px;
      }

      .agreement-party {
        display: flex;
        align-items: center;
        gap: 8px;
        font-size: 14px;
      }

      .agreement-party mat-icon {
        font-size: 20px;
        width: 20px;
        height: 20px;
        color: #bdbdbd;
      }

      .agreement-party mat-icon.accepted {
        color: #2e7d32;
      }

      .accept-agreement-btn {
        width: 100%;
        margin-top: 8px;
      }

      .agreement-accepted-banner {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 10px 14px;
        border-radius: 8px;
        background: #e8f5e9;
        color: #2e7d32;
        font-size: 14px;
        font-weight: 500;
        margin-top: 8px;
      }

      .agreement-accepted-banner mat-icon {
        font-size: 20px;
        width: 20px;
        height: 20px;
      }
    `,
  ],
})
export class BookingDetailComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private bookingService = inject(BookingService);
  private authService = inject(AuthService);
  private destroyRef = inject(DestroyRef);
  private snackBar = inject(MatSnackBar);

  @ViewChild(PaymentFormComponent) private reauthPaymentForm?: PaymentFormComponent;

  booking = signal<BookingDetails | null>(null);
  isLoading = signal(false);
  error = signal<string | null>(null);

  // Rental agreement state
  agreement = signal<RentalAgreementDTO | null>(null);
  isAcceptingAgreement = signal(false);
  agreementConfirmed = signal(false);
  extensions = signal<TripExtension[]>([]);
  pendingExtension = signal<TripExtension | null>(null);
  requestedExtensionDate = signal('');
  extensionReason = signal('');
  hostExtensionResponse = signal('');
  isSubmittingExtension = signal(false);
  isUpdatingExtension = signal(false);
  reauthCardValid = signal(false);
  isReauthorizingPayment = signal(false);

  /** Live ticker — bumps every 60s for countdown reactivity */
  private tick = signal(0);
  private tickIntervalId: ReturnType<typeof setInterval> | null = null;

  carTitle = computed(() => {
    const b = this.booking();
    if (!b) return 'Vozilo';
    return `${b.brand} ${b.model}${b.year ? ` (${b.year})` : ''}`;
  });

  /**
   * Computed: Pickup location data for display component.
   */
  pickupLocationData = computed<PickupLocationData | null>(() => {
    const b = this.booking();
    if (!b || !b.pickupLatitude || !b.pickupLongitude) return null;

    return {
      latitude: b.pickupLatitude,
      longitude: b.pickupLongitude,
      address: b.pickupAddress,
      city: b.pickupCity,
      zipCode: b.pickupZipCode,
      isEstimated: b.pickupLocationEstimated,
    };
  });

  /**
   * Computed: Whether booking has delivery info.
   */
  hasDeliveryInfo = computed(() => {
    const b = this.booking();
    return (
      b !== null &&
      b.deliveryDistanceKm !== null &&
      b.deliveryDistanceKm !== undefined &&
      b.deliveryDistanceKm > 0
    );
  });

  // ========== PAYMENT LIFECYCLE COMPUTEDS ==========

  /** Guest-friendly payment summary text */
  paymentSummary = computed((): string | null => {
    const b = this.booking();
    if (!b?.chargeLifecycleStatus) return null;
    return getGuestPaymentSummary(b.chargeLifecycleStatus);
  });

  /** Guest-friendly deposit summary text */
  depositSummary = computed((): string | null => {
    const b = this.booking();
    if (!b?.depositLifecycleStatus) return null;
    return getGuestDepositSummary(b.depositLifecycleStatus, b.securityDeposit ?? 30000);
  });

  /** Icon for charge status display */
  chargeStatusIcon = computed((): string => {
    const status = this.booking()?.chargeLifecycleStatus;
    return status ? getChargeStatusIcon(status) : 'help_outline';
  });

  /** Color class for charge status */
  chargeStatusColor = computed((): string => {
    const status = this.booking()?.chargeLifecycleStatus;
    return status ? getChargeStatusColor(status) : 'neutral';
  });

  /** Icon for deposit status display */
  depositStatusIcon = computed((): string => {
    const status = this.booking()?.depositLifecycleStatus;
    return status ? getDepositStatusIcon(status) : 'help_outline';
  });

  /** Color class for deposit status */
  depositStatusColor = computed((): string => {
    const status = this.booking()?.depositLifecycleStatus;
    return status ? getDepositStatusColor(status) : 'neutral';
  });

  /** Whether the payment status requires guest action */
  isPaymentActionRequired = computed((): boolean => {
    const status = this.booking()?.chargeLifecycleStatus;
    return status ? isChargeStatusActionRequired(status) : false;
  });

  currentUser = computed(() => this.authService.getCurrentUser());

  isAdminView = computed(() => this.currentUser()?.roles?.includes('ADMIN') ?? false);

  isHostView = computed(() => {
    const booking = this.booking();
    const currentUser = this.currentUser();
    return !!booking && !!currentUser && Number(currentUser.id) === booking.hostId;
  });

  isRenterView = computed(() => {
    const booking = this.booking();
    const currentUser = this.currentUser();
    if (!booking || !currentUser || this.isAdminView()) {
      return false;
    }
    return Number(currentUser.id) !== booking.hostId;
  });

  showPaymentReauth = computed(() => {
    return this.isRenterView() && this.booking()?.chargeLifecycleStatus === 'REAUTH_REQUIRED';
  });

  showExtensionSection = computed(() => {
    return !!this.booking() && !this.isAdminView() && (this.isHostView() || this.isRenterView());
  });

  canRequestExtension = computed(() => {
    return this.isRenterView() && this.booking()?.status === 'IN_TRIP' && !this.pendingExtension();
  });

  protected formatBookingDateTime(value: string | null | undefined): string {
    if (!value) {
      return 'Nije dostupno';
    }
    return formatDateTimeSerbiaValue(value);
  }

  minimumExtensionDate = computed(() => {
    const booking = this.booking();
    if (!booking) {
      return '';
    }

    return getSerbiaDateInputValue(booking.endTime, 1);
  });

  ngOnInit(): void {
    this.loadBooking();
    this.handlePaymentReturnState();

    // Live countdown ticker
    this.tickIntervalId = setInterval(() => this.tick.update((t) => t + 1), 60_000);
    this.destroyRef.onDestroy(() => {
      if (this.tickIntervalId) clearInterval(this.tickIntervalId);
    });
  }

  loadBooking(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.error.set('ID rezervacije nije pronađen');
      return;
    }

    this.isLoading.set(true);
    this.error.set(null);

    // Use getBookingDetails for full pickup location data
    this.bookingService.getBookingDetails(parseInt(id, 10)).subscribe({
      next: (booking) => {
        this.booking.set(booking);
        this.isLoading.set(false);
        this.loadAgreement(booking.id);
        this.loadExtensions(booking.id);
      },
      error: (err) => {
        this.error.set(err.error?.message || 'Nije moguće učitati rezervaciju');
        this.isLoading.set(false);
      },
    });
  }

  loadAgreement(bookingId: number): void {
    this.bookingService.getAgreement(bookingId).subscribe({
      next: (agreement) => this.agreement.set(agreement),
      error: () => {
        // Agreement may not exist yet for legacy bookings — ignore
        this.agreement.set(null);
      },
    });
  }

  loadExtensions(bookingId: number): void {
    this.bookingService.getBookingExtensions(bookingId).subscribe({
      next: (extensions) => {
        this.extensions.set(extensions);
        this.pendingExtension.set(
          extensions.find((extension) =>
            ['PENDING', 'PAYMENT_PENDING'].includes(extension.status),
          ) ?? null,
        );
      },
      error: () => {
        this.extensions.set([]);
        this.pendingExtension.set(null);
      },
    });
  }

  onReauthCardValidityChanged(valid: boolean): void {
    this.reauthCardValid.set(valid);
  }

  async submitReauthorization(): Promise<void> {
    const bookingId = this.booking()?.id;
    if (!bookingId || !this.reauthPaymentForm) {
      return;
    }

    this.isReauthorizingPayment.set(true);

    try {
      const tokenResult = await this.reauthPaymentForm.requestToken();
      this.bookingService
        .reauthorizeBookingPayment(bookingId, { paymentMethodId: tokenResult.token })
        .subscribe({
          next: (response: BookingReauthorizeResponse) => {
            this.isReauthorizingPayment.set(false);

            if (response.success) {
              this.snackBar.open('Kartica je uspešno ponovo autorizovana.', 'Zatvori', {
                duration: 4000,
              });
              this.loadBooking();
              return;
            }

            if (response.redirectUrl) {
              window.location.assign(response.redirectUrl);
              return;
            }

            this.snackBar.open(response.errorMessage || 'Ponovna autorizacija nije uspela.', 'Zatvori', {
              duration: 5000,
            });
          },
          error: (error) => {
            this.isReauthorizingPayment.set(false);
            this.snackBar.open(
              error.error?.errorMessage || error.error?.message || 'Ponovna autorizacija nije uspela.',
              'Zatvori',
              { duration: 5000 },
            );
          },
        });
    } catch (error: unknown) {
      this.isReauthorizingPayment.set(false);
      const message = error instanceof Error ? error.message : 'Obrada kartice nije uspela.';
      this.snackBar.open(message, 'Zatvori', { duration: 5000 });
    }
  }

  submitExtensionRequest(): void {
    const bookingId = this.booking()?.id;
    const requestedEndDate = this.requestedExtensionDate();

    if (!bookingId || !requestedEndDate) {
      this.snackBar.open('Izaberite novi datum završetka.', 'Zatvori', { duration: 4000 });
      return;
    }

    if (requestedEndDate < this.minimumExtensionDate()) {
      this.snackBar.open('Novi datum mora biti posle trenutnog završetka putovanja.', 'Zatvori', {
        duration: 5000,
      });
      return;
    }

    this.isSubmittingExtension.set(true);
    this.bookingService
      .requestBookingExtension(bookingId, {
        newEndDate: requestedEndDate,
        reason: this.extensionReason() || undefined,
      })
      .subscribe({
        next: () => {
          this.isSubmittingExtension.set(false);
          this.requestedExtensionDate.set('');
          this.extensionReason.set('');
          this.snackBar.open('Zahtev za produženje je poslat domaćinu.', 'Zatvori', {
            duration: 4000,
          });
          this.loadExtensions(bookingId);
        },
        error: (error) => {
          this.isSubmittingExtension.set(false);
          this.snackBar.open(
            error.error?.message || 'Slanje zahteva za produženje nije uspelo.',
            'Zatvori',
            { duration: 5000 },
          );
        },
      });
  }

  canCancelPendingExtension(): boolean {
    return this.isRenterView() && !!this.pendingExtension();
  }

  canContinueExtensionPayment(): boolean {
    const extension = this.pendingExtension();
    return (
      this.isRenterView() &&
      !!extension &&
      extension.status === 'PAYMENT_PENDING' &&
      !!extension.paymentRedirectUrl
    );
  }

  canRespondToPendingExtension(): boolean {
    return this.isHostView() && this.pendingExtension()?.status === 'PENDING';
  }

  cancelPendingExtension(): void {
    const bookingId = this.booking()?.id;
    const extension = this.pendingExtension();
    if (!bookingId || !extension) {
      return;
    }

    this.isUpdatingExtension.set(true);
    this.bookingService.cancelBookingExtension(bookingId, extension.id).subscribe({
      next: () => {
        this.isUpdatingExtension.set(false);
        this.snackBar.open('Zahtev za produženje je otkazan.', 'Zatvori', { duration: 4000 });
        this.loadExtensions(bookingId);
      },
      error: (error) => {
        this.isUpdatingExtension.set(false);
        this.snackBar.open(error.error?.message || 'Otkazivanje zahteva nije uspelo.', 'Zatvori', {
          duration: 5000,
        });
      },
    });
  }

  approvePendingExtension(): void {
    this.respondToPendingExtension('approve');
  }

  declinePendingExtension(): void {
    this.respondToPendingExtension('decline');
  }

  continueExtensionPayment(): void {
    const redirectUrl = this.pendingExtension()?.paymentRedirectUrl;
    if (redirectUrl) {
      window.location.assign(redirectUrl);
    }
  }

  getExtensionStatusClass(status: string): string {
    return `extension-status-${status.toLowerCase()}`;
  }

  private respondToPendingExtension(action: 'approve' | 'decline'): void {
    const bookingId = this.booking()?.id;
    const extension = this.pendingExtension();
    if (!bookingId || !extension) {
      return;
    }

    this.isUpdatingExtension.set(true);
    const request =
      action === 'approve'
        ? this.bookingService.approveBookingExtension(bookingId, extension.id, {
            response: this.hostExtensionResponse() || undefined,
          })
        : this.bookingService.declineBookingExtension(bookingId, extension.id, {
            response: this.hostExtensionResponse() || undefined,
          });

    request.subscribe({
      next: (updatedExtension) => {
        this.isUpdatingExtension.set(false);
        this.hostExtensionResponse.set('');

        const message =
          action === 'approve'
            ? updatedExtension.status === 'PAYMENT_PENDING'
              ? 'Produženje je odobreno. Gost mora završiti dodatno plaćanje.'
              : 'Produženje je odobreno.'
            : 'Produženje je odbijeno.';

        this.snackBar.open(message, 'Zatvori', { duration: 4000 });
        this.loadExtensions(bookingId);
        this.loadBooking();
      },
      error: (error) => {
        this.isUpdatingExtension.set(false);
        this.snackBar.open(error.error?.message || 'Ažuriranje produženja nije uspelo.', 'Zatvori', {
          duration: 5000,
        });
      },
    });
  }

  private handlePaymentReturnState(): void {
    const paymentState = this.route.snapshot.queryParamMap.get('payment');
    if (!paymentState) {
      return;
    }

    if (paymentState === 'confirmed') {
      this.snackBar.open('Plaćanje je potvrđeno. Osvežavamo rezervaciju.', 'Zatvori', {
        duration: 4000,
      });
      return;
    }

    if (paymentState === 'failed') {
      this.snackBar.open('Plaćanje nije potvrđeno. Možete pokušati ponovo iz detalja rezervacije.', 'Zatvori', {
        duration: 5000,
      });
    }
  }

  canAcceptAgreement(): boolean {
    const a = this.agreement();
    if (!a || a.status === 'FULLY_ACCEPTED') return false;

    const currentUser = this.authService.getCurrentUser();
    if (!currentUser) return false;

    const currentUserId = Number(currentUser.id);

    // Only show accept if the current user hasn't already accepted
    if (a.ownerUserId === currentUserId) return !a.ownerAccepted;
    if (a.renterUserId === currentUserId) return !a.renterAccepted;

    return false;
  }

  acceptAgreement(): void {
    const bookingId = this.booking()?.id;
    if (!bookingId) return;

    this.isAcceptingAgreement.set(true);
    this.bookingService.acceptAgreement(bookingId).subscribe({
      next: (updated) => {
        this.agreement.set(updated);
        this.isAcceptingAgreement.set(false);
      },
      error: (err) => {
        this.isAcceptingAgreement.set(false);
        this.error.set(err.error?.message || 'Prihvatanje ugovora nije uspelo. Pokušajte ponovo.');
      },
    });
  }

  goBack(): void {
    this.router.navigate(['/bookings']);
  }

  getStatusLabel(status: string | undefined): string {
    const labels: Record<string, string> = {
      PENDING: 'Na čekanju',
      PENDING_APPROVAL: 'Na čekanju',
      CONFIRMED: 'Potvrđeno',
      ACTIVE: 'Aktivno',
      IN_TRIP: 'U toku',
      COMPLETED: 'Završeno',
      CANCELLED: 'Otkazano',
      CANCELLATION_PENDING_SETTLEMENT: 'Otkazano, poravnanje u toku',
      DECLINED: 'Odbijeno',
      CHECK_IN_OPEN: 'Čeka check-in',
      CHECK_IN_HOST_COMPLETE: 'Čeka gosta',
      CHECK_IN_COMPLETE: 'Spremno za preuzimanje',
      CHECKOUT_OPEN: 'Čeka povratak',
      CHECKOUT_GUEST_COMPLETE: 'Čeka domaćina',
      CHECKOUT_HOST_COMPLETE: 'Završava se',
      HOST_SUBMITTED: 'Čeka gosta',
      GUEST_ACKNOWLEDGED: 'Potvrđeno',
      NO_SHOW_HOST: 'Domaćin nije došao',
      NO_SHOW_GUEST: 'Gost nije došao',
      EXPIRED: 'Isteklo',
      EXPIRED_SYSTEM: 'Isteklo',
    };
    return labels[status || ''] || status || 'Nepoznato';
  }

  canCheckIn(): boolean {
    const status = this.booking()?.status;
    const bookingStatusOk = status
      ? ['CONFIRMED', 'CHECK_IN_OPEN', 'HOST_SUBMITTED', 'GUEST_ACKNOWLEDGED'].includes(status)
      : false;
    if (!bookingStatusOk) return false;

    // Gate on agreement acceptance — both parties must accept before check-in
    const agreement = this.agreement();
    if (agreement && agreement.status !== 'FULLY_ACCEPTED') {
      return false;
    }
    return true;
  }

  canReview(): boolean {
    const booking = this.booking();
    return booking?.status === 'COMPLETED' && !booking?.reviewSubmitted;
  }

  // ========== LIVE COUNTDOWN COMPUTEDS ==========

  /** Live countdown text for upcoming/ongoing bookings */
  countdownText = computed((): string | null => {
    void this.tick(); // reactive dependency
    const b = this.booking();
    if (!b) return null;
    const now = new Date();
    const start = parseSerbiaDateTime(b.startTime);
    const end = parseSerbiaDateTime(b.endTime);
    const status = b.status;

    // Upcoming (before trip starts)
    if (
      [
        'PENDING_APPROVAL',
        'ACTIVE',
        'APPROVED',
        'CHECK_IN_OPEN',
        'CHECK_IN_HOST_COMPLETE',
        'CHECK_IN_COMPLETE',
      ].includes(status) &&
      start.getTime() > now.getTime()
    ) {
      return `Počinje za ${this.formatDuration(start.getTime() - now.getTime())}`;
    }

    // In-trip / ongoing
    if (['IN_TRIP', 'CHECKOUT_OPEN'].includes(status)) {
      const msLeft = end.getTime() - now.getTime();
      if (msLeft <= 0) return 'Vraćanje vozila';
      const minutesLeft = Math.floor(msLeft / 60_000);
      if (minutesLeft <= 60) {
        return `Priprema za vraćanje — ${this.formatDuration(msLeft)}`;
      }
      return `Završava se za ${this.formatDuration(msLeft)}`;
    }

    return null;
  });

  /** Icon for countdown banner */
  countdownIcon = computed((): string => {
    void this.tick();
    const b = this.booking();
    if (!b) return 'schedule';
    if (['IN_TRIP', 'CHECKOUT_OPEN'].includes(b.status)) {
      const msLeft = parseSerbiaDateTime(b.endTime).getTime() - Date.now();
      if (msLeft > 0 && msLeft <= 3_600_000) return 'alarm';
      return 'timelapse';
    }
    return 'schedule';
  });

  /** Whether checkout prep phase is active (≤ 1h to end) */
  isCheckoutPrep = computed((): boolean => {
    void this.tick();
    const b = this.booking();
    if (!b || !['IN_TRIP', 'CHECKOUT_OPEN'].includes(b.status)) return false;
    const msLeft = parseSerbiaDateTime(b.endTime).getTime() - Date.now();
    return msLeft > 0 && msLeft <= 3_600_000;
  });

  private formatDuration(ms: number): string {
    const totalMinutes = Math.max(0, Math.floor(ms / 60_000));
    const days = Math.floor(totalMinutes / 1440);
    const hours = Math.floor((totalMinutes % 1440) / 60);
    const minutes = totalMinutes % 60;

    if (days > 0) return `${days} ${days === 1 ? 'dan' : 'dana'}`;
    if (hours > 0) {
      const minPart = minutes > 0 ? ` i ${minutes} min` : '';
      return `${hours} ${hours === 1 ? 'sat' : 'sati'}${minPart}`;
    }
    return `${minutes} min`;
  }
}

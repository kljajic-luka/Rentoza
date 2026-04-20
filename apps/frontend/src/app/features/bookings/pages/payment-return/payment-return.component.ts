import { Component, OnInit, OnDestroy, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { Subscription, timer, EMPTY } from 'rxjs';
import { switchMap, expand, take } from 'rxjs/operators';

import { BookingService } from '@core/services/booking.service';
import { AuthService } from '@core/auth/auth.service';
import { isTerminalStatus } from '@core/utils/booking.utils';

type ReturnState = 'pending' | 'success' | 'failed' | 'unauthenticated';

/** Maximum number of polling attempts before giving up and sending user to booking detail. */
const MAX_POLL_ATTEMPTS = 10;
/** Backoff delays in milliseconds for each polling attempt. Fibonacci-style ~45s total. */
const POLL_DELAYS_MS = [2000, 3000, 3000, 5000, 5000, 8000, 8000, 8000, 8000, 8000];
/** After this attempt index, show "taking longer" message. */
const SLOW_THRESHOLD = 3;

/**
 * Payment return / callback landing page.
 *
 * Handles the redirect-back from 3DS/SCA payment provider after the user
 * completes (or cancels) bank authentication.
 *
 * Expected query params (tolerant — accepts common provider variants):
 *   bookingId | booking_id | order_id  → internal booking ID
 *   status | payment_status            → success | paid | failed | cancelled
 *
 * Mounted at: /bookings/payment-return
 * Alias at:   /booking/payment-return  (added in app.routes.ts for resilience)
 */
@Component({
  selector: 'app-payment-return',
  standalone: true,
  imports: [CommonModule, RouterModule, MatProgressSpinnerModule, MatButtonModule, MatIconModule],
  template: `
    <div class="return-page">
      <!-- PENDING / POLLING -->
      <div *ngIf="state() === 'pending'" class="state-block">
        <mat-spinner diameter="56"></mat-spinner>
        <h2>Proveravamo status plaćanja...</h2>
        <p>Molimo sačekajte dok proveravamo potvrdu vaše banke.</p>
        <p *ngIf="pollingSlow()" class="slow-hint">
          Obrada traje duže nego obično. Vaš zahtev se i dalje obrađuje.
          <span *ngIf="bookingId()">Broj rezervacije: <strong>#{{ bookingId() }}</strong></span>
        </p>
      </div>

      <!-- SUCCESS -->
      <div *ngIf="state() === 'success'" class="state-block">
        <mat-icon class="icon-success">check_circle</mat-icon>
        <h2>Plaćanje potvrđeno</h2>
        <p>Vaša rezervacija je uspešno kreirana. Preusmeravamo vas...</p>
        <a mat-stroked-button [routerLink]="['/bookings', bookingId()]" *ngIf="bookingId()">
          Pogledaj rezervaciju
        </a>
      </div>

      <!-- FAILED -->
      <div *ngIf="state() === 'failed'" class="state-block">
        <mat-icon class="icon-failed">error_outline</mat-icon>
        <h2>Plaćanje nije uspelo</h2>
        <p>
          Vaša banka nije odobrila plaćanje ili je verifikacija otkazana. Vaša rezervacija
          <strong>nije</strong> potvrđena.
        </p>
        <p *ngIf="bookingId()">
          Broj rezervacije: <strong>#{{ bookingId() }}</strong> — pokušajte ponovo ili kontaktirajte
          podršku.
        </p>
        <div class="action-row">
          <a mat-raised-button color="primary" routerLink="/vozila"> Pretraži vozila </a>
          <a mat-stroked-button [routerLink]="['/bookings', bookingId()]" *ngIf="bookingId()">
            Detalji rezervacije
          </a>
        </div>
      </div>

      <!-- UNAUTHENTICATED -->
      <div *ngIf="state() === 'unauthenticated'" class="state-block">
        <mat-icon class="icon-warn">lock_outline</mat-icon>
        <h2>Prijavite se</h2>
        <p>Morate biti prijavljeni da biste videli status plaćanja.</p>
        <a
          mat-raised-button
          color="primary"
          [routerLink]="['/auth/login']"
          [queryParams]="{ returnUrl: currentUrl }"
        >
          Prijava
        </a>
      </div>
    </div>
  `,
  styles: [
    `
      .return-page {
        display: flex;
        justify-content: center;
        align-items: center;
        min-height: 60vh;
        padding: 32px 16px;
      }
      .state-block {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 16px;
        max-width: 480px;
        text-align: center;
      }
      .state-block h2 {
        margin: 0;
        font-size: 24px;
        font-weight: 600;
      }
      .state-block p {
        margin: 0;
        color: rgba(0, 0, 0, 0.6);
        line-height: 1.5;
      }
      .icon-success {
        font-size: 64px;
        width: 64px;
        height: 64px;
        color: #2e7d32;
      }
      .icon-failed {
        font-size: 64px;
        width: 64px;
        height: 64px;
        color: #c62828;
      }
      .icon-warn {
        font-size: 64px;
        width: 64px;
        height: 64px;
        color: #e65100;
      }
      .action-row {
        display: flex;
        gap: 12px;
        flex-wrap: wrap;
        justify-content: center;
      }
      .slow-hint {
        color: #e65100;
        font-size: 14px;
        background: #fff3e0;
        padding: 8px 16px;
        border-radius: 8px;
      }
    `,
  ],
})
export class PaymentReturnComponent implements OnInit, OnDestroy {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private bookingService = inject(BookingService);
  private authService = inject(AuthService);

  state = signal<ReturnState>('pending');
  bookingId = signal<string | null>(null);
  pollingSlow = signal(false);
  currentUrl = this.router.url;

  private pollSub: Subscription | null = null;

  ngOnInit(): void {
    if (!this.authService.isAuthenticated()) {
      this.state.set('unauthenticated');
      return;
    }

    const params = this.route.snapshot.queryParamMap;

    // Tolerant param extraction: accept common provider variants
    const rawId = params.get('bookingId') ?? params.get('booking_id') ?? params.get('order_id');

    const rawStatus = params.get('status') ?? params.get('payment_status') ?? '';

    if (rawId) {
      this.bookingId.set(rawId);
    }

    const isFailure = /^(fail|failed|cancel|cancelled|rejected)$/i.test(rawStatus);

    if (isFailure) {
      this.state.set('failed');
      return;
    }

    // Either explicit success or unknown — poll the booking to confirm state
    if (rawId) {
      this.startPolling(rawId);
    } else {
      // No booking ID at all; cannot determine result — show failure
      this.state.set('failed');
    }
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  private startPolling(id: string): void {
    let attempt = 0;

    this.pollSub = timer(POLL_DELAYS_MS[0])
      .pipe(
        switchMap(() => this.bookingService.getBookingById(id)),
        expand((booking) => {
          attempt++;
          if (attempt >= SLOW_THRESHOLD) {
            this.pollingSlow.set(true);
          }
          if (isTerminalStatus(booking.status) || booking.status === 'ACTIVE' || booking.status === 'PENDING_APPROVAL') {
            const isActive = booking.status === 'ACTIVE' || booking.status === 'PENDING_APPROVAL';
            this.state.set(isActive ? 'success' : 'failed');
            if (isActive) {
              setTimeout(() => {
                void this.router.navigate(['/bookings', id]);
              }, 1500);
            }
            return EMPTY;
          }
          if (attempt >= MAX_POLL_ATTEMPTS) {
            void this.router.navigate(['/bookings', id]);
            return EMPTY;
          }
          const delay = POLL_DELAYS_MS[Math.min(attempt, POLL_DELAYS_MS.length - 1)];
          return timer(delay).pipe(
            switchMap(() => this.bookingService.getBookingById(id)),
          );
        }),
      )
      .subscribe({
        error: () => {
          this.state.set('failed');
        },
      });
  }
}

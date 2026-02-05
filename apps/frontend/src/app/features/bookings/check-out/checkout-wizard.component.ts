/**
 * Checkout Wizard Component
 *
 * Main orchestrator for the checkout flow. Routes to appropriate sub-components
 * based on render decision signal (role-aware state machine).
 */
import {
  Component,
  inject,
  OnInit,
  OnDestroy,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';

import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCardModule } from '@angular/material/card';

import { CheckoutService } from '@core/services/checkout.service';
import { GeolocationService } from '@core/services/geolocation.service';
import { GuestCheckoutComponent } from './guest-checkout.component';
import { HostCheckoutComponent } from './host-checkout.component';
import { CheckoutWaitingComponent } from './checkout-waiting.component';
import { CheckoutCompleteComponent } from './checkout-complete.component';

@Component({
  selector: 'app-checkout-wizard',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatCardModule,
    GuestCheckoutComponent,
    HostCheckoutComponent,
    CheckoutWaitingComponent,
    CheckoutCompleteComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="checkout-wizard">
      <!-- Header -->
      <div class="wizard-header">
        <button mat-icon-button (click)="goBack()" class="back-button">
          <mat-icon>arrow_back</mat-icon>
        </button>
        <h1>Checkout - Vraćanje vozila</h1>
      </div>

      <!-- Loading State -->
      @if (checkoutService.renderDecision() === 'LOADING') {
        <div class="loading-container">
          <mat-spinner diameter="48"></mat-spinner>
          <p>Učitavanje statusa checkout-a...</p>
        </div>
      }

      <!-- Error State -->
      @if (checkoutService.error()) {
        <mat-card class="error-card">
          <mat-card-content>
            <mat-icon color="warn">error</mat-icon>
            <p>{{ checkoutService.error() }}</p>
            <button mat-button color="primary" (click)="loadStatus()">
              <mat-icon>refresh</mat-icon>
              Pokušaj ponovo
            </button>
          </mat-card-content>
        </mat-card>
      }

      <!-- Main Content - Role-Based Routing -->
      @switch (checkoutService.renderDecision()) {
        @case ('IN_TRIP') {
          <div class="info-card">
            <mat-icon>info</mat-icon>
            <h3>Put je još u toku</h3>
            <p>Checkout će biti dostupan na dan povratka vozila.</p>
            <button mat-raised-button color="primary" (click)="initiateEarlyReturn()">
              <mat-icon>schedule</mat-icon>
              Rani povratak vozila
            </button>
          </div>
        }

        @case ('GUEST_EDIT') {
          <app-guest-checkout
            [bookingId]="bookingId"
            [status]="checkoutService.currentStatus()"
            (completed)="onGuestCompleted()">
          </app-guest-checkout>
        }

        @case ('GUEST_WAITING') {
          <app-checkout-waiting
            [status]="checkoutService.currentStatus()"
            title="Čekanje na domaćina"
            message="Vaš checkout je uspešno prosleđen. Čekamo da domaćin potvrdi stanje vozila."
            icon="hourglass_empty"
            [nextSteps]="[
              'Domaćin će pregledati fotografije i podatke',
              'Proverićemo da li ima novih oštećenja',
              'Dobićete obaveštenje kada checkout bude završen'
            ]"
            (refresh)="loadStatus()">
          </app-checkout-waiting>
        }

        @case ('HOST_WAITING') {
          <app-checkout-waiting
            [status]="checkoutService.currentStatus()"
            title="Čekanje na gosta"
            message="Checkout je otvoren. Čekamo da gost preda vozilo i uploaduje fotografije."
            icon="directions_car"
            [nextSteps]="[
              'Gost treba da uploaduje fotografije povratka vozila',
              'Gost treba da unese završnu kilometražu i nivo goriva',
              'Dobićete obaveštenje kada gost završi'
            ]"
            (refresh)="loadStatus()">
          </app-checkout-waiting>
        }

        @case ('HOST_CONFIRM') {
          <app-host-checkout
            [bookingId]="bookingId"
            [status]="checkoutService.currentStatus()"
            (confirmed)="onHostConfirmed()">
          </app-host-checkout>
        }

        @case ('COMPLETE') {
          <app-checkout-complete
            [bookingId]="bookingId"
            [status]="checkoutService.currentStatus()">
          </app-checkout-complete>
        }

        @case ('NOT_READY') {
          <div class="info-card warning">
            <mat-icon color="warn">warning</mat-icon>
            <h3>Checkout nije dostupan</h3>
            <p>Status rezervacije ne dozvoljava checkout u ovom trenutku.</p>
            <button mat-button (click)="goBack()">
              <mat-icon>arrow_back</mat-icon>
              Nazad na rezervacije
            </button>
          </div>
        }
      }
    </div>
  `,
  styles: [`
    .checkout-wizard {
      max-width: 800px;
      margin: 0 auto;
      padding: 16px;
    }

    .wizard-header {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 24px;

      h1 {
        margin: 0;
        font-size: 1.5rem;
        font-weight: 500;
      }
    }

    .back-button {
      margin-left: -8px;
    }

    .loading-container {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 48px;
      gap: 16px;

      p {
        color: var(--text-secondary);
      }
    }

    .error-card {
      mat-card-content {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 16px;
        padding: 24px;
        text-align: center;

        mat-icon {
          font-size: 48px;
          width: 48px;
          height: 48px;
        }

        p {
          margin: 0;
          color: var(--warn-color);
        }
      }
    }

    .info-card {
      display: flex;
      flex-direction: column;
      align-items: center;
      text-align: center;
      padding: 48px 24px;
      background: var(--surface-color, #f5f5f5);
      border-radius: 12px;
      gap: 16px;

      mat-icon {
        font-size: 64px;
        width: 64px;
        height: 64px;
        color: var(--primary-color);
      }

      h3 {
        margin: 0;
        font-size: 1.25rem;
      }

      p {
        margin: 0;
        color: var(--text-secondary);
        max-width: 400px;
      }

      &.warning mat-icon {
        color: var(--warn-color);
      }
    }
  `],
})
export class CheckoutWizardComponent implements OnInit, OnDestroy {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  protected checkoutService = inject(CheckoutService);
  private geolocationService = inject(GeolocationService);

  bookingId!: number;

  ngOnInit(): void {
    // Extract booking ID from route
    this.bookingId = Number(this.route.snapshot.paramMap.get('id'));

    if (isNaN(this.bookingId)) {
      this.router.navigate(['/bookings']);
      return;
    }

    // Start location watching
    this.geolocationService.startWatching();

    // Load initial status
    this.loadStatus();

    // Start polling
    this.checkoutService.startPolling(this.bookingId, 15000);
  }

  ngOnDestroy(): void {
    this.checkoutService.stopPolling();
    this.geolocationService.stopWatching();
    this.checkoutService.reset();
  }

  loadStatus(): void {
    this.checkoutService.loadStatus(this.bookingId).subscribe();
  }

  initiateEarlyReturn(): void {
    this.checkoutService.initiateCheckout(this.bookingId, true).subscribe({
      next: () => {
        // Status will be updated via the service
      },
      error: (err) => {
        console.error('Failed to initiate early return:', err);
      },
    });
  }

  onGuestCompleted(): void {
    // Guest completed their part, refresh status
    this.loadStatus();
  }

  onHostConfirmed(): void {
    // Host confirmed, refresh status
    this.loadStatus();
  }

  goBack(): void {
    this.router.navigate(['/bookings', this.bookingId]);
  }
}


import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-booking-confirmation',
  standalone: true,
  imports: [CommonModule, RouterModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="confirmation-page" role="main">
      <!-- Background glow -->
      <div class="confirmation-glow" aria-hidden="true"></div>

      <div class="confirmation-card" [class.visible]="visible()">

        <!-- Animated checkmark -->
        <div class="check-wrapper" aria-hidden="true">
          <svg class="check-svg" viewBox="0 0 80 80" fill="none" xmlns="http://www.w3.org/2000/svg">
            <circle
              class="check-circle"
              cx="40" cy="40" r="36"
              stroke="currentColor"
              stroke-width="5"
              stroke-linecap="round"
            />
            <path
              class="check-mark"
              d="M24 40 L35 52 L56 28"
              stroke="currentColor"
              stroke-width="5"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
        </div>

        <h1 class="confirmation-title">Rezervacija je potvrđena!</h1>
        <p class="confirmation-subtitle">
          Čestitamo — tvoja rezervacija je uspešno kreirana.<br />
          Proveravamo automobil i objavićemo ti potvrdu domaćina.
        </p>

        @if (bookingId()) {
          <div class="booking-id-pill">
            Rezervacija #{{ bookingId() }}
          </div>
        }

        <!-- What's next section -->
        <div class="next-steps">
          <h2 class="next-steps__title">Šta dalje?</h2>
          <div class="next-steps__grid">
            <div class="step">
              <div class="step__icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none">
                  <path d="M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/>
                  <line x1="8" y1="10" x2="16" y2="10" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
                  <line x1="8" y1="14" x2="13" y2="14" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
                </svg>
              </div>
              <p class="step__text">Pošalji poruku domaćinu i dogovori detalje prelaza</p>
            </div>
            <div class="step">
              <div class="step__icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none">
                  <rect x="3" y="4" width="18" height="18" rx="3" stroke="currentColor" stroke-width="1.8"/>
                  <line x1="3" y1="10" x2="21" y2="10" stroke="currentColor" stroke-width="1.8"/>
                  <line x1="8" y1="2" x2="8" y2="6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
                  <line x1="16" y1="2" x2="16" y2="6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
                </svg>
              </div>
              <p class="step__text">Dodaj rezervaciju u kalendar da ne propustiš termin</p>
            </div>
            <div class="step">
              <div class="step__icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none">
                  <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="1.8"/>
                  <path d="M12 6v6l4 2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
              </div>
              <p class="step__text">Domaćin ima 24h da potvrdi ili odbije zahtev</p>
            </div>
          </div>
        </div>

        <!-- CTAs -->
        <div class="confirmation-actions">
          @if (bookingId()) {
            <a
              class="btn btn--primary"
              [routerLink]="['/bookings', bookingId()]"
            >
              Pogledaj rezervaciju
            </a>
          }
          <a class="btn btn--ghost" routerLink="/messages">
            Pošalji poruku domaćinu
          </a>
          <a class="btn btn--link" routerLink="/cars">
            Pretraži još automobila
          </a>
        </div>

      </div>
    </div>
  `,
  styles: [
    `
      :host {
        display: block;
      }

      .confirmation-page {
        min-height: 100vh;
        display: flex;
        align-items: center;
        justify-content: center;
        padding: 2rem 1rem;
        position: relative;
        overflow: hidden;
        background: var(--color-surface-primary, #ffffff);
      }

      .confirmation-glow {
        position: absolute;
        top: 20%;
        left: 50%;
        transform: translateX(-50%);
        width: 600px;
        height: 400px;
        background: radial-gradient(ellipse at center, rgba(34, 197, 94, 0.1) 0%, transparent 70%);
        pointer-events: none;
      }

      .confirmation-card {
        position: relative;
        z-index: 1;
        max-width: 600px;
        width: 100%;
        text-align: center;
        opacity: 0;
        transform: translateY(32px);
        transition: opacity 0.5s ease, transform 0.5s ease;

        &.visible {
          opacity: 1;
          transform: translateY(0);
        }
      }

      /* Animated checkmark */
      .check-wrapper {
        width: 96px;
        height: 96px;
        margin: 0 auto 2rem;
        color: #16a34a;
      }

      .check-svg {
        width: 100%;
        height: 100%;
        overflow: visible;
      }

      .check-circle {
        stroke-dasharray: 226;
        stroke-dashoffset: 226;
        animation: drawCircle 0.8s cubic-bezier(.4, 0, .2, 1) 0.2s forwards;
      }

      .check-mark {
        stroke-dasharray: 60;
        stroke-dashoffset: 60;
        animation: drawCheck 0.4s cubic-bezier(.4, 0, .2, 1) 0.9s forwards;
      }

      @keyframes drawCircle {
        to { stroke-dashoffset: 0; }
      }

      @keyframes drawCheck {
        to { stroke-dashoffset: 0; }
      }

      .confirmation-title {
        font-size: clamp(1.625rem, 4vw, 2.25rem);
        font-weight: 800;
        color: var(--color-text-primary, #0f172a);
        margin: 0 0 0.75rem;
      }

      .confirmation-subtitle {
        font-size: 1rem;
        color: var(--color-text-secondary, #64748b);
        line-height: 1.6;
        margin: 0 0 1.5rem;
      }

      .booking-id-pill {
        display: inline-block;
        padding: 0.375rem 1rem;
        background: var(--color-surface-secondary, #f1f5f9);
        border-radius: 100px;
        font-size: 0.8125rem;
        font-weight: 600;
        color: var(--color-text-secondary, #475569);
        letter-spacing: 0.02em;
        margin-bottom: 2rem;
      }

      /* Next steps */
      .next-steps {
        background: var(--color-surface-secondary, #f8fafc);
        border-radius: 16px;
        padding: 1.5rem;
        margin-bottom: 2rem;
        text-align: left;
        border: 1px solid var(--color-border, #e2e8f0);
      }

      .next-steps__title {
        font-size: 0.875rem;
        font-weight: 700;
        color: var(--color-text-secondary, #64748b);
        text-transform: uppercase;
        letter-spacing: 0.08em;
        margin: 0 0 1rem;
      }

      .next-steps__grid {
        display: flex;
        flex-direction: column;
        gap: 0.875rem;
      }

      .step {
        display: flex;
        align-items: flex-start;
        gap: 0.875rem;
      }

      .step__icon {
        width: 36px;
        height: 36px;
        border-radius: 10px;
        background: white;
        border: 1px solid var(--color-border, #e2e8f0);
        display: flex;
        align-items: center;
        justify-content: center;
        flex-shrink: 0;
        color: var(--brand-primary);
        padding: 6px;

        svg {
          width: 100%;
          height: 100%;
        }
      }

      .step__text {
        font-size: 0.9375rem;
        color: var(--color-text-primary, #334155);
        margin: 0;
        padding-top: 0.5rem;
      }

      /* CTAs */
      .confirmation-actions {
        display: flex;
        flex-direction: column;
        gap: 0.75rem;
        align-items: center;
      }

      .btn {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        gap: 0.5rem;
        border-radius: 10px;
        font-size: 0.9375rem;
        font-weight: 600;
        cursor: pointer;
        transition: all 0.2s ease;
        text-decoration: none;
        border: none;
        font-family: inherit;
        width: 100%;
        max-width: 360px;
        padding: 0.875rem 1.5rem;

        &:focus-visible {
          outline: 2px solid var(--brand-primary);
          outline-offset: 2px;
        }
      }

      .btn--primary {
        background: var(--brand-primary);
        color: #fff;

        &:hover {
          background: var(--color-primary-hover);
          transform: translateY(-1px);
          box-shadow: 0 8px 20px rgba(99, 102, 241, 0.3);
        }
      }

      .btn--ghost {
        background: transparent;
        color: var(--color-text-primary, #334155);
        border: 1.5px solid var(--color-border, #e2e8f0);

        &:hover {
          background: var(--color-surface-secondary, #f8fafc);
        }
      }

      .btn--link {
        background: none;
        color: var(--brand-primary);
        font-size: 0.9rem;
        font-weight: 500;
        padding: 0.5rem;
        width: auto;

        &:hover {
          text-decoration: underline;
        }
      }

      /* Dark mode */
      @media (prefers-color-scheme: dark) {
        .confirmation-page {
          background: var(--color-surface-primary, #0f172a);
        }

        .confirmation-title {
          color: var(--color-text-primary, #f1f5f9);
        }

        .confirmation-subtitle {
          color: var(--color-text-secondary, #94a3b8);
        }

        .booking-id-pill {
          background: var(--color-surface-secondary, #1e293b);
          color: var(--color-text-secondary, #94a3b8);
        }

        .next-steps {
          background: var(--color-surface-secondary, #1e293b);
          border-color: var(--color-border, #334155);
        }

        .step__icon {
          background: var(--color-surface-primary, #0f172a);
          border-color: var(--color-border, #334155);
        }

        .step__text {
          color: var(--color-text-primary, #e2e8f0);
        }

        .btn--ghost {
          color: var(--color-text-primary, #e2e8f0);
          border-color: var(--color-border, #334155);

          &:hover {
            background: var(--color-surface-secondary, #1e293b);
          }
        }
      }
    `,
  ],
})
export class BookingConfirmationComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly visible = signal(false);
  protected readonly bookingId = signal<string | null>(null);

  ngOnInit(): void {
    // Get booking ID from route params
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.bookingId.set(id);
    }

    // Trigger entrance animation
    requestAnimationFrame(() => {
      setTimeout(() => this.visible.set(true), 50);
    });
  }
}
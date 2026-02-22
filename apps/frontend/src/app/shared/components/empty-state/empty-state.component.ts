import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Input,
  Output,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';

export type EmptyStateVariant =
  | 'search'
  | 'upcoming-trips'
  | 'past-trips'
  | 'cancelled-trips'
  | 'favorites'
  | 'messages'
  | 'host-cars'
  | 'notifications'
  | 'custom';

interface EmptyStateConfig {
  headline: string;
  subtext: string | null;
  ctaLabel: string | null;
  ctaRoute: string | null;
}

const PRESET_CONFIGS: Record<Exclude<EmptyStateVariant, 'custom'>, EmptyStateConfig> = {
  search: {
    headline: 'Nismo pronašli automobile',
    subtext: 'Pokušaj da proširiš datume ili promeniš lokaciju.',
    ctaLabel: 'Promeni pretragu',
    ctaRoute: null,
  },
  'upcoming-trips': {
    headline: 'Nemaš predstojećih putovanja',
    subtext: 'Pronađi savršen auto za tvoju sledeću avanturu.',
    ctaLabel: 'Pretraži automobile',
    ctaRoute: '/cars',
  },
  'past-trips': {
    headline: 'Još nisi vozio na Rentozi',
    subtext: 'Hiljade automobila te čeka. Krenimo!',
    ctaLabel: 'Pretraži automobile',
    ctaRoute: '/cars',
  },
  'cancelled-trips': {
    headline: 'Nemaš otkazanih rezervacija',
    subtext: 'Sve rezervacije su bile uspešne.',
    ctaLabel: null,
    ctaRoute: null,
  },
  favorites: {
    headline: 'Nemaš sačuvanih automobila',
    subtext: 'Tapni srce na automobilu da ga dodaš ovde.',
    ctaLabel: 'Istraži automobile',
    ctaRoute: '/cars',
  },
  messages: {
    headline: 'Nema poruka',
    subtext: 'Razgovaraj sa domaćinima o dostupnosti i detaljima.',
    ctaLabel: null,
    ctaRoute: null,
  },
  'host-cars': {
    headline: 'Nisi još dodao automobil',
    subtext: 'Zaraduj dok auto stoji. Postavi oglas za nekoliko minuta.',
    ctaLabel: 'Dodaj automobil',
    ctaRoute: '/owner/cars/new',
  },
  notifications: {
    headline: 'Sve notifikacije su pročitane',
    subtext: null,
    ctaLabel: null,
    ctaRoute: null,
  },
};

@Component({
  selector: 'app-empty-state',
  standalone: true,
  imports: [CommonModule, RouterModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="empty-state" role="status" [attr.aria-label]="resolvedHeadline">
      <!-- SVG Illustration -->
      <div class="empty-state__illustration" aria-hidden="true">
        <ng-container [ngSwitch]="variant">

          <!-- Search: magnifying glass with ? -->
          <svg *ngSwitchCase="'search'" viewBox="0 0 120 120" fill="none" xmlns="http://www.w3.org/2000/svg">
            <circle cx="50" cy="50" r="30" stroke="currentColor" stroke-width="6" stroke-linecap="round"/>
            <line x1="72" y1="72" x2="95" y2="95" stroke="currentColor" stroke-width="6" stroke-linecap="round"/>
            <text x="50" y="58" text-anchor="middle" font-size="22" font-weight="700" fill="currentColor">?</text>
          </svg>

          <!-- Upcoming trips: calendar with car -->
          <svg *ngSwitchCase="'upcoming-trips'" viewBox="0 0 120 120" fill="none" xmlns="http://www.w3.org/2000/svg">
            <rect x="15" y="25" width="90" height="75" rx="10" stroke="currentColor" stroke-width="5"/>
            <line x1="15" y1="48" x2="105" y2="48" stroke="currentColor" stroke-width="5"/>
            <line x1="38" y1="15" x2="38" y2="35" stroke="currentColor" stroke-width="5" stroke-linecap="round"/>
            <line x1="82" y1="15" x2="82" y2="35" stroke="currentColor" stroke-width="5" stroke-linecap="round"/>
            <rect x="30" y="62" width="60" height="26" rx="8" stroke="currentColor" stroke-width="4"/>
            <circle cx="38" cy="88" r="5" stroke="currentColor" stroke-width="4"/>
            <circle cx="82" cy="88" r="5" stroke="currentColor" stroke-width="4"/>
            <line x1="43" y1="88" x2="77" y2="88" stroke="currentColor" stroke-width="4"/>
          </svg>

          <!-- Past trips: winding road -->
          <svg *ngSwitchCase="'past-trips'" viewBox="0 0 120 120" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M20 100 Q40 60 60 80 Q80 100 100 40" stroke="currentColor" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>
            <path d="M45 90 Q55 70 65 80" stroke="currentColor" stroke-width="3" stroke-dasharray="4 4" stroke-linecap="round"/>
            <circle cx="100" cy="40" r="10" stroke="currentColor" stroke-width="5"/>
            <path d="M96 40 l4-4 l4 4" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>

          <!-- Cancelled trips: shield with checkmark (positive state) -->
          <svg *ngSwitchCase="'cancelled-trips'" viewBox="0 0 120 120" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M60 10 L95 25 L95 60 C95 80 78 98 60 105 C42 98 25 80 25 60 L25 25 Z" stroke="currentColor" stroke-width="5" stroke-linejoin="round"/>
            <path d="M43 60 L55 72 L77 48" stroke="currentColor" stroke-width="6" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>

          <!-- Favorites: heart outline -->
          <svg *ngSwitchCase="'favorites'" viewBox="0 0 120 120" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M60 95 C60 95 18 68 18 40 C18 27 28 17 40 17 C50 17 58 24 60 24 C62 24 70 17 80 17 C92 17 102 27 102 40 C102 68 60 95 60 95Z" stroke="currentColor" stroke-width="5" stroke-linejoin="round"/>
            <rect x="42" y="62" width="36" height="20" rx="6" stroke="currentColor" stroke-width="4"/>
            <circle cx="50" cy="75" r="3" stroke="currentColor" stroke-width="3"/>
            <circle cx="70" cy="75" r="3" stroke="currentColor" stroke-width="3"/>
            <line x1="53" y1="75" x2="67" y2="75" stroke="currentColor" stroke-width="3"/>
          </svg>

          <!-- Messages: chat bubble -->
          <svg *ngSwitchCase="'messages'" viewBox="0 0 120 120" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M15 20 L105 20 L105 80 L70 80 L55 100 L55 80 L15 80 Z" stroke="currentColor" stroke-width="5" stroke-linejoin="round"/>
            <line x1="35" y1="45" x2="85" y2="45" stroke="currentColor" stroke-width="4" stroke-linecap="round"/>
            <line x1="35" y1="60" x2="68" y2="60" stroke="currentColor" stroke-width="4" stroke-linecap="round"/>
          </svg>

          <!-- Host cars: car + plus -->
          <svg *ngSwitchCase="'host-cars'" viewBox="0 0 120 120" fill="none" xmlns="http://www.w3.org/2000/svg">
            <rect x="10" y="45" width="80" height="42" rx="10" stroke="currentColor" stroke-width="5"/>
            <path d="M25 45 L35 20 L65 20 L75 45" stroke="currentColor" stroke-width="5" stroke-linejoin="round"/>
            <circle cx="28" cy="87" r="7" stroke="currentColor" stroke-width="5"/>
            <circle cx="72" cy="87" r="7" stroke="currentColor" stroke-width="5"/>
            <line x1="35" y1="87" x2="65" y2="87" stroke="currentColor" stroke-width="5"/>
            <!-- Plus badge -->
            <circle cx="92" cy="30" r="18" fill="white" stroke="currentColor" stroke-width="4"/>
            <line x1="92" y1="21" x2="92" y2="39" stroke="currentColor" stroke-width="4" stroke-linecap="round"/>
            <line x1="83" y1="30" x2="101" y2="30" stroke="currentColor" stroke-width="4" stroke-linecap="round"/>
          </svg>

          <!-- Notifications: bell with checkmark -->
          <svg *ngSwitchCase="'notifications'" viewBox="0 0 120 120" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M60 15 C42 15 28 30 28 50 L28 78 L14 90 L106 90 L92 78 L92 50 C92 30 78 15 60 15Z" stroke="currentColor" stroke-width="5" stroke-linejoin="round"/>
            <path d="M48 90 C48 97 53 103 60 103 C67 103 72 97 72 90" stroke="currentColor" stroke-width="5" stroke-linecap="round"/>
            <path d="M44 52 L56 64 L78 40" stroke="currentColor" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>

          <!-- Custom / default: generic box -->
          <svg *ngSwitchDefault viewBox="0 0 120 120" fill="none" xmlns="http://www.w3.org/2000/svg">
            <rect x="15" y="30" width="90" height="70" rx="10" stroke="currentColor" stroke-width="5"/>
            <path d="M15 52 L105 52" stroke="currentColor" stroke-width="5"/>
            <line x1="40" y1="75" x2="80" y2="75" stroke="currentColor" stroke-width="4" stroke-linecap="round"/>
          </svg>

        </ng-container>
      </div>

      <!-- Text -->
      <h2 class="empty-state__headline">{{ resolvedHeadline }}</h2>
      @if (resolvedSubtext) {
        <p class="empty-state__subtext">{{ resolvedSubtext }}</p>
      }

      <!-- CTA -->
      @if (resolvedCtaLabel) {
        @if (resolvedCtaRoute) {
          <a class="empty-state__cta" [routerLink]="resolvedCtaRoute">
            {{ resolvedCtaLabel }}
          </a>
        } @else {
          <button class="empty-state__cta" type="button" (click)="ctaClicked.emit()">
            {{ resolvedCtaLabel }}
          </button>
        }
      }
    </div>
  `,
  styles: [
    `
      :host {
        display: block;
      }

      .empty-state {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        text-align: center;
        padding: 4rem 2rem;
        min-height: 280px;
        animation: emptyStateFadeIn 0.4s ease 0.15s both;
      }

      @keyframes emptyStateFadeIn {
        from {
          opacity: 0;
          transform: scale(0.96);
        }
        to {
          opacity: 1;
          transform: scale(1);
        }
      }

      .empty-state__illustration {
        width: 100px;
        height: 100px;
        margin-bottom: 1.5rem;
        color: var(--brand-primary);
        opacity: 0.55;

        svg {
          width: 100%;
          height: 100%;
        }
      }

      .empty-state__headline {
        font-size: 1.375rem;
        font-weight: 700;
        color: var(--color-text-primary, #0f172a);
        margin: 0 0 0.625rem;
        max-width: 380px;
      }

      .empty-state__subtext {
        font-size: 0.9375rem;
        color: var(--color-text-secondary, #64748b);
        margin: 0 0 1.75rem;
        max-width: 360px;
        line-height: 1.6;
      }

      .empty-state__cta {
        display: inline-flex;
        align-items: center;
        gap: 0.5rem;
        padding: 0.75rem 1.75rem;
        background: var(--brand-primary);
        color: #fff;
        border: none;
        border-radius: 10px;
        font-size: 0.9375rem;
        font-weight: 600;
        cursor: pointer;
        font-family: inherit;
        text-decoration: none;
        transition: background 0.2s ease, transform 0.2s ease, box-shadow 0.2s ease;

        &:hover {
          background: var(--color-primary-hover);
          transform: translateY(-2px);
          box-shadow: 0 8px 20px rgba(99, 102, 241, 0.3);
        }

        &:focus-visible {
          outline: 2px solid var(--brand-primary);
          outline-offset: 3px;
        }
      }

      /* Dark mode */
      @media (prefers-color-scheme: dark) {
        .empty-state__headline {
          color: var(--color-text-primary, #f1f5f9);
        }

        .empty-state__subtext {
          color: var(--color-text-secondary, #94a3b8);
        }
      }

      /* Mobile */
      @media (max-width: 480px) {
        .empty-state {
          padding: 3rem 1.25rem;
        }

        .empty-state__cta {
          width: 100%;
          justify-content: center;
        }
      }
    `,
  ],
})
export class EmptyStateComponent {
  @Input() variant: EmptyStateVariant = 'custom';

  /** Override headline for custom variant */
  @Input() headline?: string;

  /** Override subtext for custom variant */
  @Input() subtext?: string | null;

  /** Override CTA label for custom variant (set to null to hide) */
  @Input() ctaLabel?: string | null;

  /** Override CTA route for custom variant */
  @Input() ctaRoute?: string | null;

  @Output() ctaClicked = new EventEmitter<void>();

  get resolvedConfig(): EmptyStateConfig {
    if (this.variant !== 'custom') {
      return PRESET_CONFIGS[this.variant];
    }
    return {
      headline: this.headline ?? 'Nema podataka',
      subtext: this.subtext ?? null,
      ctaLabel: this.ctaLabel ?? null,
      ctaRoute: this.ctaRoute ?? null,
    };
  }

  get resolvedHeadline(): string {
    return this.headline ?? this.resolvedConfig.headline;
  }

  get resolvedSubtext(): string | null {
    return this.subtext !== undefined ? this.subtext : this.resolvedConfig.subtext;
  }

  get resolvedCtaLabel(): string | null {
    return this.ctaLabel !== undefined ? this.ctaLabel : this.resolvedConfig.ctaLabel;
  }

  get resolvedCtaRoute(): string | null {
    return this.ctaRoute !== undefined ? this.ctaRoute : this.resolvedConfig.ctaRoute;
  }
}
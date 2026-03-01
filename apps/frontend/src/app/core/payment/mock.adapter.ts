import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';

import {
  PaymentProviderAdapter,
  TokenizationResult,
  TokenizationError,
  MountOptions,
} from './payment-provider.adapter';

/**
 * Mock test card definitions.
 *
 * These tokens are recognized by the backend MockPaymentProvider and trigger
 * specific payment outcomes (decline, SCA redirect, async processing, etc.).
 */
export interface MockTestCard {
  label: string;
  token: string;
  description: string;
  last4: string;
  brand: string;
}

export const MOCK_TEST_CARDS: MockTestCard[] = [
  {
    label: 'Visa (uspesno)',
    token: 'pm_card_visa',
    description: 'Standardna uspesna autorizacija',
    last4: '4242',
    brand: 'visa',
  },
  {
    label: 'Odbijena kartica',
    token: 'pm_card_declined',
    description: 'Kartica odbijena od strane banke',
    last4: '0002',
    brand: 'visa',
  },
  {
    label: 'Nedovoljno sredstava',
    token: 'pm_card_insufficient',
    description: 'Nedovoljno sredstava na racunu',
    last4: '9995',
    brand: 'visa',
  },
  {
    label: 'Istekla kartica',
    token: 'pm_card_expired',
    description: 'Kartica istekla',
    last4: '4000',
    brand: 'visa',
  },
  {
    label: 'Sumnja na prevaru',
    token: 'pm_card_fraud',
    description: 'Oznaceno kao sumnjiva transakcija',
    last4: '9979',
    brand: 'visa',
  },
  {
    label: '3DS/SCA potrebna',
    token: 'pm_card_sca_required',
    description: 'Zahteva 3D Secure verifikaciju',
    last4: '3220',
    brand: 'visa',
  },
  {
    label: 'Greska u obradi',
    token: 'pm_card_processing_error',
    description: 'Greska na strani procesora',
    last4: '0119',
    brand: 'visa',
  },
  {
    label: 'Asinhrona obrada',
    token: 'pm_card_async',
    description: 'Rezultat stize preko webhook-a',
    last4: '1234',
    brand: 'visa',
  },
];

/**
 * Mock payment provider adapter for credentialless staging.
 *
 * Renders a card-scenario selector instead of real PAN input.
 * Returns tokens recognized by the backend MockPaymentProvider,
 * enabling full end-to-end payment flow testing without Monri credentials.
 *
 * Implements the same PaymentProviderAdapter interface so the booking
 * dialog code path is identical in mock and production modes.
 */
@Injectable({ providedIn: 'root' })
export class MockPaymentAdapter implements PaymentProviderAdapter {
  readonly providerName = 'Mock (Staging)';

  private mounted = false;
  private selectedCard: MockTestCard = MOCK_TEST_CARDS[0];
  private containerEl: HTMLElement | null = null;

  private readonly _cardValid$ = new BehaviorSubject<boolean>(false);
  private readonly _cardError$ = new BehaviorSubject<string | null>(null);

  readonly cardValid$: Observable<boolean> = this._cardValid$.asObservable();
  readonly cardError$: Observable<string | null> = this._cardError$.asObservable();

  get isReady(): boolean {
    return true; // No SDK to load
  }

  async initialize(): Promise<void> {
    // No external SDK needed for mock mode
  }

  async mountCardForm(options: MountOptions): Promise<void> {
    const container =
      typeof options.container === 'string'
        ? document.querySelector(options.container)
        : options.container;

    if (!container) {
      throw this.createError('MOUNT_FAILED', 'Mock card form container not found');
    }

    this.containerEl = container as HTMLElement;
    this.renderMockForm();
    this.mounted = true;
    // Default first option is pre-selected = valid
    this._cardValid$.next(true);
  }

  async tokenize(): Promise<TokenizationResult> {
    if (!this.mounted) {
      throw this.createError(
        'NOT_MOUNTED',
        'Mock card form is not mounted. Call mountCardForm() first.',
      );
    }

    return {
      token: this.selectedCard.token,
      last4: this.selectedCard.last4,
      brand: this.selectedCard.brand,
      expiryMonth: 12,
      expiryYear: 2030,
    };
  }

  unmount(): void {
    if (this.containerEl) {
      this.containerEl.replaceChildren();
      this.containerEl = null;
    }
    this.mounted = false;
    this._cardValid$.next(false);
    this._cardError$.next(null);
  }

  // ──────────────────────────────────────────────────────────────────────

  /**
   * M7: Replaced innerHTML template-literal injection with DOM API construction
   * to eliminate XSS risk from interpolated card data.
   */
  private renderMockForm(): void {
    if (!this.containerEl) return;

    const wrapper = document.createElement('div');
    wrapper.className = 'mock-payment-form';

    // Banner
    const banner = document.createElement('div');
    Object.assign(banner.style, {
      background: '#fffde7',
      border: '1px dashed #f9a825',
      borderRadius: '8px',
      padding: '12px 16px',
      marginBottom: '8px',
      fontSize: '13px',
      color: '#5d4037',
      display: 'flex',
      alignItems: 'center',
      gap: '8px',
    });
    const iconSpan = document.createElement('span');
    iconSpan.style.fontSize = '18px';
    iconSpan.textContent = '\u26A0';
    const textSpan = document.createElement('span');
    const strong = document.createElement('strong');
    strong.textContent = 'STAGING REZIM';
    textSpan.append(strong, ' \u2014 Simulacija placanja bez pravih karticnih podataka');
    banner.append(iconSpan, textSpan);

    // Label
    const label = document.createElement('label');
    Object.assign(label.style, {
      display: 'block',
      fontSize: '13px',
      fontWeight: '500',
      color: 'rgba(0,0,0,0.6)',
      marginBottom: '6px',
    });
    label.textContent = 'Izaberite test scenario:';

    // Select
    const select = document.createElement('select');
    select.id = 'mock-card-select';
    Object.assign(select.style, {
      width: '100%',
      padding: '10px 12px',
      fontSize: '14px',
      border: '1px solid rgba(0,0,0,0.23)',
      borderRadius: '4px',
      background: 'white',
      cursor: 'pointer',
      fontFamily: 'inherit',
    });
    MOCK_TEST_CARDS.forEach((card, i) => {
      const option = document.createElement('option');
      option.value = String(i);
      option.selected = i === 0;
      option.textContent = `${card.label} \u2014 ${card.description}`;
      select.appendChild(option);
    });

    // Token display
    const tokenDisplay = document.createElement('div');
    tokenDisplay.id = 'mock-card-token';
    Object.assign(tokenDisplay.style, {
      marginTop: '6px',
      fontSize: '11px',
      color: 'rgba(0,0,0,0.38)',
      fontFamily: 'monospace',
    });
    tokenDisplay.textContent = `Token: ${this.selectedCard.token}`;

    wrapper.append(banner, label, select, tokenDisplay);
    this.containerEl.replaceChildren(wrapper);

    // Bind select handler
    select.addEventListener('change', () => {
      const idx = parseInt(select.value, 10);
      this.selectedCard = MOCK_TEST_CARDS[idx];
      this._cardValid$.next(true);
      this._cardError$.next(null);
      tokenDisplay.textContent = `Token: ${this.selectedCard.token}`;
    });
  }

  private createError(code: string, message: string): TokenizationError {
    return { code, message };
  }
}

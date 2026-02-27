import { Injectable, inject } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';

import {
  PaymentProviderAdapter,
  TokenizationResult,
  TokenizationError,
  MountOptions,
} from './payment-provider.adapter';
import { environment } from '@environments/environment';

/**
 * Monri SDK type declarations.
 * Monri's JS SDK exposes a global `Monri` constructor after script load.
 */
interface MonriInstance {
  components(options?: { clientSecret?: string }): MonriComponents;
  createToken(component: MonriCardComponent): Promise<MonriTokenResponse>;
}

interface MonriComponents {
  create(type: 'card', options?: Record<string, unknown>): MonriCardComponent;
}

interface MonriCardComponent {
  mount(element: HTMLElement | string): void;
  unmount(): void;
  on(event: 'change', handler: (ev: MonriChangeEvent) => void): void;
}

interface MonriChangeEvent {
  complete: boolean;
  error?: { message: string; code: string };
  brand?: string;
}

interface MonriTokenResponse {
  result?: {
    status: string;
    pan_token?: string;
    masked_pan?: string;
    brand?: string;
    expiration_date?: string;
  };
  error?: { message: string; code: string };
}

declare global {
  interface Window {
    Monri?: new (authenticityToken: string, options?: { locale?: string }) => MonriInstance;
  }
}

/**
 * Monri payment provider adapter.
 *
 * Loads Monri's Components JS SDK, mounts a secure card input,
 * and tokenizes card details into a PAN token for backend authorization.
 *
 * Configuration is read from `environment.monri`.
 */
@Injectable({ providedIn: 'root' })
export class MonriAdapter implements PaymentProviderAdapter {
  readonly providerName = 'Monri';

  private monriInstance: MonriInstance | null = null;
  private cardComponent: MonriCardComponent | null = null;
  private sdkLoaded = false;
  private sdkLoading: Promise<void> | null = null;

  private readonly _cardValid$ = new BehaviorSubject<boolean>(false);
  private readonly _cardError$ = new BehaviorSubject<string | null>(null);

  readonly cardValid$: Observable<boolean> = this._cardValid$.asObservable();
  readonly cardError$: Observable<string | null> = this._cardError$.asObservable();

  get isReady(): boolean {
    return this.sdkLoaded && this.monriInstance !== null;
  }

  async initialize(): Promise<void> {
    if (this.sdkLoaded) return;
    if (this.sdkLoading) {
      await this.sdkLoading;
      return;
    }

    this.sdkLoading = this.loadSdk();
    await this.sdkLoading;
  }

  async mountCardForm(options: MountOptions): Promise<void> {
    if (!this.isReady) {
      await this.initialize();
    }

    if (!this.monriInstance) {
      throw this.createError('SDK_NOT_LOADED', 'Monri SDK failed to initialize');
    }

    // Create components container
    const components = this.monriInstance.components();

    // Create card element with styling
    this.cardComponent = components.create('card', {
      style: options.style ?? {
        base: {
          fontSize: '16px',
          fontFamily: 'Roboto, sans-serif',
          color: '#1a1a1a',
          '::placeholder': { color: '#9e9e9e' },
        },
        invalid: {
          color: '#c62828',
        },
      },
    });

    // Listen for validation changes
    this.cardComponent.on('change', (event: MonriChangeEvent) => {
      this._cardValid$.next(event.complete);
      this._cardError$.next(event.error?.message ?? null);
    });

    // Mount to container
    const container =
      typeof options.container === 'string'
        ? document.querySelector(options.container)
        : options.container;

    if (!container) {
      throw this.createError('MOUNT_FAILED', 'Card form container element not found');
    }

    this.cardComponent.mount(container as HTMLElement);
  }

  async tokenize(): Promise<TokenizationResult> {
    if (!this.monriInstance || !this.cardComponent) {
      throw this.createError(
        'NOT_MOUNTED',
        'Card form is not mounted. Call mountCardForm() first.',
      );
    }

    const response = await this.monriInstance.createToken(this.cardComponent);

    if (response.error) {
      throw this.createError(response.error.code, response.error.message);
    }

    const result = response.result;
    if (!result?.pan_token) {
      throw this.createError('TOKENIZATION_FAILED', 'Tokenizacija kartice nije uspela.');
    }

    // Parse masked PAN for last4 (e.g. "411111******1111" -> "1111")
    const maskedPan = result.masked_pan ?? '';
    const last4 = maskedPan.slice(-4) || '****';

    // Parse expiry (format: "YYMM" or "MM/YY")
    let expiryMonth: number | undefined;
    let expiryYear: number | undefined;
    if (result.expiration_date) {
      const exp = result.expiration_date;
      if (exp.includes('/')) {
        const [mm, yy] = exp.split('/');
        expiryMonth = parseInt(mm, 10);
        expiryYear = 2000 + parseInt(yy, 10);
      } else if (exp.length === 4) {
        expiryYear = 2000 + parseInt(exp.slice(0, 2), 10);
        expiryMonth = parseInt(exp.slice(2, 4), 10);
      }
    }

    return {
      token: result.pan_token,
      last4,
      brand: result.brand,
      expiryMonth,
      expiryYear,
    };
  }

  unmount(): void {
    if (this.cardComponent) {
      try {
        this.cardComponent.unmount();
      } catch {
        // Ignore unmount errors during cleanup
      }
      this.cardComponent = null;
    }
    this._cardValid$.next(false);
    this._cardError$.next(null);
  }

  // ──────────────────────────────────────────────────────────────────────

  private async loadSdk(): Promise<void> {
    // Check if already loaded (e.g. via <script> tag in index.html)
    if (window.Monri) {
      this.createMonriInstance();
      return;
    }

    const monriConfig = (environment as Record<string, unknown>)['monri'] as
      | { authenticityToken: string; sdkUrl: string }
      | undefined;

    if (!monriConfig?.sdkUrl) {
      throw this.createError(
        'CONFIG_MISSING',
        'Monri SDK URL not configured in environment.monri.sdkUrl',
      );
    }

    await new Promise<void>((resolve, reject) => {
      const script = document.createElement('script');
      script.src = monriConfig.sdkUrl;
      script.async = true;
      script.onload = () => {
        this.createMonriInstance();
        resolve();
      };
      script.onerror = () => {
        reject(this.createError('SDK_LOAD_FAILED', 'Failed to load Monri SDK script'));
      };
      document.head.appendChild(script);
    });
  }

  private createMonriInstance(): void {
    const monriConfig = (environment as Record<string, unknown>)['monri'] as
      | { authenticityToken: string }
      | undefined;

    if (!monriConfig?.authenticityToken) {
      throw this.createError(
        'CONFIG_MISSING',
        'Monri authenticity token not configured in environment.monri.authenticityToken',
      );
    }

    if (!window.Monri) {
      throw this.createError('SDK_NOT_AVAILABLE', 'Monri global not found after script load');
    }

    this.monriInstance = new window.Monri(monriConfig.authenticityToken, { locale: 'sr' });
    this.sdkLoaded = true;
  }

  private createError(code: string, message: string): TokenizationError {
    return { code, message };
  }
}

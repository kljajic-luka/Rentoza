import { Observable } from 'rxjs';

/**
 * Result of a card tokenization operation.
 * The token is sent to the backend as paymentMethodId.
 */
export interface TokenizationResult {
  /** Opaque token representing the card. Sent to backend as paymentMethodId. */
  token: string;
  /** Last 4 digits for display (e.g. "4242"). */
  last4: string;
  /** Card brand if available (e.g. "visa", "mastercard"). */
  brand?: string;
  /** Expiry month (1-12). */
  expiryMonth?: number;
  /** Expiry year (4-digit). */
  expiryYear?: number;
}

/**
 * Error from the payment provider during tokenization.
 */
export interface TokenizationError {
  code: string;
  message: string;
}

/**
 * Options for mounting the card input element.
 */
export interface MountOptions {
  /** DOM element or CSS selector to mount into. */
  container: HTMLElement | string;
  /** Locale for card form labels. */
  locale?: string;
  /** Custom styling overrides (provider-specific). */
  style?: Record<string, unknown>;
}

/**
 * Provider-agnostic payment adapter interface.
 *
 * Implementations handle SDK loading, card form mounting, and tokenization
 * for a specific payment provider (Monri, Stripe, etc.).
 *
 * Lifecycle:
 *   1. initialize() — load SDK, set up credentials
 *   2. mountCardForm(options) — render secure card input into DOM
 *   3. tokenize() — collect card data and return opaque token
 *   4. unmount() — cleanup DOM and SDK resources
 */
export interface PaymentProviderAdapter {
  /** Human-readable provider name (e.g. "Monri", "Stripe"). */
  readonly providerName: string;

  /** Whether the SDK has been loaded and is ready. */
  readonly isReady: boolean;

  /** Initialize the provider SDK. Idempotent — safe to call multiple times. */
  initialize(): Promise<void>;

  /** Mount the secure card input form into the given container. */
  mountCardForm(options: MountOptions): Promise<void>;

  /** Tokenize the current card input. Returns the token or throws TokenizationError. */
  tokenize(): Promise<TokenizationResult>;

  /** Unmount the card form and release SDK resources. */
  unmount(): void;

  /** Observable that emits card input validation state changes. */
  readonly cardValid$: Observable<boolean>;

  /** Observable that emits card input error messages. */
  readonly cardError$: Observable<string | null>;
}

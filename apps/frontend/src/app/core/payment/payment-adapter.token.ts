import { InjectionToken, Provider } from '@angular/core';

import { PaymentProviderAdapter } from './payment-provider.adapter';
import { MonriAdapter } from './monri.adapter';
import { MockPaymentAdapter } from './mock.adapter';
import { environment } from '@environments/environment';

/**
 * DI token for the active payment provider adapter.
 *
 * Adapter selection is driven entirely by `environment.payment.providerMode`.
 * In production, only 'monri' is permitted.
 * In dev/staging, 'mock' is the default for credentialless testing.
 */
export const PAYMENT_ADAPTER = new InjectionToken<PaymentProviderAdapter>('PAYMENT_ADAPTER');

/**
 * Payment configuration shape from environment files.
 */
type PaymentConfig = {
  providerMode: 'mock' | 'monri';
  allowMockInThisEnv: boolean;
  modeLabel?: 'MOCK' | 'MONRI';
};

function hasMonriAuthToken(): boolean {
  const monriConfig = (environment as Record<string, unknown>)['monri'] as
    | { authenticityToken?: string }
    | undefined;

  return (
    typeof monriConfig?.authenticityToken === 'string' &&
    monriConfig.authenticityToken.trim().length > 0
  );
}

/**
 * Resolves the active payment provider mode with safety checks.
 *
 * Throws at bootstrap time if production attempts to use mock mode.
 * Logs clear warnings for misconfiguration.
 */
export function resolveProviderMode(): 'mock' | 'monri' {
  const paymentConfig = (environment as Record<string, unknown>)['payment'] as
    | PaymentConfig
    | undefined;

  if (!paymentConfig) {
    // No payment config → default based on production flag
    if (environment.production) {
      console.error(
        '[PAYMENT] No payment config found in production environment. Defaulting to monri.',
      );
      return 'monri';
    }
    console.warn('[PAYMENT] No payment config found. Defaulting to mock mode for development.');
    return 'mock';
  }

  const { providerMode, allowMockInThisEnv } = paymentConfig;

  // Safety guardrail: block mock in production
  if (providerMode === 'mock' && !allowMockInThisEnv) {
    throw new Error(
      '[PAYMENT] FATAL: Mock payment mode is not allowed in this environment. ' +
        'Set payment.providerMode to "monri" or enable payment.allowMockInThisEnv (non-production only).',
    );
  }

  if (providerMode === 'mock') {
    console.warn(
      '[PAYMENT] Running in MOCK payment mode. ' +
        'No real card data will be collected. Tokens are simulated test scenarios.',
    );
    return 'mock';
  }

  // Non-prod fallback: monri configured without a token can safely use mock mode
  // as long as this environment explicitly allows mock payments.
  if (providerMode === 'monri' && !hasMonriAuthToken()) {
    if (allowMockInThisEnv) {
      console.warn(
        '[PAYMENT] Monri mode requested but authenticity token is missing. ' +
          'Falling back to MOCK mode for this non-production environment.',
      );
      return 'mock';
    }

    throw new Error(
      '[PAYMENT] FATAL: Monri mode requires environment.monri.authenticityToken in this environment.',
    );
  }

  return providerMode;
}

/**
 * Factory provider for the payment adapter.
 *
 * Usage: Add `providePaymentAdapter()` to your app providers or component imports.
 */
export function providePaymentAdapter(): Provider {
  return {
    provide: PAYMENT_ADAPTER,
    useFactory: (monri: MonriAdapter, mock: MockPaymentAdapter): PaymentProviderAdapter => {
      const mode = resolveProviderMode();
      return mode === 'monri' ? monri : mock;
    },
    deps: [MonriAdapter, MockPaymentAdapter],
  };
}

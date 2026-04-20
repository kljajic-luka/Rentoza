import { environment } from '@environments/environment';

import { resolveProviderMode } from './payment-adapter.token';

type MutableEnvironment = {
  payment?: {
    providerMode?: 'mock' | 'monri';
    allowMockInThisEnv?: boolean;
    modeLabel?: 'MOCK' | 'MONRI';
  };
  monri?: {
    authenticityToken?: string;
    sdkUrl?: string;
  };
};

describe('resolveProviderMode', () => {
  const env = environment as MutableEnvironment;

  let originalPayment: MutableEnvironment['payment'];
  let originalMonri: MutableEnvironment['monri'];

  beforeEach(() => {
    originalPayment = env.payment ? { ...env.payment } : undefined;
    originalMonri = env.monri ? { ...env.monri } : undefined;
  });

  afterEach(() => {
    env.payment = originalPayment;
    env.monri = originalMonri;
  });

  it('returns mock when mock mode is configured and allowed', () => {
    env.payment = {
      providerMode: 'mock',
      allowMockInThisEnv: true,
    };

    expect(resolveProviderMode()).toBe('mock');
  });

  it('throws when mock mode is configured but not allowed', () => {
    env.payment = {
      providerMode: 'mock',
      allowMockInThisEnv: false,
    };

    expect(() => resolveProviderMode()).toThrowError(/Mock payment mode is not allowed/i);
  });

  it('returns monri when monri mode is configured and token exists', () => {
    env.payment = {
      providerMode: 'monri',
      allowMockInThisEnv: false,
    };
    env.monri = {
      ...(env.monri ?? {}),
      authenticityToken: 'token-present',
    };

    expect(resolveProviderMode()).toBe('monri');
  });

  it('falls back to mock when monri token is missing in non-production mock-allowed env', () => {
    env.payment = {
      providerMode: 'monri',
      allowMockInThisEnv: true,
    };
    env.monri = {
      ...(env.monri ?? {}),
      authenticityToken: '',
    };

    const warnSpy = spyOn(console, 'warn');
    expect(resolveProviderMode()).toBe('mock');
    expect(warnSpy).toHaveBeenCalledWith(
      jasmine.stringMatching(/Monri mode requested but authenticity token is missing/i),
    );
  });

  it('throws when monri token is missing and mock is not allowed', () => {
    env.payment = {
      providerMode: 'monri',
      allowMockInThisEnv: false,
    };
    env.monri = {
      ...(env.monri ?? {}),
      authenticityToken: '',
    };

    expect(() => resolveProviderMode()).toThrowError(/Monri mode requires/i);
  });
});

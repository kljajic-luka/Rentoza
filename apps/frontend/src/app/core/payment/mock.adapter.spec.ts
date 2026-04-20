import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { MockPaymentAdapter, MOCK_TEST_CARDS } from './mock.adapter';

describe('MockPaymentAdapter', () => {
  let adapter: MockPaymentAdapter;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [MockPaymentAdapter],
    });
    adapter = TestBed.inject(MockPaymentAdapter);
  });

  afterEach(() => {
    adapter.unmount();
  });

  describe('Initial State', () => {
    it('should have providerName as Mock (Staging)', () => {
      expect(adapter.providerName).toBe('Mock (Staging)');
    });

    it('should be ready immediately (no SDK to load)', () => {
      expect(adapter.isReady).toBe(true);
    });

    it('should emit false for initial card validity (not mounted)', async () => {
      const valid = await firstValueFrom(adapter.cardValid$);
      expect(valid).toBe(false);
    });

    it('should emit null for initial card error', async () => {
      const error = await firstValueFrom(adapter.cardError$);
      expect(error).toBeNull();
    });
  });

  describe('initialize', () => {
    it('should resolve immediately (no external SDK)', async () => {
      await adapter.initialize();
      expect(adapter.isReady).toBe(true);
    });
  });

  describe('mountCardForm', () => {
    it('should mount into an HTML container element', async () => {
      const container = document.createElement('div');
      await adapter.mountCardForm({ container });
      // After mount, card should be valid (default card pre-selected)
      const valid = await firstValueFrom(adapter.cardValid$);
      expect(valid).toBe(true);
    });

    it('should throw if container not found', async () => {
      try {
        await adapter.mountCardForm({ container: '#nonexistent' });
        fail('Should have thrown');
      } catch (err: unknown) {
        const error = err as { code: string };
        expect(error.code).toBe('MOUNT_FAILED');
      }
    });

    it('should render mock form with test card selector', async () => {
      const container = document.createElement('div');
      await adapter.mountCardForm({ container });
      const select = container.querySelector('#mock-card-select') as HTMLSelectElement;
      expect(select).toBeTruthy();
      expect(select.options.length).toBe(MOCK_TEST_CARDS.length);
    });
  });

  describe('tokenize', () => {
    it('should throw if not mounted', async () => {
      try {
        await adapter.tokenize();
        fail('Should have thrown');
      } catch (err: unknown) {
        const error = err as { code: string };
        expect(error.code).toBe('NOT_MOUNTED');
      }
    });

    it('should return default test card token (pm_card_visa) when mounted', async () => {
      const container = document.createElement('div');
      await adapter.mountCardForm({ container });

      const result = await adapter.tokenize();
      expect(result.token).toBe('pm_card_visa');
      expect(result.last4).toBe('4242');
      expect(result.brand).toBe('visa');
    });

    it('should return selected card token after selection change', async () => {
      const container = document.createElement('div');
      await adapter.mountCardForm({ container });

      // Simulate selecting "declined" card (index 1)
      const select = container.querySelector('#mock-card-select') as HTMLSelectElement;
      select.value = '1';
      select.dispatchEvent(new Event('change'));

      const result = await adapter.tokenize();
      expect(result.token).toBe('pm_card_declined');
      expect(result.last4).toBe('0002');
    });
  });

  describe('unmount', () => {
    it('should reset validity to false after unmount', async () => {
      const container = document.createElement('div');
      await adapter.mountCardForm({ container });
      adapter.unmount();
      const valid = await firstValueFrom(adapter.cardValid$);
      expect(valid).toBe(false);
    });
  });

  describe('MOCK_TEST_CARDS', () => {
    it('should contain at least success and decline cards', () => {
      const tokens = MOCK_TEST_CARDS.map((c) => c.token);
      expect(tokens).toContain('pm_card_visa');
      expect(tokens).toContain('pm_card_declined');
      expect(tokens).toContain('pm_card_insufficient');
      expect(tokens).toContain('pm_card_sca_required');
      expect(tokens).toContain('pm_card_async');
    });

    it('should have 8 test cards matching backend MockPaymentProvider', () => {
      expect(MOCK_TEST_CARDS.length).toBe(8);
    });
  });
});

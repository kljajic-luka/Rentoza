import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { MonriAdapter } from './monri.adapter';

describe('MonriAdapter', () => {
  let adapter: MonriAdapter;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [MonriAdapter],
    });
    adapter = TestBed.inject(MonriAdapter);
  });

  afterEach(() => {
    adapter.unmount();
  });

  describe('Initial State', () => {
    it('should have providerName as Monri', () => {
      expect(adapter.providerName).toBe('Monri');
    });

    it('should not be ready before initialization', () => {
      expect(adapter.isReady).toBe(false);
    });

    it('should emit false for initial card validity', async () => {
      const valid = await firstValueFrom(adapter.cardValid$);
      expect(valid).toBe(false);
    });

    it('should emit null for initial card error', async () => {
      const error = await firstValueFrom(adapter.cardError$);
      expect(error).toBeNull();
    });
  });

  describe('unmount', () => {
    it('should reset card validity to false after unmount', async () => {
      adapter.unmount();
      const valid = await firstValueFrom(adapter.cardValid$);
      expect(valid).toBe(false);
    });

    it('should reset card error to null after unmount', async () => {
      adapter.unmount();
      const error = await firstValueFrom(adapter.cardError$);
      expect(error).toBeNull();
    });
  });

  describe('tokenize without mount', () => {
    it('should throw error when tokenize called before mount', async () => {
      try {
        await adapter.tokenize();
        fail('Should have thrown');
      } catch (err: unknown) {
        const error = err as { code: string; message: string };
        expect(error.code).toBe('NOT_MOUNTED');
      }
    });
  });
});

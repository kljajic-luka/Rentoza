import { TestBed, fakeAsync, tick, flushMicrotasks } from '@angular/core/testing';
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

  // ── Hang-bug regression ──────────────────────────────────────────────────────
  // Prior to the fix, createMonriInstance() throwing inside script.onload would
  // escape the Promise callback unhandled. resolve() was never called, reject()
  // was never called, and the Promise hung forever — causing the infinite spinner.
  describe('SDK loading hang-bug regression', () => {
    const origCreateElement = document.createElement.bind(document);

    function makeFakeScript() {
      return {
        onload: null as (() => void) | null,
        onerror: null as (() => void) | null,
        src: '',
        async: false,
      };
    }

    afterEach(() => {
      // Remove any window.Monri set during tests
      delete (window as { Monri?: unknown }).Monri;
    });

    it('should reject (not hang) when createMonriInstance throws after onload fires', fakeAsync(() => {
      // Ensure we exercise the script-loading code path, not the early-return path
      delete (window as { Monri?: unknown }).Monri;

      const fakeScript = makeFakeScript();

      spyOn(document, 'createElement').and.callFake((tag: string) => {
        if (tag === 'script') return fakeScript as unknown as HTMLScriptElement;
        return origCreateElement(tag);
      });

      spyOn(document.head, 'appendChild').and.callFake(<T extends Node>(node: T) => {
        // Simulate async script load after 100 ms.
        // createMonriInstance() will throw CONFIG_MISSING because
        // environment.monri.authenticityToken is '' in test builds.
        setTimeout(() => fakeScript.onload?.(), 100);
        return node;
      });

      let resolved = false;
      let rejection: unknown;

      adapter.initialize()
        .then(() => (resolved = true))
        .catch((err) => (rejection = err));

      tick(200); // Fire the simulated onload
      flushMicrotasks(); // Settle the Promise chain

      expect(resolved).toBe(false);
      expect((rejection as { code: string })?.code).toBe('CONFIG_MISSING');
    }));

    it('should reject with SDK_TIMEOUT when SDK script never fires onload', fakeAsync(() => {
      delete (window as { Monri?: unknown }).Monri;

      const fakeScript = makeFakeScript();

      spyOn(document, 'createElement').and.callFake((tag: string) => {
        if (tag === 'script') return fakeScript as unknown as HTMLScriptElement;
        return origCreateElement(tag);
      });

      // Intentionally do NOT fire onload — simulates a CDN stall / network outage
      spyOn(document.head, 'appendChild').and.callFake(<T extends Node>(node: T) => node);

      let resolved = false;
      let rejection: unknown;

      adapter.initialize()
        .then(() => (resolved = true))
        .catch((err) => (rejection = err));

      tick(15_001); // Pass the 15 s SDK_TIMEOUT threshold
      flushMicrotasks();

      expect(resolved).toBe(false);
      expect((rejection as { code: string })?.code).toBe('SDK_TIMEOUT');
    }));
  });
});

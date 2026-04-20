import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  tick,
  flushMicrotasks,
  discardPeriodicTasks,
} from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { BehaviorSubject } from 'rxjs';

import { PaymentFormComponent } from './payment-form.component';
import { PAYMENT_ADAPTER } from '../payment-adapter.token';
import {
  PaymentProviderAdapter,
  TokenizationResult,
  TokenizationError,
} from '../payment-provider.adapter';

/** Returns a Promise that never settles — simulates a hanging adapter. */
function neverSettle<T>(): Promise<T> {
  return new Promise<T>(() => undefined);
}

function buildAdapter(overrides: Partial<PaymentProviderAdapter> = {}): PaymentProviderAdapter {
  const unmountSpy = jasmine.createSpy('unmount');
  return {
    providerName: 'Test',
    isReady: true,
    cardValid$: new BehaviorSubject<boolean>(false).asObservable(),
    cardError$: new BehaviorSubject<string | null>(null).asObservable(),
    initialize: () => Promise.resolve(),
    mountCardForm: () => neverSettle<void>(),
    tokenize: () => neverSettle<TokenizationResult>(),
    unmount: unmountSpy,
    ...overrides,
  };
}

/**
 * Creates a PaymentFormComponent fixture with a controlled adapter.
 *
 * Uses TestBed.overrideComponent to replace the component-level
 * providePaymentAdapter() provider with the test-supplied adapter.
 */
function createFixture(adapter: PaymentProviderAdapter): ComponentFixture<PaymentFormComponent> {
  TestBed.configureTestingModule({
    imports: [PaymentFormComponent, NoopAnimationsModule],
  });
  TestBed.overrideComponent(PaymentFormComponent, {
    set: { providers: [{ provide: PAYMENT_ADAPTER, useValue: adapter }] },
  });
  const fixture = TestBed.createComponent(PaymentFormComponent);
  fixture.detectChanges(); // triggers ngOnInit + starts ngAfterViewInit
  return fixture;
}

describe('PaymentFormComponent', () => {
  describe('loading state', () => {
    it('should show loading spinner while adapter is mounting', fakeAsync(() => {
      const fixture = createFixture(buildAdapter()); // mountCardForm never resolves

      const spinner = fixture.nativeElement.querySelector('mat-spinner');
      expect(spinner).toBeTruthy();

      discardPeriodicTasks();
    }));

    it('should hide spinner and show timeout error after 15 s with no response', fakeAsync(() => {
      // This is the exact bug scenario: Monri SDK hangs after script load.
      // Previously the spinner stayed visible forever. The fix uses Promise.race
      // with a 15 s timeout that rejects with MOUNT_TIMEOUT.
      const fixture = createFixture(buildAdapter()); // mountCardForm never resolves

      tick(15_001); // Trigger the MOUNT_TIMEOUT
      flushMicrotasks(); // Settle Promise chain → catch block runs → signals updated
      fixture.detectChanges(); // Re-render OnPush view with new signal values

      const spinner = fixture.nativeElement.querySelector('mat-spinner');
      const errorEl = fixture.nativeElement.querySelector('.payment-form__error--init');

      expect(spinner).toBeFalsy();
      expect(errorEl).toBeTruthy();
      expect((errorEl.textContent as string).trim()).toContain('Plaćanje se nije učitalo');
    }));
  });

  describe('successful mount', () => {
    it('should hide spinner once adapter mounts successfully', fakeAsync(() => {
      const fixture = createFixture(buildAdapter({ mountCardForm: () => Promise.resolve() }));

      tick(0); // Let the resolved promise settle
      flushMicrotasks();
      fixture.detectChanges();

      const spinner = fixture.nativeElement.querySelector('mat-spinner');
      expect(spinner).toBeFalsy();
    }));

    it('should not show initError after a successful mount', fakeAsync(() => {
      const fixture = createFixture(buildAdapter({ mountCardForm: () => Promise.resolve() }));

      tick(0);
      flushMicrotasks();
      fixture.detectChanges();

      const errorEl = fixture.nativeElement.querySelector('.payment-form__error--init');
      expect(errorEl).toBeFalsy();
    }));
  });

  describe('adapter failure', () => {
    it('should show initError when adapter rejects immediately', fakeAsync(() => {
      const error: TokenizationError = { code: 'SDK_LOAD_FAILED', message: 'Greška adaptera' };
      const fixture = createFixture(
        buildAdapter({ mountCardForm: () => Promise.reject<void>(error) }),
      );

      tick(0);
      flushMicrotasks();
      fixture.detectChanges();

      const spinnerEl = fixture.nativeElement.querySelector('mat-spinner');
      const errorEl = fixture.nativeElement.querySelector('.payment-form__error--init');

      expect(spinnerEl).toBeFalsy();
      expect(errorEl).toBeTruthy();
      expect((errorEl.textContent as string).trim()).toContain('Greška adaptera');
    }));
  });

  describe('cleanup', () => {
    it('should call adapter.unmount on destroy', fakeAsync(() => {
      const adapter = buildAdapter({ mountCardForm: () => Promise.resolve() });
      const fixture = createFixture(adapter);

      tick(0);
      flushMicrotasks();
      fixture.destroy();

      expect(adapter.unmount).toHaveBeenCalledTimes(1);
    }));
  });
});

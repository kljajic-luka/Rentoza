import {
  Component,
  OnInit,
  OnDestroy,
  AfterViewInit,
  Inject,
  signal,
  output,
  ElementRef,
  ViewChild,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { Subscription } from 'rxjs';

import {
  PaymentProviderAdapter,
  TokenizationResult,
  TokenizationError,
} from '../payment-provider.adapter';
import { PAYMENT_ADAPTER, providePaymentAdapter } from '../payment-adapter.token';

/**
 * Secure card input form component.
 *
 * Wraps the Monri Components card element in a styled container.
 * Emits tokenization results or errors to the parent component.
 *
 * Usage:
 * ```html
 * <app-payment-form
 *   (tokenized)="onToken($event)"
 *   (tokenError)="onError($event)"
 *   (validityChange)="onValid($event)"
 * />
 * ```
 */
@Component({
  selector: 'app-payment-form',
  standalone: true,
  imports: [CommonModule, MatProgressSpinnerModule, MatIconModule],
  providers: [providePaymentAdapter()],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="payment-form">
      <label class="payment-form__label">
        <mat-icon>credit_card</mat-icon>
        Podaci kartice
      </label>

      <div
        class="payment-form__card-container"
        [class.payment-form__card-container--error]="cardError()"
      >
        @if (isLoading()) {
          <div class="payment-form__loading">
            <mat-spinner diameter="24"></mat-spinner>
            <span>Učitavanje platnog formulara...</span>
          </div>
        }
        <div #cardElement class="payment-form__card-element" [class.hidden]="isLoading()"></div>
      </div>

      @if (cardError()) {
        <p class="payment-form__error">
          <mat-icon>error_outline</mat-icon>
          {{ cardError() }}
        </p>
      }

      @if (initError()) {
        <p class="payment-form__error payment-form__error--init">
          <mat-icon>warning</mat-icon>
          {{ initError() }}
        </p>
      }
    </div>
  `,
  styles: [
    `
      .payment-form {
        margin: 0;
      }

      .payment-form__label {
        display: flex;
        align-items: center;
        gap: 8px;
        font-size: 14px;
        font-weight: 500;
        color: rgba(0, 0, 0, 0.7);
        margin-bottom: 8px;

        mat-icon {
          font-size: 20px;
          width: 20px;
          height: 20px;
        }
      }

      .payment-form__card-container {
        border: 1px solid rgba(0, 0, 0, 0.23);
        border-radius: 4px;
        padding: 14px 16px;
        transition: border-color 200ms ease;
        min-height: 48px;
        display: flex;
        align-items: center;

        &:hover {
          border-color: rgba(0, 0, 0, 0.87);
        }

        &:focus-within {
          border-color: var(--brand-primary, #593cfb);
          border-width: 2px;
          padding: 13px 15px;
        }

        &--error {
          border-color: #c62828;
        }
      }

      .payment-form__card-element {
        width: 100%;
        min-height: 24px;

        &.hidden {
          display: none;
        }
      }

      .payment-form__loading {
        display: flex;
        align-items: center;
        gap: 12px;
        color: rgba(0, 0, 0, 0.5);
        font-size: 14px;
      }

      .payment-form__error {
        display: flex;
        align-items: center;
        gap: 6px;
        color: #c62828;
        font-size: 12px;
        margin: 6px 0 0;

        mat-icon {
          font-size: 16px;
          width: 16px;
          height: 16px;
        }

        &--init {
          color: #e65100;
        }
      }
    `,
  ],
})
export class PaymentFormComponent implements OnInit, AfterViewInit, OnDestroy {
  private readonly adapter: PaymentProviderAdapter;

  constructor(@Inject(PAYMENT_ADAPTER) adapter: PaymentProviderAdapter) {
    this.adapter = adapter;
  }

  @ViewChild('cardElement', { static: true }) cardElementRef!: ElementRef<HTMLElement>;

  /** Emits when card input validity changes. */
  readonly validityChange = output<boolean>();

  /** Emits the tokenization result on success. */
  readonly tokenized = output<TokenizationResult>();

  /** Emits tokenization errors. */
  readonly tokenError = output<TokenizationError>();

  protected readonly isLoading = signal(true);
  protected readonly cardError = signal<string | null>(null);
  protected readonly initError = signal<string | null>(null);

  private subs: Subscription[] = [];

  ngOnInit(): void {
    // Subscribe to card validity and error streams
    this.subs.push(
      this.adapter.cardValid$.subscribe((valid) => {
        this.validityChange.emit(valid);
      }),
      this.adapter.cardError$.subscribe((error) => {
        this.cardError.set(error);
      }),
    );
  }

  async ngAfterViewInit(): Promise<void> {
    try {
      await this.adapter.mountCardForm({
        container: this.cardElementRef.nativeElement,
        locale: 'sr',
      });
      this.isLoading.set(false);
    } catch (err) {
      const error = err as TokenizationError;
      this.isLoading.set(false);
      this.initError.set(error.message || 'Greska pri ucitavanju platnog formulara');
    }
  }

  /**
   * Tokenize the current card input.
   * Called by the parent component (booking dialog) on form submit.
   */
  async requestToken(): Promise<TokenizationResult> {
    const result = await this.adapter.tokenize();
    this.tokenized.emit(result);
    return result;
  }

  ngOnDestroy(): void {
    this.subs.forEach((s) => s.unsubscribe());
    this.adapter.unmount();
  }
}

import { Component, Input, Output, EventEmitter, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Reusable error state component for displaying errors gracefully.
 *
 * Provides:
 * - Multiple error type variants (network, auth, not-found, server, generic)
 * - Retry functionality
 * - Accessible error messages
 * - Bilingual support (Serbian/English)
 *
 * @example
 * <app-error-state
 *   type="network"
 *   [retryable]="true"
 *   (retry)="loadData()"
 * />
 *
 * @since Phase 9.0 - UX Edge Cases
 */
@Component({
  selector: 'app-error-state',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="error-state" [class]="type" role="alert" aria-live="assertive">
      <!-- Error Icon -->
      <div class="error-icon" aria-hidden="true">
        @switch (type) {
          @case ('network') {
            <svg viewBox="0 0 24 24">
              <path
                fill="currentColor"
                d="M20.12 6.71l-2.83-2.83A1 1 0 0016.59 4H7.41a1 1 0 00-.7.29L3.88 7.12A3 3 0 003 9.24v8.26A2.5 2.5 0 005.5 20h13a2.5 2.5 0 002.5-2.5V9.24a3 3 0 00-.88-2.12zM12 17a4 4 0 110-8 4 4 0 010 8z"
              />
              <line x1="1" y1="1" x2="23" y2="23" stroke="currentColor" stroke-width="2" />
            </svg>
          }
          @case ('auth') {
            <svg viewBox="0 0 24 24">
              <path
                fill="currentColor"
                d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 10.99h7c-.53 4.12-3.28 7.79-7 8.94V12H5V6.3l7-3.11v8.8z"
              />
              <circle cx="12" cy="12" r="3" fill="currentColor" />
            </svg>
          }
          @case ('not-found') {
            <svg viewBox="0 0 24 24">
              <path
                fill="currentColor"
                d="M15.5 14h-.79l-.28-.27A6.471 6.471 0 0016 9.5 6.5 6.5 0 109.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"
              />
              <line x1="7" y1="9.5" x2="12" y2="9.5" stroke="currentColor" stroke-width="2" />
            </svg>
          }
          @case ('server') {
            <svg viewBox="0 0 24 24">
              <path
                fill="currentColor"
                d="M20 13H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zM7 19c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zM20 3H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1V4c0-.55-.45-1-1-1zM7 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z"
              />
              <line x1="4" y1="12" x2="20" y2="12" stroke="currentColor" stroke-width="2" />
            </svg>
          }
          @case ('empty') {
            <svg viewBox="0 0 24 24">
              <path
                fill="currentColor"
                d="M20 6h-8l-2-2H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 12H4V6h5.17l2 2H20v10z"
              />
            </svg>
          }
          @default {
            <svg viewBox="0 0 24 24">
              <path
                fill="currentColor"
                d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z"
              />
            </svg>
          }
        }
      </div>

      <!-- Error Title -->
      <h2 class="error-title">{{ getTitle() }}</h2>

      <!-- Error Message -->
      <p class="error-message">{{ customMessage || getMessage() }}</p>

      <!-- Technical Details (collapsible) -->
      @if (errorCode || technicalDetails) {
        <details class="error-details">
          <summary>Tehnički detalji</summary>
          <div class="details-content">
            @if (errorCode) {
              <p><strong>Kod greške:</strong> {{ errorCode }}</p>
            }
            @if (technicalDetails) {
              <p>{{ technicalDetails }}</p>
            }
          </div>
        </details>
      }

      <!-- Action Buttons -->
      <div class="error-actions">
        @if (retryable) {
          <button
            class="btn-primary"
            (click)="onRetry()"
            [attr.aria-label]="'Pokušaj ponovo: ' + getTitle()"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path
                fill="currentColor"
                d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"
              />
            </svg>
            Pokušaj ponovo
          </button>
        }

        @if (showHomeButton) {
          <a class="btn-secondary" href="/" aria-label="Vrati se na početnu stranicu">
            Početna stranica
          </a>
        }

        @if (showContactButton) {
          <button class="btn-secondary" (click)="onContact()">Kontaktiraj podršku</button>
        }
      </div>

      <!-- Help Text -->
      @if (helpText) {
        <p class="help-text">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path
              fill="currentColor"
              d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 17h-2v-2h2v2zm2.07-7.75l-.9.92C13.45 12.9 13 13.5 13 15h-2v-.5c0-1.1.45-2.1 1.17-2.83l1.24-1.26c.37-.36.59-.86.59-1.41 0-1.1-.9-2-2-2s-2 .9-2 2H8c0-2.21 1.79-4 4-4s4 1.79 4 4c0 .88-.36 1.68-.93 2.25z"
            />
          </svg>
          {{ helpText }}
        </p>
      }
    </div>
  `,
  styles: [
    `
      .error-state {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        text-align: center;
        padding: 48px 24px;
        min-height: 300px;
      }

      .error-icon {
        width: 80px;
        height: 80px;
        margin-bottom: 24px;

        svg {
          width: 100%;
          height: 100%;
        }
      }

      /* Color variations based on type */
      .network .error-icon {
        color: #f57c00;
      }
      .auth .error-icon {
        color: #d32f2f;
      }
      .not-found .error-icon {
        color: var(--brand-primary);
      }
      .server .error-icon {
        color: #d32f2f;
      }
      .empty .error-icon {
        color: #9e9e9e;
      }
      .generic .error-icon {
        color: #f57c00;
      }

      .error-title {
        font-size: 24px;
        font-weight: 600;
        margin: 0 0 12px;
        color: #333;
      }

      .error-message {
        font-size: 16px;
        color: #666;
        margin: 0 0 24px;
        max-width: 400px;
        line-height: 1.5;
      }

      .error-details {
        margin-bottom: 24px;
        background: #f5f5f5;
        border-radius: 8px;
        padding: 8px 16px;
        max-width: 400px;
        text-align: left;

        summary {
          cursor: pointer;
          font-size: 14px;
          color: #666;

          &:hover {
            color: #333;
          }
        }

        .details-content {
          margin-top: 12px;
          font-size: 13px;
          color: #888;
          font-family: monospace;

          p {
            margin: 4px 0;
          }
        }
      }

      .error-actions {
        display: flex;
        gap: 12px;
        flex-wrap: wrap;
        justify-content: center;
      }

      .btn-primary,
      .btn-secondary {
        display: inline-flex;
        align-items: center;
        gap: 8px;
        padding: 12px 24px;
        border-radius: 8px;
        font-size: 14px;
        font-weight: 500;
        cursor: pointer;
        text-decoration: none;
        transition: all 0.2s ease;

        svg {
          width: 18px;
          height: 18px;
        }
      }

      .btn-primary {
        background: var(--brand-primary);
        color: white;
        border: none;

        &:hover {
          background: #1565c0;
        }

        &:focus {
          outline: 2px solid var(--brand-primary);
          outline-offset: 2px;
        }
      }

      .btn-secondary {
        background: white;
        color: #333;
        border: 1px solid #ddd;

        &:hover {
          background: #f5f5f5;
          border-color: #ccc;
        }

        &:focus {
          outline: 2px solid var(--brand-primary);
          outline-offset: 2px;
        }
      }

      .help-text {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-top: 24px;
        font-size: 13px;
        color: #888;

        svg {
          width: 16px;
          height: 16px;
          flex-shrink: 0;
        }
      }

      /* Dark mode support */
      @media (prefers-color-scheme: dark) {
        .error-title {
          color: #eee;
        }

        .error-message {
          color: #aaa;
        }

        .error-details {
          background: #2a2a2a;

          summary {
            color: #aaa;

            &:hover {
              color: #eee;
            }
          }

          .details-content {
            color: #888;
          }
        }

        .btn-secondary {
          background: #333;
          color: #eee;
          border-color: #555;

          &:hover {
            background: #444;
          }
        }

        .help-text {
          color: #888;
        }
      }

      /* High contrast mode */
      @media (prefers-contrast: high) {
        .btn-primary,
        .btn-secondary {
          border: 2px solid currentColor;
        }
      }

      /* Mobile responsive */
      @media (max-width: 480px) {
        .error-state {
          padding: 32px 16px;
        }

        .error-icon {
          width: 60px;
          height: 60px;
        }

        .error-title {
          font-size: 20px;
        }

        .error-actions {
          flex-direction: column;
          width: 100%;
        }

        .btn-primary,
        .btn-secondary {
          width: 100%;
          justify-content: center;
        }
      }
    `,
  ],
})
export class ErrorStateComponent {
  @Input() type: 'network' | 'auth' | 'not-found' | 'server' | 'empty' | 'generic' = 'generic';
  @Input() customMessage?: string;
  @Input() errorCode?: string;
  @Input() technicalDetails?: string;
  @Input() helpText?: string;
  @Input() retryable: boolean = true;
  @Input() showHomeButton: boolean = false;
  @Input() showContactButton: boolean = false;

  @Output() retry = new EventEmitter<void>();
  @Output() contact = new EventEmitter<void>();

  private readonly titles: Record<string, string> = {
    network: 'Nema internet konekcije',
    auth: 'Pristup odbijen',
    'not-found': 'Stranica nije pronađena',
    server: 'Greška servera',
    empty: 'Nema podataka',
    generic: 'Došlo je do greške',
  };

  private readonly messages: Record<string, string> = {
    network: 'Proverite vašu internet konekciju i pokušajte ponovo.',
    auth: 'Nemate dozvolu za pristup ovom sadržaju. Molimo prijavite se ili kontaktirajte podršku.',
    'not-found': 'Stranica koju tražite ne postoji ili je premeštena.',
    server: 'Naš server trenutno ima problema. Molimo pokušajte ponovo za nekoliko minuta.',
    empty: 'Nema dostupnih podataka za prikaz.',
    generic: 'Nešto je pošlo po zlu. Molimo pokušajte ponovo.',
  };

  getTitle(): string {
    return this.titles[this.type] || this.titles['generic'];
  }

  getMessage(): string {
    return this.messages[this.type] || this.messages['generic'];
  }

  onRetry(): void {
    this.retry.emit();
  }

  onContact(): void {
    this.contact.emit();
  }
}
import {
  Component,
  ChangeDetectionStrategy,
  DestroyRef,
  ElementRef,
  ViewChild,
  signal,
  OnInit,
  inject,
  input,
} from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { RouterLink } from '@angular/router';
import { animate, state, style, transition, trigger } from '@angular/animations';
import { EMPTY, catchError } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';

type CookieConsentRenderMode = 'fixed' | 'inline';

/**
 * Cookie Consent Banner Component
 *
 * GDPR-compliant cookie consent implementation:
 * - Essential cookies always enabled (no opt-out)
 * - Opt-in for analytics and marketing cookies
 * - Persistent preference storage in localStorage
 * - Shows banner on first visit or when preferences need refresh
 *
 * Cookie categories:
 * - Essential: Auth tokens, CSRF, session management (required)
 * - Analytics: Usage tracking for service improvement (optional)
 * - Marketing: Ad targeting, email preferences (optional)
 */
@Component({
  selector: 'app-cookie-consent',
  standalone: true,
  imports: [CommonModule, MatButtonModule, MatIconModule, MatSlideToggleModule, RouterLink],
  template: `
    @if (showBanner() && !suspended()) {
      <div
        #banner
        class="cookie-banner"
        [class.cookie-banner--fixed]="renderMode() === 'fixed'"
        [class.cookie-banner--inline]="renderMode() === 'inline'"
        [@slideUp]
        role="region"
        aria-label="Podešavanja kolačića"
      >
        <div class="banner-content">
          <div class="banner-icon">
            <mat-icon>cookie</mat-icon>
          </div>

          <div class="banner-text">
            <h3>Kolačići (Cookies)</h3>
            <p>
              Koristimo kolačiće za poboljšanje vašeg iskustva na sajtu. Možete prilagoditi svoje
              postavke ili prihvatiti sve.
            </p>
            <a routerLink="/legal/privacy-policy" class="privacy-link"> Politika privatnosti </a>
          </div>

          @if (showSettings()) {
            <div class="cookie-settings">
              <div class="setting-item essential">
                <mat-slide-toggle [checked]="true" [disabled]="true">
                  <span class="setting-label">Neophodni</span>
                </mat-slide-toggle>
                <span class="setting-desc">Potrebni za rad sajta (uvek uključeni)</span>
              </div>

              <div class="setting-item">
                <mat-slide-toggle
                  [checked]="analyticsEnabled()"
                  (change)="analyticsEnabled.set($event.checked)"
                >
                  <span class="setting-label">Analitički</span>
                </mat-slide-toggle>
                <span class="setting-desc">Pomažu nam da poboljšamo sajt</span>
              </div>

              <div class="setting-item">
                <mat-slide-toggle
                  [checked]="marketingEnabled()"
                  (change)="marketingEnabled.set($event.checked)"
                >
                  <span class="setting-label">Marketing</span>
                </mat-slide-toggle>
                <span class="setting-desc">Personalizovane preporuke</span>
              </div>
            </div>
          }

          <div class="banner-actions">
            @if (!showSettings()) {
              <button mat-stroked-button (click)="showSettings.set(true)">Prilagodi</button>
            }
            <button mat-stroked-button (click)="rejectAll()">Samo neophodni</button>
            <button mat-raised-button color="primary" (click)="acceptSelected()">
              {{ showSettings() ? 'Sačuvaj izbor' : 'Prihvati sve' }}
            </button>
          </div>
        </div>
      </div>
    }
  `,
  styles: [
    `
      .cookie-banner {
        background: white;
        box-shadow: 0 -4px 20px rgba(0, 0, 0, 0.15);
        padding: 16px 24px;
        border-top: 3px solid var(--brand-primary);
      }

      .cookie-banner--fixed {
        position: fixed;
        left: 0;
        right: 0;
        bottom: 0;
        z-index: 950;
        padding-bottom: calc(16px + env(safe-area-inset-bottom, 0px));
      }

      .cookie-banner--inline {
        position: relative;
        z-index: auto;
        box-shadow: 0 10px 30px rgba(15, 23, 42, 0.08);
        border-top: 0;
        border-bottom: 3px solid var(--brand-primary);
      }

      .banner-content {
        max-width: 1200px;
        margin: 0 auto;
        display: flex;
        align-items: flex-start;
        gap: 16px;
        flex-wrap: wrap;
      }

      .banner-icon {
        background: #f5f5f5;
        border-radius: 50%;
        padding: 12px;
        mat-icon {
          color: var(--brand-primary);
          font-size: 28px;
          width: 28px;
          height: 28px;
        }
      }

      .banner-text {
        flex: 1;
        min-width: 250px;

        h3 {
          margin: 0 0 8px;
          font-size: 18px;
          font-weight: 600;
        }

        p {
          margin: 0 0 8px;
          color: #666;
          font-size: 14px;
          line-height: 1.5;
        }

        .privacy-link {
          color: var(--brand-primary);
          font-size: 14px;
          text-decoration: none;
          &:hover {
            text-decoration: underline;
          }
        }
      }

      .cookie-settings {
        width: 100%;
        display: flex;
        gap: 24px;
        padding: 16px 0;
        flex-wrap: wrap;

        .setting-item {
          display: flex;
          flex-direction: column;
          gap: 4px;

          .setting-label {
            font-weight: 500;
            margin-left: 8px;
          }

          .setting-desc {
            font-size: 12px;
            color: #888;
            margin-left: 48px;
          }

          &.essential {
            opacity: 0.7;
          }
        }
      }

      .banner-actions {
        display: flex;
        gap: 12px;
        align-items: center;
        margin-left: auto;

        button {
          white-space: nowrap;
        }
      }

      @media (max-width: 768px) {
        .cookie-banner {
          padding: 12px 16px;
        }

        .cookie-banner--fixed {
          padding-bottom: calc(12px + env(safe-area-inset-bottom, 0px));
        }

        .banner-content {
          flex-direction: column;
        }

        .banner-actions {
          width: 100%;
          justify-content: flex-end;
          flex-wrap: wrap;
        }

        .cookie-settings {
          flex-direction: column;
          gap: 16px;
        }
      }
    `,
  ],
  animations: [
    trigger('slideUp', [
      transition(':enter', [
        style({ transform: 'translateY(100%)' }),
        animate('300ms ease-out', style({ transform: 'translateY(0)' })),
      ]),
      transition(':leave', [animate('300ms ease-in', style({ transform: 'translateY(100%)' }))]),
    ]),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CookieConsentComponent implements OnInit {
  private readonly STORAGE_KEY = 'rentoza_cookie_consent';
  private readonly CONSENT_VERSION = 1; // Increment to re-show banner
  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);

  @ViewChild('banner') private bannerElement?: ElementRef<HTMLElement>;

  readonly renderMode = input<CookieConsentRenderMode>('fixed');
  readonly suspended = input(false);

  readonly showBanner = signal(false);
  readonly showSettings = signal(false);
  readonly analyticsEnabled = signal(false);
  readonly marketingEnabled = signal(false);

  private resizeObserver: ResizeObserver | null = null;
  private observedBanner: HTMLElement | null = null;
  private reservedSpace = 0;

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.disconnectResizeObserver();
      this.setReservedSpace(0);
    });
  }

  ngOnInit(): void {
    this.checkConsentStatus();
  }

  ngAfterViewChecked(): void {
    this.syncReservedSpace();
  }

  private checkConsentStatus(): void {
    const stored = this.readStoredConsent();

    if (!stored) {
      this.showBanner.set(true);
      return;
    }

    try {
      const consent = JSON.parse(stored);

      // Re-show if consent version changed
      if (consent.version !== this.CONSENT_VERSION) {
        this.showBanner.set(true);
        return;
      }

      // Apply stored preferences
      this.analyticsEnabled.set(consent.analytics ?? false);
      this.marketingEnabled.set(consent.marketing ?? false);

      // Update tracking services based on consent
      this.applyConsentPreferences(consent);
    } catch {
      this.showBanner.set(true);
    }
  }

  acceptSelected(): void {
    const consent = {
      version: this.CONSENT_VERSION,
      essential: true, // Always true
      analytics: this.showSettings() ? this.analyticsEnabled() : true,
      marketing: this.showSettings() ? this.marketingEnabled() : true,
      timestamp: new Date().toISOString(),
    };

    this.saveConsent(consent);
  }

  rejectAll(): void {
    const consent = {
      version: this.CONSENT_VERSION,
      essential: true,
      analytics: false,
      marketing: false,
      timestamp: new Date().toISOString(),
    };

    this.saveConsent(consent);
  }

  private saveConsent(consent: CookieConsent): void {
    try {
      localStorage.setItem(this.STORAGE_KEY, JSON.stringify(consent));
    } catch {
      // Keep the current session usable even if storage is unavailable.
    }

    this.applyConsentPreferences(consent);
    this.showBanner.set(false);
    this.showSettings.set(false);
    this.setReservedSpace(0);

    // Also sync to backend for GDPR records
    this.syncConsentToBackend(consent);
  }

  private applyConsentPreferences(consent: CookieConsent): void {
    // Enable/disable Google Analytics
    if (typeof window !== 'undefined' && (window as any).gtag) {
      (window as any).gtag('consent', 'update', {
        analytics_storage: consent.analytics ? 'granted' : 'denied',
        ad_storage: consent.marketing ? 'granted' : 'denied',
      });
    }

    // Disable tracking scripts if not consented
    if (!consent.analytics) {
      // Disable any analytics tracking
      console.log('[CookieConsent] Analytics tracking disabled');
    }

    if (!consent.marketing) {
      // Disable any marketing tracking
      console.log('[CookieConsent] Marketing tracking disabled');
    }
  }

  private syncConsentToBackend(consent: CookieConsent): void {
    // Gate: only sync when user is authenticated — avoids 401 noise from anonymous sessions.
    if (!this.authService.isAuthenticated()) {
      return;
    }

    // Best-effort sync via HttpClient — interceptors handle auth and XSRF automatically.
    // No blocking; errors are silently ignored (consent is saved locally regardless).
    this.http
      .put('/api/users/me/consent', {
        marketingEmails: consent.marketing,
        smsNotifications: consent.marketing,
        analyticsTracking: consent.analytics,
        thirdPartySharing: consent.marketing,
      })
      .pipe(catchError(() => EMPTY))
      .subscribe();
  }

  private readStoredConsent(): string | null {
    try {
      return localStorage.getItem(this.STORAGE_KEY);
    } catch {
      return null;
    }
  }

  private syncReservedSpace(): void {
    const shouldReserveSpace = this.showBanner() && !this.suspended() && this.renderMode() === 'fixed';

    if (!shouldReserveSpace) {
      this.disconnectResizeObserver();
      this.setReservedSpace(0);
      return;
    }

    const banner = this.bannerElement?.nativeElement;
    if (!banner) {
      return;
    }

    this.observeBannerSize(banner);
    this.setReservedSpace(Math.ceil(banner.getBoundingClientRect().height));
  }

  private observeBannerSize(banner: HTMLElement): void {
    if (this.observedBanner === banner || typeof ResizeObserver === 'undefined') {
      return;
    }

    this.disconnectResizeObserver();
    this.observedBanner = banner;
    this.resizeObserver = new ResizeObserver(() => {
      this.setReservedSpace(Math.ceil(banner.getBoundingClientRect().height));
    });
    this.resizeObserver.observe(banner);
  }

  private disconnectResizeObserver(): void {
    this.resizeObserver?.disconnect();
    this.resizeObserver = null;
    this.observedBanner = null;
  }

  private setReservedSpace(value: number): void {
    if (this.reservedSpace === value) {
      return;
    }

    this.reservedSpace = value;
    this.document.documentElement.style.setProperty('--cookie-consent-offset', `${value}px`);
  }
}

interface CookieConsent {
  version: number;
  essential: boolean;
  analytics: boolean;
  marketing: boolean;
  timestamp: string;
}
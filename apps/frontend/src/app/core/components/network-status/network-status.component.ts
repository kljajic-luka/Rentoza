import { Component, inject, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { animate, style, transition, trigger } from '@angular/animations';
import { OfflineModeService } from '../../services/offline-mode.service';

/**
 * Network status indicator component.
 *
 * Displays:
 * - Offline banner when disconnected
 * - Syncing indicator when reconnected
 * - Pending operations count
 *
 * Accessibility:
 * - Uses ARIA live regions for screen reader announcements
 * - High contrast colors for visibility
 * - Keyboard focusable
 *
 * @since Phase 9.0 - UX Edge Cases
 */
@Component({
  selector: 'app-network-status',
  standalone: true,
  imports: [CommonModule],
  template: `
    <!-- Offline Banner -->
    @if (showBanner()) {
      <div
        class="network-status-banner"
        [class.offline]="status() === 'offline'"
        [class.syncing]="status() === 'syncing'"
        [class.pending]="status() === 'pending'"
        role="alert"
        aria-live="polite"
        aria-atomic="true"
        [@slideDown]
      >
        <div class="banner-content">
          <!-- Offline State -->
          @if (status() === 'offline') {
            <svg class="icon" viewBox="0 0 24 24" aria-hidden="true">
              <path
                fill="currentColor"
                d="M20.12 6.71l-2.83-2.83A1 1 0 0016.59 4H7.41a1 1 0 00-.7.29L3.88 7.12A3 3 0 003 9.24v8.26A2.5 2.5 0 005.5 20h13a2.5 2.5 0 002.5-2.5V9.24a3 3 0 00-.88-2.12zM12 17a4 4 0 110-8 4 4 0 010 8zm0-6a2 2 0 100 4 2 2 0 000-4z"
              />
              <line x1="1" y1="1" x2="23" y2="23" stroke="currentColor" stroke-width="2" />
            </svg>
            <span class="message">
              <strong>Niste povezani.</strong>
              Neke funkcije možda neće raditi.
            </span>
          }

          <!-- Syncing State -->
          @if (status() === 'syncing') {
            <svg class="icon spinning" viewBox="0 0 24 24" aria-hidden="true">
              <path
                fill="currentColor"
                d="M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46A7.93 7.93 0 0020 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74A7.93 7.93 0 004 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z"
              />
            </svg>
            <span class="message">
              <strong>Sinhronizacija...</strong>
              Šaljemo sačuvane podatke.
            </span>
          }

          <!-- Pending State -->
          @if (status() === 'pending') {
            <svg class="icon" viewBox="0 0 24 24" aria-hidden="true">
              <path
                fill="currentColor"
                d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z"
              />
            </svg>
            <span class="message">
              <strong>{{ pendingCount() }} operacija na čekanju.</strong>
              Biće poslate kada se povežete.
            </span>
          }

          <!-- Action Button -->
          @if (status() !== 'syncing') {
            <button class="action-btn" (click)="handleAction()" [attr.aria-label]="actionLabel()">
              @if (status() === 'offline') {
                Pokušaj ponovo
              } @else if (status() === 'pending') {
                Sinhronizuj sada
              }
            </button>
          }

          <!-- Close Button -->
          <button class="close-btn" (click)="dismissBanner()" aria-label="Zatvori obaveštenje">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path
                fill="currentColor"
                d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"
              />
            </svg>
          </button>
        </div>
      </div>
    }

    <!-- Small Indicator (when banner dismissed but still offline) -->
    @if (!showBanner() && status() !== 'online') {
      <button
        class="mini-indicator"
        [class.offline]="status() === 'offline'"
        [class.pending]="status() === 'pending'"
        (click)="showBannerAgain()"
        [attr.aria-label]="
          status() === 'offline' ? 'Offline - kliknite za detalje' : 'Čekaju sinhronizaciju'
        "
      >
        @if (status() === 'offline') {
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path
              fill="currentColor"
              d="M20.12 6.71l-2.83-2.83A1 1 0 0016.59 4H7.41a1 1 0 00-.7.29L3.88 7.12A3 3 0 003 9.24v8.26A2.5 2.5 0 005.5 20h13a2.5 2.5 0 002.5-2.5V9.24a3 3 0 00-.88-2.12z"
            />
            <line x1="1" y1="1" x2="23" y2="23" stroke="currentColor" stroke-width="2" />
          </svg>
        } @else {
          <span class="pending-badge">{{ pendingCount() }}</span>
        }
      </button>
    }
  `,
  styles: [
    `
      .network-status-banner {
        position: fixed;
        top: 0;
        left: 0;
        right: 0;
        z-index: 9999;
        padding: 12px 16px;
        color: white;
        font-size: 14px;

        &.offline {
          background: linear-gradient(135deg, #d32f2f 0%, #b71c1c 100%);
        }

        &.syncing {
          background: linear-gradient(
            135deg,
            var(--brand-primary) 0%,
            var(--color-primary-hover) 100%
          );
        }

        &.pending {
          background: linear-gradient(135deg, #f57c00 0%, #e65100 100%);
        }
      }

      .banner-content {
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 12px;
        max-width: 1200px;
        margin: 0 auto;
      }

      .icon {
        width: 24px;
        height: 24px;
        flex-shrink: 0;

        &.spinning {
          animation: spin 1s linear infinite;
        }
      }

      @keyframes spin {
        from {
          transform: rotate(0deg);
        }
        to {
          transform: rotate(360deg);
        }
      }

      .message {
        flex: 1;
        text-align: center;

        strong {
          margin-right: 4px;
        }
      }

      .action-btn {
        background: rgba(255, 255, 255, 0.2);
        border: 1px solid rgba(255, 255, 255, 0.5);
        color: white;
        padding: 6px 16px;
        border-radius: 4px;
        font-size: 13px;
        font-weight: 500;
        cursor: pointer;
        transition: all 0.2s ease;
        white-space: nowrap;

        &:hover {
          background: rgba(255, 255, 255, 0.3);
        }

        &:focus {
          outline: 2px solid white;
          outline-offset: 2px;
        }
      }

      .close-btn {
        background: none;
        border: none;
        color: white;
        padding: 4px;
        cursor: pointer;
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;

        svg {
          width: 20px;
          height: 20px;
        }

        &:hover {
          background: rgba(255, 255, 255, 0.2);
        }

        &:focus {
          outline: 2px solid white;
          outline-offset: 2px;
        }
      }

      .mini-indicator {
        position: fixed;
        bottom: 20px;
        right: 20px;
        z-index: 9998;
        width: 48px;
        height: 48px;
        border-radius: 50%;
        border: none;
        cursor: pointer;
        display: flex;
        align-items: center;
        justify-content: center;
        box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
        transition: transform 0.2s ease;

        &.offline {
          background: #d32f2f;
          color: white;
        }

        &.pending {
          background: #f57c00;
          color: white;
        }

        svg {
          width: 24px;
          height: 24px;
        }

        &:hover {
          transform: scale(1.1);
        }

        &:focus {
          outline: 2px solid var(--brand-primary);
          outline-offset: 2px;
        }
      }

      .pending-badge {
        font-size: 16px;
        font-weight: bold;
      }

      /* High contrast mode support */
      @media (prefers-contrast: high) {
        .network-status-banner {
          border-bottom: 2px solid white;
        }

        .action-btn {
          border: 2px solid white;
        }
      }

      /* Reduced motion preference */
      @media (prefers-reduced-motion: reduce) {
        .icon.spinning {
          animation: none;
        }

        .mini-indicator {
          transition: none;
        }
      }

      /* Mobile responsive */
      @media (max-width: 600px) {
        .banner-content {
          flex-wrap: wrap;
          gap: 8px;
        }

        .message {
          order: 1;
          width: 100%;
          margin-bottom: 8px;
        }

        .icon {
          order: 0;
        }

        .action-btn {
          order: 2;
          flex: 1;
        }

        .close-btn {
          order: 3;
        }
      }
    `,
  ],
  animations: [
    trigger('slideDown', [
      transition(':enter', [
        style({ transform: 'translateY(-100%)' }),
        animate('300ms ease-out', style({ transform: 'translateY(0)' })),
      ]),
      transition(':leave', [animate('300ms ease-in', style({ transform: 'translateY(-100%)' }))]),
    ]),
  ],
})
export class NetworkStatusComponent {
  private readonly offlineService = inject(OfflineModeService);

  // ==================== STATE ====================

  private _bannerDismissed = false;

  // ==================== COMPUTED SIGNALS ====================

  readonly status = this.offlineService.connectionStatus;
  readonly pendingCount = this.offlineService.pendingOperations;

  readonly showBanner = computed(() => {
    if (this._bannerDismissed) return false;
    return this.status() !== 'online';
  });

  readonly actionLabel = computed(() => {
    switch (this.status()) {
      case 'offline':
        return 'Pokušaj da se ponovo povežeš';
      case 'pending':
        return 'Sinhronizuj sačuvane podatke';
      default:
        return '';
    }
  });

  // ==================== ACTIONS ====================

  handleAction(): void {
    if (this.status() === 'offline') {
      // Try to check connection by fetching a small resource
      this.checkConnection();
    } else if (this.status() === 'pending') {
      this.offlineService.syncOfflineQueue();
    }
  }

  dismissBanner(): void {
    this._bannerDismissed = true;
  }

  showBannerAgain(): void {
    this._bannerDismissed = false;
  }

  private async checkConnection(): Promise<void> {
    try {
      // Ping a small endpoint to check connectivity
      const response = await fetch('/api/health', {
        method: 'HEAD',
        cache: 'no-store',
      });

      if (response.ok) {
        // Manually trigger online event if fetch succeeds but browser thinks offline
        window.dispatchEvent(new Event('online'));
      }
    } catch {
      console.log('[NetworkStatus] Connection check failed');
    }
  }
}

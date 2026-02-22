import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterModule } from '@angular/router';

@Component({
  selector: 'app-not-found',
  standalone: true,
  imports: [RouterModule],
  template: `
    <div class="not-found-page" role="main">
      <!-- Decorative background shapes -->
      <div class="not-found-bg" aria-hidden="true">
        <div class="bg-circle bg-circle--1"></div>
        <div class="bg-circle bg-circle--2"></div>
      </div>

      <div class="not-found-content">
        <!-- Large 404 -->
        <div class="not-found-code" aria-hidden="true">404</div>

        <!-- Magnifying glass SVG -->
        <div class="not-found-icon" aria-hidden="true">
          <svg viewBox="0 0 80 80" fill="none" xmlns="http://www.w3.org/2000/svg">
            <circle cx="34" cy="34" r="22" stroke="currentColor" stroke-width="5" stroke-linecap="round"/>
            <line x1="50" y1="50" x2="68" y2="68" stroke="currentColor" stroke-width="5" stroke-linecap="round"/>
            <text x="34" y="40" text-anchor="middle" font-size="18" font-weight="bold" fill="currentColor" dy=".1em">?</text>
          </svg>
        </div>

        <!-- Heading -->
        <h1 class="not-found-title">Ova stranica ne postoji</h1>
        <p class="not-found-subtitle">
          Možda je link pogrešan ili je stranica uklonjena.<br />
          Proveri da li si upisao ispravnu adresu.
        </p>

        <!-- CTAs -->
        <div class="not-found-actions">
          <button class="btn btn--ghost" onclick="history.back()" type="button">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true">
              <path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/>
            </svg>
            Nazad
          </button>
          <a class="btn btn--primary" routerLink="/">
            Idi na početnu
          </a>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      :host {
        display: block;
      }

      .not-found-page {
        min-height: 100vh;
        display: flex;
        align-items: center;
        justify-content: center;
        padding: 2rem;
        position: relative;
        overflow: hidden;
        background: var(--color-surface-primary, #ffffff);
      }

      /* Decorative blobs */
      .not-found-bg {
        position: absolute;
        inset: 0;
        pointer-events: none;
        z-index: 0;
      }

      .bg-circle {
        position: absolute;
        border-radius: 50%;
        opacity: 0.06;
        background: var(--color-primary, #6366f1);
      }

      .bg-circle--1 {
        width: 480px;
        height: 480px;
        top: -120px;
        right: -120px;
      }

      .bg-circle--2 {
        width: 320px;
        height: 320px;
        bottom: -80px;
        left: -80px;
      }

      .not-found-content {
        position: relative;
        z-index: 1;
        text-align: center;
        max-width: 520px;
        animation: fadeInUp 0.5s ease both;
      }

      .not-found-code {
        font-size: clamp(100px, 20vw, 160px);
        font-weight: 800;
        line-height: 1;
        letter-spacing: -4px;
        background: linear-gradient(135deg, var(--color-primary, #6366f1) 0%, #a78bfa 100%);
        -webkit-background-clip: text;
        -webkit-text-fill-color: transparent;
        background-clip: text;
        opacity: 0.35;
        margin-bottom: -1rem;
        user-select: none;
      }

      .not-found-icon {
        width: 80px;
        height: 80px;
        margin: 0 auto 1.5rem;
        color: var(--color-primary, #6366f1);
        opacity: 0.7;

        svg {
          width: 100%;
          height: 100%;
        }
      }

      .not-found-title {
        font-size: clamp(1.5rem, 4vw, 2rem);
        font-weight: 700;
        color: var(--color-text-primary, #0f172a);
        margin: 0 0 0.75rem;
      }

      .not-found-subtitle {
        font-size: 1rem;
        color: var(--color-text-secondary, #64748b);
        line-height: 1.6;
        margin: 0 0 2.5rem;
      }

      .not-found-actions {
        display: flex;
        gap: 0.75rem;
        justify-content: center;
        flex-wrap: wrap;
      }

      .btn {
        display: inline-flex;
        align-items: center;
        gap: 0.5rem;
        padding: 0.75rem 1.5rem;
        border-radius: 10px;
        font-size: 0.9375rem;
        font-weight: 600;
        cursor: pointer;
        transition: all 0.2s ease;
        text-decoration: none;
        border: none;
        font-family: inherit;

        &:focus-visible {
          outline: 2px solid var(--color-primary, #6366f1);
          outline-offset: 2px;
        }
      }

      .btn--primary {
        background: var(--color-primary, #6366f1);
        color: #fff;

        &:hover {
          background: var(--color-primary-dark, #4f46e5);
          transform: translateY(-1px);
          box-shadow: 0 8px 20px rgba(99, 102, 241, 0.35);
        }
      }

      .btn--ghost {
        background: transparent;
        color: var(--color-text-secondary, #64748b);
        border: 1.5px solid var(--color-border, #e2e8f0);

        &:hover {
          background: var(--color-surface-secondary, #f8fafc);
          border-color: var(--color-text-secondary, #64748b);
        }
      }

      @keyframes fadeInUp {
        from {
          opacity: 0;
          transform: translateY(24px);
        }
        to {
          opacity: 1;
          transform: translateY(0);
        }
      }

      /* Dark mode */
      @media (prefers-color-scheme: dark) {
        .not-found-page {
          background: var(--color-surface-primary, #0f172a);
        }

        .not-found-title {
          color: var(--color-text-primary, #f1f5f9);
        }

        .not-found-subtitle {
          color: var(--color-text-secondary, #94a3b8);
        }

        .btn--ghost {
          border-color: var(--color-border, #334155);
          color: var(--color-text-secondary, #94a3b8);

          &:hover {
            background: var(--color-surface-secondary, #1e293b);
          }
        }
      }

      /* Mobile */
      @media (max-width: 480px) {
        .not-found-actions {
          flex-direction: column;
        }

        .btn {
          justify-content: center;
        }
      }
    `,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotFoundComponent {}

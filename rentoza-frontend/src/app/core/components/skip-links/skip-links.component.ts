import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AccessibilityService } from '../../services/accessibility.service';

/**
 * Skip links component for keyboard navigation accessibility.
 *
 * Provides keyboard users with quick navigation to:
 * - Main content area
 * - Navigation menu
 * - Search functionality (if available)
 *
 * WCAG 2.1 Requirements:
 * - 2.4.1 Bypass Blocks (Level A)
 * - Links become visible on focus for keyboard users
 *
 * @since Phase 9.0 - Accessibility Improvements
 */
@Component({
  selector: 'app-skip-links',
  standalone: true,
  imports: [CommonModule],
  template: `
    <nav class="skip-links" aria-label="Brza navigacija">
      <a
        href="#main-content"
        class="skip-link"
        (click)="skipToMain($event)"
        (keydown.enter)="skipToMain($event)"
      >
        Preskoči na glavni sadržaj
      </a>

      <a
        href="#navigation"
        class="skip-link"
        (click)="skipToNav($event)"
        (keydown.enter)="skipToNav($event)"
      >
        Preskoči na navigaciju
      </a>

      <a
        href="#search"
        class="skip-link"
        (click)="skipToSearch($event)"
        (keydown.enter)="skipToSearch($event)"
      >
        Preskoči na pretragu
      </a>
    </nav>
  `,
  styles: [
    `
      .skip-links {
        position: fixed;
        top: 0;
        left: 0;
        z-index: 10000;
        display: flex;
        gap: 4px;
        padding: 4px;
      }

      .skip-link {
        position: absolute;
        left: -9999px;
        top: 0;
        padding: 12px 24px;
        background: #1976d2;
        color: white;
        text-decoration: none;
        font-weight: 600;
        font-size: 14px;
        border-radius: 0 0 8px 8px;
        box-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
        transition: none; /* Immediate focus for accessibility */

        &:focus {
          position: relative;
          left: 0;
          outline: 3px solid #ffeb3b;
          outline-offset: 2px;
        }

        &:hover {
          background: #1565c0;
        }
      }

      /* High contrast mode */
      @media (prefers-contrast: high) {
        .skip-link {
          border: 2px solid white;

          &:focus {
            outline: 3px solid white;
          }
        }
      }

      /* Dark mode */
      @media (prefers-color-scheme: dark) {
        .skip-link {
          background: #2196f3;

          &:hover {
            background: #1976d2;
          }
        }
      }
    `,
  ],
})
export class SkipLinksComponent {
  private readonly a11yService = inject(AccessibilityService);

  skipToMain(event: Event): void {
    event.preventDefault();
    this.a11yService.skipToMain();
  }

  skipToNav(event: Event): void {
    event.preventDefault();
    this.a11yService.skipToNav();
  }

  skipToSearch(event: Event): void {
    event.preventDefault();
    const search =
      document.querySelector('input[type="search"]') ||
      document.querySelector('[role="search"] input') ||
      document.getElementById('search');

    if (search) {
      this.a11yService.focusElement(search as HTMLElement);
      this.a11yService.announce('Фокус премештен на претрагу');
    }
  }
}

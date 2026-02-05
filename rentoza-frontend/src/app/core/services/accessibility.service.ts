import { Injectable, signal, computed } from '@angular/core';

/**
 * Accessibility service for managing WCAG 2.1 AA compliance.
 *
 * Provides:
 * - Focus management for modals and dynamic content
 * - Skip link functionality
 * - Screen reader announcements
 * - Reduced motion preference detection
 * - High contrast mode support
 * - Keyboard navigation helpers
 *
 * @since Phase 9.0 - Accessibility Improvements
 */
@Injectable({
  providedIn: 'root',
})
export class AccessibilityService {
  // ==================== PREFERENCE SIGNALS ====================

  /** User prefers reduced motion */
  private readonly _prefersReducedMotion = signal<boolean>(
    window.matchMedia('(prefers-reduced-motion: reduce)').matches,
  );

  /** User prefers high contrast */
  private readonly _prefersHighContrast = signal<boolean>(
    window.matchMedia('(prefers-contrast: high)').matches,
  );

  /** User prefers dark mode */
  private readonly _prefersDarkMode = signal<boolean>(
    window.matchMedia('(prefers-color-scheme: dark)').matches,
  );

  /** Current font size multiplier (for zoom) */
  private readonly _fontSizeMultiplier = signal<number>(1);

  // ==================== PUBLIC SIGNALS ====================

  readonly prefersReducedMotion = this._prefersReducedMotion.asReadonly();
  readonly prefersHighContrast = this._prefersHighContrast.asReadonly();
  readonly prefersDarkMode = this._prefersDarkMode.asReadonly();
  readonly fontSizeMultiplier = this._fontSizeMultiplier.asReadonly();

  /** Combined accessibility settings */
  readonly settings = computed(() => ({
    reducedMotion: this._prefersReducedMotion(),
    highContrast: this._prefersHighContrast(),
    darkMode: this._prefersDarkMode(),
    fontScale: this._fontSizeMultiplier(),
  }));

  // ==================== LIVE REGION ====================

  private liveRegion: HTMLElement | null = null;
  private liveRegionAssertive: HTMLElement | null = null;

  // ==================== INITIALIZATION ====================

  constructor() {
    this.initMediaQueryListeners();
    this.createLiveRegions();
    this.loadSavedSettings();
  }

  /**
   * Initialize media query listeners for preference changes.
   */
  private initMediaQueryListeners(): void {
    // Reduced motion
    const motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
    motionQuery.addEventListener('change', (e) => {
      this._prefersReducedMotion.set(e.matches);
    });

    // High contrast
    const contrastQuery = window.matchMedia('(prefers-contrast: high)');
    contrastQuery.addEventListener('change', (e) => {
      this._prefersHighContrast.set(e.matches);
    });

    // Dark mode
    const darkQuery = window.matchMedia('(prefers-color-scheme: dark)');
    darkQuery.addEventListener('change', (e) => {
      this._prefersDarkMode.set(e.matches);
    });
  }

  /**
   * Create ARIA live regions for screen reader announcements.
   */
  private createLiveRegions(): void {
    // Polite announcements (wait for user to finish reading current content)
    this.liveRegion = document.createElement('div');
    this.liveRegion.setAttribute('role', 'status');
    this.liveRegion.setAttribute('aria-live', 'polite');
    this.liveRegion.setAttribute('aria-atomic', 'true');
    this.liveRegion.className = 'sr-only';
    this.liveRegion.id = 'a11y-announcements';
    document.body.appendChild(this.liveRegion);

    // Assertive announcements (interrupt immediately)
    this.liveRegionAssertive = document.createElement('div');
    this.liveRegionAssertive.setAttribute('role', 'alert');
    this.liveRegionAssertive.setAttribute('aria-live', 'assertive');
    this.liveRegionAssertive.setAttribute('aria-atomic', 'true');
    this.liveRegionAssertive.className = 'sr-only';
    this.liveRegionAssertive.id = 'a11y-alerts';
    document.body.appendChild(this.liveRegionAssertive);
  }

  /**
   * Load saved accessibility settings from localStorage.
   */
  private loadSavedSettings(): void {
    try {
      const saved = localStorage.getItem('rentoza_a11y_settings');
      if (saved) {
        const settings = JSON.parse(saved);
        if (settings.fontScale) {
          this._fontSizeMultiplier.set(settings.fontScale);
        }
      }
    } catch {
      // Ignore errors
    }
  }

  // ==================== ANNOUNCEMENTS ====================

  /**
   * Announce a message to screen readers (polite).
   * Use for non-urgent status updates.
   *
   * @param message Message to announce
   * @param delay Delay in ms before clearing (default: 1000)
   */
  announce(message: string, delay: number = 1000): void {
    if (!this.liveRegion) return;

    // Clear previous content first
    this.liveRegion.textContent = '';

    // Small delay to ensure screen reader picks up the change
    setTimeout(() => {
      if (this.liveRegion) {
        this.liveRegion.textContent = message;
      }
    }, 50);

    // Clear after delay
    setTimeout(() => {
      if (this.liveRegion) {
        this.liveRegion.textContent = '';
      }
    }, delay);
  }

  /**
   * Announce an urgent message to screen readers (assertive).
   * Use for errors, alerts, and time-sensitive information.
   *
   * @param message Message to announce
   * @param delay Delay in ms before clearing (default: 3000)
   */
  announceUrgent(message: string, delay: number = 3000): void {
    if (!this.liveRegionAssertive) return;

    this.liveRegionAssertive.textContent = '';

    setTimeout(() => {
      if (this.liveRegionAssertive) {
        this.liveRegionAssertive.textContent = message;
      }
    }, 50);

    setTimeout(() => {
      if (this.liveRegionAssertive) {
        this.liveRegionAssertive.textContent = '';
      }
    }, delay);
  }

  // ==================== FOCUS MANAGEMENT ====================

  /** Stack of previously focused elements for modal focus restoration */
  private focusStack: HTMLElement[] = [];

  /**
   * Save current focus and move focus to a new element.
   * Use when opening modals or overlays.
   *
   * @param element Element to focus
   */
  trapFocus(element: HTMLElement): void {
    // Save currently focused element
    const activeElement = document.activeElement as HTMLElement;
    if (activeElement) {
      this.focusStack.push(activeElement);
    }

    // Focus the new element
    this.focusElement(element);

    // Setup focus trap
    this.setupFocusTrap(element);
  }

  /**
   * Restore focus to the previously focused element.
   * Use when closing modals or overlays.
   */
  restoreFocus(): void {
    const previousElement = this.focusStack.pop();
    if (previousElement && previousElement.focus) {
      previousElement.focus();
    }
  }

  /**
   * Focus an element safely.
   *
   * @param element Element to focus
   * @param options Focus options
   */
  focusElement(element: HTMLElement | null, options?: FocusOptions): void {
    if (!element) return;

    // Make element focusable if it isn't
    if (!element.getAttribute('tabindex')) {
      element.setAttribute('tabindex', '-1');
    }

    element.focus(options);
  }

  /**
   * Focus the first focusable element within a container.
   *
   * @param container Container element
   */
  focusFirstFocusable(container: HTMLElement): void {
    const focusable = this.getFocusableElements(container);
    if (focusable.length > 0) {
      focusable[0].focus();
    }
  }

  /**
   * Get all focusable elements within a container.
   *
   * @param container Container element
   * @returns Array of focusable elements
   */
  getFocusableElements(container: HTMLElement): HTMLElement[] {
    const selector = [
      'a[href]',
      'button:not([disabled])',
      'input:not([disabled])',
      'select:not([disabled])',
      'textarea:not([disabled])',
      '[tabindex]:not([tabindex="-1"])',
      '[contenteditable="true"]',
    ].join(', ');

    const elements = Array.from(container.querySelectorAll<HTMLElement>(selector));
    return elements.filter((el) => {
      // Check visibility
      const style = window.getComputedStyle(el);
      return style.display !== 'none' && style.visibility !== 'hidden';
    });
  }

  /**
   * Setup keyboard focus trap within a container.
   */
  private setupFocusTrap(container: HTMLElement): void {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Tab') return;

      const focusableElements = this.getFocusableElements(container);
      if (focusableElements.length === 0) return;

      const firstElement = focusableElements[0];
      const lastElement = focusableElements[focusableElements.length - 1];

      if (event.shiftKey) {
        // Shift + Tab: go to last element if at first
        if (document.activeElement === firstElement) {
          event.preventDefault();
          lastElement.focus();
        }
      } else {
        // Tab: go to first element if at last
        if (document.activeElement === lastElement) {
          event.preventDefault();
          firstElement.focus();
        }
      }
    };

    container.addEventListener('keydown', handleKeyDown);

    // Store cleanup function
    (container as any).__focusTrapCleanup = () => {
      container.removeEventListener('keydown', handleKeyDown);
    };
  }

  /**
   * Remove focus trap from a container.
   */
  releaseFocusTrap(container: HTMLElement): void {
    const cleanup = (container as any).__focusTrapCleanup;
    if (cleanup) {
      cleanup();
      delete (container as any).__focusTrapCleanup;
    }
  }

  // ==================== SKIP LINKS ====================

  /**
   * Skip to main content area.
   */
  skipToMain(): void {
    const main =
      document.querySelector('main') ||
      document.querySelector('[role="main"]') ||
      document.getElementById('main-content');

    if (main) {
      this.focusElement(main as HTMLElement);
      this.announce('Прескочено на главни садржај');
    }
  }

  /**
   * Skip to navigation.
   */
  skipToNav(): void {
    const nav = document.querySelector('nav') || document.querySelector('[role="navigation"]');

    if (nav) {
      this.focusElement(nav as HTMLElement);
      this.announce('Прескочено на навигацију');
    }
  }

  // ==================== FONT SIZE CONTROL ====================

  /**
   * Increase font size.
   */
  increaseFontSize(): void {
    const current = this._fontSizeMultiplier();
    const newSize = Math.min(current + 0.1, 2.0);
    this.setFontSize(newSize);
    this.announce(`Величина фонта повећана на ${Math.round(newSize * 100)}%`);
  }

  /**
   * Decrease font size.
   */
  decreaseFontSize(): void {
    const current = this._fontSizeMultiplier();
    const newSize = Math.max(current - 0.1, 0.8);
    this.setFontSize(newSize);
    this.announce(`Величина фонта смањена на ${Math.round(newSize * 100)}%`);
  }

  /**
   * Reset font size to default.
   */
  resetFontSize(): void {
    this.setFontSize(1.0);
    this.announce('Величина фонта враћена на подразумевану');
  }

  /**
   * Set font size multiplier.
   */
  private setFontSize(multiplier: number): void {
    this._fontSizeMultiplier.set(multiplier);
    document.documentElement.style.setProperty('--font-scale', multiplier.toString());

    // Save to localStorage
    try {
      const settings = { fontScale: multiplier };
      localStorage.setItem('rentoza_a11y_settings', JSON.stringify(settings));
    } catch {
      // Ignore errors
    }
  }

  // ==================== KEYBOARD HELPERS ====================

  /**
   * Check if an event is an activation key (Enter or Space).
   */
  isActivationKey(event: KeyboardEvent): boolean {
    return event.key === 'Enter' || event.key === ' ';
  }

  /**
   * Check if an event is an escape key.
   */
  isEscapeKey(event: KeyboardEvent): boolean {
    return event.key === 'Escape' || event.key === 'Esc';
  }

  /**
   * Check if an event is an arrow key.
   */
  isArrowKey(event: KeyboardEvent): 'up' | 'down' | 'left' | 'right' | null {
    switch (event.key) {
      case 'ArrowUp':
        return 'up';
      case 'ArrowDown':
        return 'down';
      case 'ArrowLeft':
        return 'left';
      case 'ArrowRight':
        return 'right';
      default:
        return null;
    }
  }

  // ==================== ANIMATION HELPERS ====================

  /**
   * Get appropriate animation duration based on user preferences.
   *
   * @param normalDuration Normal animation duration in ms
   * @returns 0 if reduced motion preferred, otherwise normalDuration
   */
  getAnimationDuration(normalDuration: number): number {
    return this._prefersReducedMotion() ? 0 : normalDuration;
  }

  /**
   * Get CSS transition string respecting reduced motion.
   *
   * @param property CSS property to transition
   * @param duration Duration in ms
   * @param easing Easing function
   */
  getTransition(property: string, duration: number, easing: string = 'ease'): string {
    if (this._prefersReducedMotion()) {
      return 'none';
    }
    return `${property} ${duration}ms ${easing}`;
  }
}

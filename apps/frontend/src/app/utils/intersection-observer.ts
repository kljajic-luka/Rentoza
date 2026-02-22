/**
 * IntersectionObserver utility — Category 4, Interaction 5.4
 *
 * Scroll-triggered entrance animations using the native IntersectionObserver API.
 * - No animation library needed
 * - Auto-disconnects after first trigger (animate ONCE)
 * - Respects prefers-reduced-motion
 * - Supports staggered sibling animations
 *
 * @example
 * // In a component's ngAfterViewInit:
 * const elements = this.el.nativeElement.querySelectorAll('.section');
 * observeEntrance(elements, { stagger: 80 });
 */

export interface EntranceConfig {
  /** translateY start offset in px. Default: 24 */
  translateY?: number;
  /** Animation duration in ms. Default: 400 */
  duration?: number;
  /** Easing. Default: 'cubic-bezier(0.0, 0.0, 0.2, 1)' */
  easing?: string;
  /** Stagger delay between elements in ms. Default: 80 */
  stagger?: number;
  /** IntersectionObserver threshold. Default: 0.1 */
  threshold?: number;
  /** Root margin for observer. Default: '0px 0px -48px 0px' */
  rootMargin?: string;
}

const DEFAULT_CONFIG: Required<EntranceConfig> = {
  translateY: 24,
  duration: 400,
  easing: 'cubic-bezier(0.0, 0.0, 0.2, 1)',
  stagger: 80,
  threshold: 0.1,
  rootMargin: '0px 0px -48px 0px',
};

/**
 * Observe a list of elements and animate them into view on scroll.
 * Returns a cleanup function to disconnect the observer early if needed.
 */
export function observeEntrance(
  elements: NodeListOf<Element> | Element[],
  config: EntranceConfig = {},
): () => void {
  const cfg = { ...DEFAULT_CONFIG, ...config };

  // Respect user motion preferences
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  const elArray = Array.from(elements);

  if (elArray.length === 0) return () => {};

  // Set initial state
  elArray.forEach((el) => {
    const htmlEl = el as HTMLElement;
    htmlEl.style.opacity = '0';
    if (!reducedMotion) {
      htmlEl.style.transform = `translateY(${cfg.translateY}px)`;
    }
    htmlEl.style.willChange = 'opacity, transform';
  });

  const observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;

        const el = entry.target as HTMLElement;
        const index = elArray.indexOf(el);
        const delay = index * cfg.stagger;

        setTimeout(() => {
          el.style.transition = reducedMotion
            ? `opacity ${cfg.duration}ms ease`
            : `opacity ${cfg.duration}ms ${cfg.easing}, transform ${cfg.duration}ms ${cfg.easing}`;
          el.style.opacity = '1';
          if (!reducedMotion) {
            el.style.transform = 'translateY(0)';
          }
          el.style.willChange = 'auto';

          // Disconnect after animating so it never re-triggers
          observer.unobserve(el);
        }, delay);
      });
    },
    {
      threshold: cfg.threshold,
      rootMargin: cfg.rootMargin,
    },
  );

  elArray.forEach((el) => observer.observe(el));

  // Return cleanup function
  return () => observer.disconnect();
}

/**
 * Directive-friendly version: observe a single element.
 */
export function observeSingleEntrance(
  element: Element,
  config: EntranceConfig = {},
  delay = 0,
): () => void {
  const cfg = { ...DEFAULT_CONFIG, ...config };
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  const el = element as HTMLElement;
  el.style.opacity = '0';
  if (!reducedMotion) {
    el.style.transform = `translateY(${cfg.translateY}px)`;
  }
  el.style.willChange = 'opacity, transform';

  const observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;

        setTimeout(() => {
          el.style.transition = reducedMotion
            ? `opacity ${cfg.duration}ms ease`
            : `opacity ${cfg.duration}ms ${cfg.easing}, transform ${cfg.duration}ms ${cfg.easing}`;
          el.style.opacity = '1';
          if (!reducedMotion) {
            el.style.transform = 'translateY(0)';
          }
          el.style.willChange = 'auto';
          observer.disconnect();
        }, delay);
      });
    },
    { threshold: cfg.threshold, rootMargin: cfg.rootMargin },
  );

  observer.observe(el);
  return () => observer.disconnect();
}

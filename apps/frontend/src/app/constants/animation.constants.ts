/**
 * Category 4 – Interaction & Animation Constants
 *
 * Central source of truth for every timing and easing value
 * used across the Rentoza frontend.
 *
 * RULES:
 *  - Only animate: opacity, transform (translate, scale, rotate)
 *  - Never animate: width, height, top, left, margin, padding
 *  - All CSS transitions use these values via CSS custom properties
 *  - Respect prefers-reduced-motion: reduce
 */
export const ANIMATION = {
  // ──────────────── Durations (ms) ────────────────
  MICRO: 150,     // Hover, focus, icon color transitions
  SHORT: 200,     // Fade, small transitions, toast exit
  MEDIUM: 300,    // Modals, important transitions, toast enter
  LONG: 400,      // Page sections, entrance animations

  // ──────────────── Easings ────────────────
  EASE_OUT: 'cubic-bezier(0.0, 0.0, 0.2, 1)',    // Decelerate (entering)
  EASE_IN: 'cubic-bezier(0.4, 0.0, 1, 1)',       // Accelerate (exiting)
  EASE_IN_OUT: 'cubic-bezier(0.4, 0.0, 0.2, 1)', // Standard
  SPRING: 'cubic-bezier(0.34, 1.56, 0.64, 1)',   // Slight overshoot/bounce

  // ──────────────── Stagger delays (ms) ────────────────
  STAGGER_CARDS: 80,
  STAGGER_LIST_ITEMS: 60,

  // ──────────────── Toast ────────────────
  TOAST_DEFAULT_DURATION: 4000,
  TOAST_MAX_VISIBLE: 3,

  // ──────────────── Skeleton ────────────────
  SHIMMER_DURATION: '1.5s',

  // ──────────────── Route transition ────────────────
  ROUTE_FADE_OUT: 150,
  ROUTE_FADE_IN: 150,

  // ──────────────── Loading bar ────────────────
  LOADING_BAR_TRICKLE: 1000,
  LOADING_BAR_COMPLETE: 300,
} as const;
/**
 * Photo Skeleton Component
 *
 * Shimmer loading skeleton that matches the exact aspect ratio (4:3) of photo cards.
 * Eliminates Cumulative Layout Shift (CLS) by reserving space during image loading.
 *
 * ## Usage
 * ```html
 * <!-- Single skeleton -->
 * <app-photo-skeleton></app-photo-skeleton>
 *
 * <!-- With custom aspect ratio -->
 * <app-photo-skeleton aspectRatio="16:9"></app-photo-skeleton>
 *
 * <!-- Grid of skeletons -->
 * <div class="photo-grid">
 *   @for (i of [1,2,3,4]; track i) {
 *     <app-photo-skeleton></app-photo-skeleton>
 *   }
 * </div>
 * ```
 *
 * ## Core Web Vitals Impact
 * - CLS: Prevents layout shifts by maintaining exact dimensions
 * - LCP: Shimmer animation provides visual feedback during load
 */
import { Component, Input, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-photo-skeleton',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div
      class="skeleton-container"
      [style.aspectRatio]="aspectRatioValue"
      [class.rounded]="rounded"
      [class.with-label]="showLabel"
      role="img"
      aria-busy="true"
      [attr.aria-label]="'Učitavanje fotografije'"
    >
      <div class="shimmer"></div>

      @if (showLabel) {
      <div class="skeleton-label">
        <div class="label-shimmer"></div>
      </div>
      } @if (showIcon) {
      <div class="skeleton-icon">
        <div class="icon-circle"></div>
      </div>
      }
    </div>
  `,
  styles: [
    `
      :host {
        display: block;
        width: 100%;
      }

      .skeleton-container {
        position: relative;
        width: 100%;
        background: var(--skeleton-bg, #e2e8f0);
        border-radius: 12px;
        overflow: hidden;
        aspect-ratio: 4 / 3; /* Default photo aspect ratio */
      }

      .skeleton-container.rounded {
        border-radius: 16px;
      }

      /* Shimmer animation overlay */
      .shimmer {
        position: absolute;
        inset: 0;
        background: linear-gradient(
          90deg,
          transparent 0%,
          rgba(255, 255, 255, 0.4) 50%,
          transparent 100%
        );
        animation: shimmer 1.5s infinite ease-in-out;
        transform: translateX(-100%);
      }

      @keyframes shimmer {
        0% {
          transform: translateX(-100%);
        }
        100% {
          transform: translateX(100%);
        }
      }

      /* Optional label skeleton at bottom */
      .skeleton-label {
        position: absolute;
        bottom: 0;
        left: 0;
        right: 0;
        padding: 12px;
        background: linear-gradient(transparent, rgba(0, 0, 0, 0.3));
      }

      .label-shimmer {
        height: 14px;
        width: 60%;
        background: rgba(255, 255, 255, 0.3);
        border-radius: 4px;
      }

      /* Optional icon placeholder in center */
      .skeleton-icon {
        position: absolute;
        top: 50%;
        left: 50%;
        transform: translate(-50%, -50%);
      }

      .icon-circle {
        width: 48px;
        height: 48px;
        border-radius: 50%;
        background: rgba(255, 255, 255, 0.2);
      }

      /* Dark theme support */
      :host-context(.theme-dark) .skeleton-container {
        background: var(--skeleton-bg-dark, #334155);
      }

      :host-context(.theme-dark) .shimmer {
        background: linear-gradient(
          90deg,
          transparent 0%,
          rgba(255, 255, 255, 0.1) 50%,
          transparent 100%
        );
      }

      /* Reduced motion preference */
      @media (prefers-reduced-motion: reduce) {
        .shimmer {
          animation: none;
          background: rgba(255, 255, 255, 0.2);
          transform: none;
        }
      }
    `,
  ],
})
export class PhotoSkeletonComponent {
  /**
   * Aspect ratio in "width:height" format.
   * Default: "4:3" (standard photo aspect ratio)
   */
  @Input() aspectRatio: string = '4:3';

  /**
   * Whether to apply more rounded corners.
   */
  @Input() rounded: boolean = false;

  /**
   * Whether to show a label placeholder at the bottom.
   */
  @Input() showLabel: boolean = false;

  /**
   * Whether to show an icon placeholder in the center.
   */
  @Input() showIcon: boolean = false;

  /**
   * Convert aspect ratio string to CSS value.
   */
  get aspectRatioValue(): string {
    const [width, height] = this.aspectRatio.split(':').map(Number);
    if (!width || !height) return '4 / 3';
    return `${width} / ${height}`;
  }
}

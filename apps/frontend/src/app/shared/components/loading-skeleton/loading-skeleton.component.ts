import { Component, Input, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Reusable loading skeleton component for content placeholders.
 *
 * Provides smooth loading animations while content is being fetched,
 * improving perceived performance and user experience.
 *
 * @example
 * <app-loading-skeleton type="card" [count]="3" />
 * <app-loading-skeleton type="text" [lines]="5" />
 * <app-loading-skeleton type="image" [width]="300" [height]="200" />
 *
 * @since Phase 9.0 - UX Edge Cases
 */
@Component({
  selector: 'app-loading-skeleton',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div
      class="skeleton-container"
      [class]="type"
      [attr.aria-busy]="true"
      [attr.aria-label]="'Učitavanje ' + ariaLabel"
      role="status"
    >
      @switch (type) {
        @case ('card') {
          @for (i of countArray; track i) {
            <div class="skeleton-card">
              <div class="skeleton-image pulse"></div>
              <div class="skeleton-content">
                <div class="skeleton-title pulse"></div>
                <div class="skeleton-text pulse"></div>
                <div class="skeleton-text short pulse"></div>
              </div>
            </div>
          }
        }

        @case ('text') {
          @for (i of linesArray; track i) {
            <div class="skeleton-line pulse" [style.width]="getLineWidth(i)"></div>
          }
        }

        @case ('image') {
          <div
            class="skeleton-image-block pulse"
            [style.width.px]="width"
            [style.height.px]="height"
          ></div>
        }

        @case ('avatar') {
          <div
            class="skeleton-avatar pulse"
            [class.small]="size === 'small'"
            [class.large]="size === 'large'"
          ></div>
        }

        @case ('button') {
          <div class="skeleton-button pulse"></div>
        }

        @case ('list') {
          @for (i of countArray; track i) {
            <div class="skeleton-list-item">
              <div class="skeleton-avatar small pulse"></div>
              <div class="skeleton-list-content">
                <div class="skeleton-line short pulse"></div>
                <div class="skeleton-line pulse"></div>
              </div>
            </div>
          }
        }

        @case ('table') {
          <div class="skeleton-table">
            <div class="skeleton-table-header pulse"></div>
            @for (i of countArray; track i) {
              <div class="skeleton-table-row">
                @for (j of [1, 2, 3, 4]; track j) {
                  <div class="skeleton-table-cell pulse"></div>
                }
              </div>
            }
          </div>
        }

        @default {
          <div class="skeleton-block pulse" [style.height.px]="height"></div>
        }
      }

      <span class="visually-hidden">Učitavanje sadržaja, molimo sačekajte...</span>
    </div>
  `,
  styles: [
    `
      .skeleton-container {
        width: 100%;
      }

      /* Pulse animation */
      .pulse {
        animation: pulse 1.5s ease-in-out infinite;
        background: linear-gradient(90deg, #f0f0f0 25%, #e0e0e0 50%, #f0f0f0 75%);
        background-size: 200% 100%;
      }

      @keyframes pulse {
        0% {
          background-position: 200% 0;
        }
        100% {
          background-position: -200% 0;
        }
      }

      /* Card skeleton */
      .skeleton-card {
        background: white;
        border-radius: 12px;
        overflow: hidden;
        margin-bottom: 16px;
        box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
      }

      .skeleton-image {
        height: 200px;
        width: 100%;
      }

      .skeleton-content {
        padding: 16px;
      }

      .skeleton-title {
        height: 24px;
        width: 70%;
        margin-bottom: 12px;
        border-radius: 4px;
      }

      .skeleton-text {
        height: 16px;
        width: 100%;
        margin-bottom: 8px;
        border-radius: 4px;

        &.short {
          width: 60%;
        }
      }

      /* Text skeleton */
      .skeleton-line {
        height: 16px;
        margin-bottom: 12px;
        border-radius: 4px;
      }

      /* Image skeleton */
      .skeleton-image-block {
        border-radius: 8px;
      }

      /* Avatar skeleton */
      .skeleton-avatar {
        width: 48px;
        height: 48px;
        border-radius: 50%;

        &.small {
          width: 32px;
          height: 32px;
        }

        &.large {
          width: 80px;
          height: 80px;
        }
      }

      /* Button skeleton */
      .skeleton-button {
        height: 40px;
        width: 120px;
        border-radius: 8px;
      }

      /* List skeleton */
      .skeleton-list-item {
        display: flex;
        align-items: center;
        gap: 12px;
        padding: 12px 0;
        border-bottom: 1px solid #f0f0f0;
      }

      .skeleton-list-content {
        flex: 1;
      }

      /* Table skeleton */
      .skeleton-table {
        width: 100%;
      }

      .skeleton-table-header {
        height: 48px;
        margin-bottom: 8px;
        border-radius: 4px;
      }

      .skeleton-table-row {
        display: flex;
        gap: 8px;
        margin-bottom: 8px;
      }

      .skeleton-table-cell {
        flex: 1;
        height: 40px;
        border-radius: 4px;
      }

      /* Generic block */
      .skeleton-block {
        width: 100%;
        border-radius: 8px;
      }

      /* Accessibility: Hide from screen but keep for assistive tech */
      .visually-hidden {
        position: absolute;
        width: 1px;
        height: 1px;
        padding: 0;
        margin: -1px;
        overflow: hidden;
        clip: rect(0, 0, 0, 0);
        white-space: nowrap;
        border: 0;
      }

      /* Reduced motion preference */
      @media (prefers-reduced-motion: reduce) {
        .pulse {
          animation: none;
          background: #e0e0e0;
        }
      }

      /* Dark mode support */
      @media (prefers-color-scheme: dark) {
        .pulse {
          background: linear-gradient(90deg, #2a2a2a 25%, #3a3a3a 50%, #2a2a2a 75%);
          background-size: 200% 100%;
        }

        .skeleton-card {
          background: #1a1a1a;
          box-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
        }

        .skeleton-list-item {
          border-bottom-color: #333;
        }
      }
    `,
  ],
})
export class LoadingSkeletonComponent {
  @Input() type: 'card' | 'text' | 'image' | 'avatar' | 'button' | 'list' | 'table' | 'block' =
    'block';
  @Input() count: number = 1;
  @Input() lines: number = 3;
  @Input() width: number = 300;
  @Input() height: number = 200;
  @Input() size: 'small' | 'medium' | 'large' = 'medium';
  @Input() ariaLabel: string = 'sadržaja';

  get countArray(): number[] {
    return Array.from({ length: this.count }, (_, i) => i);
  }

  get linesArray(): number[] {
    return Array.from({ length: this.lines }, (_, i) => i);
  }

  getLineWidth(index: number): string {
    // Vary line widths for more natural appearance
    const widths = ['100%', '95%', '85%', '90%', '70%'];
    return widths[index % widths.length];
  }
}
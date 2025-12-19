import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-skeleton-card',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="skeleton-card" [class.compact]="compact">
      <div class="skeleton-header" *ngIf="!compact">
        <div class="skeleton-avatar"></div>
        <div class="skeleton-lines">
          <div class="skeleton-line skeleton-title"></div>
          <div class="skeleton-line skeleton-subtitle"></div>
        </div>
      </div>
      <div class="skeleton-body">
        <div class="skeleton-line" *ngFor="let line of lines"></div>
        <div class="skeleton-line short" *ngIf="lines.length > 0"></div>
      </div>
      <div class="skeleton-actions" *ngIf="showActions">
        <div class="skeleton-button"></div>
        <div class="skeleton-button"></div>
      </div>
    </div>
  `,
  styles: [`
    .skeleton-card {
      padding: 24px;
      background: var(--color-surface);
      border-radius: 16px;
      box-shadow: var(--shadow-soft);
    }

    .skeleton-card.compact {
      padding: 16px;
    }

    .skeleton-header {
      display: flex;
      gap: 16px;
      align-items: center;
      margin-bottom: 20px;
    }

    .skeleton-avatar {
      width: 48px;
      height: 48px;
      border-radius: 50%;
      background: linear-gradient(
        90deg,
        rgba(148, 163, 184, 0.1) 25%,
        rgba(148, 163, 184, 0.2) 50%,
        rgba(148, 163, 184, 0.1) 75%
      );
      background-size: 200% 100%;
      animation: shimmer 1.5s infinite ease-in-out;
      flex-shrink: 0;
    }

    .skeleton-lines {
      flex: 1;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .skeleton-line {
      height: 16px;
      background: linear-gradient(
        90deg,
        rgba(148, 163, 184, 0.1) 25%,
        rgba(148, 163, 184, 0.2) 50%,
        rgba(148, 163, 184, 0.1) 75%
      );
      background-size: 200% 100%;
      animation: shimmer 1.5s infinite ease-in-out;
      border-radius: 4px;
      margin-bottom: 12px;

      &:last-child {
        margin-bottom: 0;
      }
    }

    .skeleton-title {
      height: 20px;
      width: 60%;
      margin-bottom: 8px;
    }

    .skeleton-subtitle {
      height: 14px;
      width: 40%;
    }

    .skeleton-body {
      margin-bottom: 16px;
    }

    .skeleton-line.short {
      width: 40%;
    }

    .skeleton-actions {
      display: flex;
      gap: 12px;
      padding-top: 16px;
      border-top: 1px solid var(--color-border-subtle);
    }

    .skeleton-button {
      height: 36px;
      width: 100px;
      background: linear-gradient(
        90deg,
        rgba(148, 163, 184, 0.1) 25%,
        rgba(148, 163, 184, 0.2) 50%,
        rgba(148, 163, 184, 0.1) 75%
      );
      background-size: 200% 100%;
      animation: shimmer 1.5s infinite ease-in-out;
      border-radius: 8px;
    }

    @keyframes shimmer {
      0% {
        background-position: -200% 0;
      }
      100% {
        background-position: 200% 0;
      }
    }
  `]
})
export class SkeletonCardComponent {
  @Input() lines: number[] = [1, 2, 3];
  @Input() compact = false;
  @Input() showActions = false;
}

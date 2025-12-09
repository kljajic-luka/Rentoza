import {
  Component,
  input,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * TypingIndicatorComponent - Presentational Component
 *
 * Displays an animated "typing..." indicator when another user is typing.
 * Uses CSS animations for smooth dots animation.
 */
@Component({
  selector: 'app-typing-indicator',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="typing-indicator">
      <div class="avatar" *ngIf="userName()">
        <span>{{ initials() }}</span>
      </div>
      <div class="bubble">
        <span class="dot"></span>
        <span class="dot"></span>
        <span class="dot"></span>
      </div>
      <span class="typing-text" *ngIf="userName()">{{ userName() }} is typing</span>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      padding: 8px 0;
    }

    .typing-indicator {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .avatar {
      width: 32px;
      height: 32px;
      border-radius: 50%;
      background: #9b59b6;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;

      span {
        font-size: 12px;
        font-weight: 600;
        color: #ffffff;
        text-transform: uppercase;
      }
    }

    .bubble {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 12px 16px;
      background-color: #f1f3f5;
      border-radius: 18px;
      border-bottom-left-radius: 4px;
    }

    .dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background-color: #888;
      animation: bounce 1.4s infinite ease-in-out both;

      &:nth-child(1) {
        animation-delay: 0s;
      }

      &:nth-child(2) {
        animation-delay: 0.16s;
      }

      &:nth-child(3) {
        animation-delay: 0.32s;
      }
    }

    .typing-text {
      font-size: 13px;
      color: #666;
      font-style: italic;
    }

    @keyframes bounce {
      0%, 80%, 100% {
        transform: scale(0.6);
        opacity: 0.4;
      }
      40% {
        transform: scale(1);
        opacity: 1;
      }
    }

    // Dark theme
    :host-context(.dark-theme) {
      .bubble {
        background-color: #2d2d2d;
      }

      .dot {
        background-color: #888;
      }

      .typing-text {
        color: #888;
      }
    }
  `],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TypingIndicatorComponent {
  userName = input<string>('');

  initials(): string {
    const name = this.userName();
    if (!name) return '';
    const parts = name.split(' ').filter(Boolean);
    if (parts.length >= 2) {
      return parts[0][0] + parts[1][0];
    }
    return name.substring(0, 2);
  }
}

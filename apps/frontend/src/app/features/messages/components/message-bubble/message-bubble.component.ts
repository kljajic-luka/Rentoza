import { Component, input, computed, signal, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

import { MessageDTO } from '@core/models/chat.model';
import { TimeFormatPipe } from '../../shared/pipes/time-format.pipe';
import { resolveAttachmentUrl } from '@shared/utils/media-url.util';
import { environment } from '@environments/environment';

/**
 * MessageBubbleComponent - Presentational Component
 *
 * Displays a single message with:
 * - Bubble styling (own vs other)
 * - Avatar for grouped messages
 * - Timestamp
 * - Delivery status (sent, delivered, read)
 * - Grouping indicators
 */
@Component({
  selector: 'app-message-bubble',
  standalone: true,
  imports: [CommonModule, MatIconModule, MatTooltipModule, TimeFormatPipe],
  template: `
    <div
      class="message-wrapper"
      [class.own]="isOwn()"
      [class.other]="!isOwn()"
      [class.first-in-group]="isFirstInGroup()"
      [class.last-in-group]="isLastInGroup()"
    >
      <!-- Avatar (only for other's messages, first in group) -->
      <div class="avatar-space" *ngIf="!isOwn()">
        <div
          class="avatar"
          *ngIf="isFirstInGroup()"
          [style.background]="senderProfilePicUrl() ? 'transparent' : avatarColor()"
        >
          <img
            *ngIf="senderProfilePicUrl()"
            [src]="senderProfilePicUrl()"
            class="avatar-img"
            alt="Profile"
            (error)="onImageError($event)"
          />
          <span *ngIf="!senderProfilePicUrl()">{{ avatarInitials() }}</span>
        </div>
      </div>

      <!-- Message bubble -->
      <div class="bubble">
        <!-- Sender name (for other's messages, first in group) -->
        <span class="sender-name" *ngIf="!isOwn() && isFirstInGroup() && senderName()">
          {{ senderName() }}
        </span>

        <!-- Message content (XSS protected) -->
        <p class="content">{{ safeContent() }}</p>

        <!-- Attachment rendering -->
        <div class="attachment" *ngIf="resolvedMediaUrl()">
          <!-- Image attachment (visible until load error) -->
          <a
            *ngIf="isImageAttachment() && !imageLoadFailed()"
            [href]="resolvedMediaUrl()!"
            target="_blank"
            rel="noopener noreferrer"
            class="image-attachment"
          >
            <img
              [src]="resolvedMediaUrl()!"
              alt="Prilog slike"
              class="attachment-image"
              loading="lazy"
              (error)="onAttachmentError($event)"
            />
          </a>
          <!-- Fallback: image failed – show Open CTA -->
          <a
            *ngIf="isImageAttachment() && imageLoadFailed()"
            [href]="resolvedMediaUrl()!"
            target="_blank"
            rel="noopener noreferrer"
            class="file-attachment"
          >
            <mat-icon>image</mat-icon>
            <span>Otvori prilog</span>
          </a>
          <!-- PDF/file attachment -->
          <a
            *ngIf="!isImageAttachment()"
            [href]="resolvedMediaUrl()!"
            target="_blank"
            rel="noopener noreferrer"
            class="file-attachment"
          >
            <mat-icon>picture_as_pdf</mat-icon>
            <span>Preuzmi prilog</span>
          </a>
        </div>

        <!-- Meta info -->
        <div class="meta">
          <time class="timestamp">{{ message().timestamp | timeFormat: 'short' }}</time>

          <!-- Status icon (own messages only) -->
          <mat-icon
            *ngIf="isOwn()"
            class="status-icon"
            [class]="statusClass()"
            [matTooltip]="statusTooltip()"
          >
            {{ statusIcon() }}
          </mat-icon>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      :host {
        display: block;
      }

      .message-wrapper {
        display: flex;
        gap: 10px;
        margin-bottom: 2px;
        animation: fadeIn 0.2s ease-out;

        &.first-in-group {
          margin-top: 8px;
        }

        &.own {
          flex-direction: row-reverse;
        }

        &.other {
          flex-direction: row;
        }
      }

      .avatar-space {
        width: 32px;
        flex-shrink: 0;
      }

      .avatar {
        width: 32px;
        height: 32px;
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        overflow: hidden;

        .avatar-img {
          width: 100%;
          height: 100%;
          object-fit: cover;
          border-radius: 50%;
        }

        span {
          font-size: 12px;
          font-weight: 600;
          color: #ffffff;
          text-transform: uppercase;
        }
      }

      .bubble {
        max-width: 70%;
        padding: 10px 14px;
        border-radius: 18px;
        position: relative;
        word-wrap: break-word;
        overflow-wrap: break-word;
      }

      .own .bubble {
        background: linear-gradient(
          135deg,
          var(--brand-primary) 0%,
          var(--color-primary-hover) 100%
        );
        color: #ffffff;
        border-bottom-right-radius: 4px;
        box-shadow: 0 1px 3px rgba(89, 60, 251, 0.2);
      }

      .other .bubble {
        background-color: #f1f3f5;
        color: #1a1a1a;
        border-bottom-left-radius: 4px;
      }

      // Grouping - smooth corners for consecutive messages
      .own:not(.last-in-group) .bubble {
        border-bottom-right-radius: 4px;
        border-top-right-radius: 4px;
      }

      .own:not(.first-in-group) .bubble {
        border-top-right-radius: 4px;
      }

      .other:not(.last-in-group) .bubble {
        border-bottom-left-radius: 4px;
        border-top-left-radius: 4px;
      }

      .other:not(.first-in-group) .bubble {
        border-top-left-radius: 4px;
      }

      .sender-name {
        display: block;
        font-size: 12px;
        font-weight: 600;
        color: #666;
        margin-bottom: 4px;
      }

      .content {
        margin: 0;
        font-size: 15px;
        line-height: 1.45;
        white-space: pre-wrap;
        word-break: break-word;
      }

      .attachment {
        margin-top: 8px;
        border-radius: 8px;
        overflow: hidden;

        .image-attachment {
          display: block;
          cursor: pointer;

          .attachment-image {
            max-width: 100%;
            max-height: 240px;
            border-radius: 8px;
            object-fit: cover;
          }
        }

        .file-attachment {
          display: flex;
          align-items: center;
          gap: 8px;
          padding: 8px 12px;
          background-color: rgba(0, 0, 0, 0.06);
          border-radius: 8px;
          text-decoration: none;
          color: inherit;
          font-size: 13px;

          mat-icon {
            font-size: 20px;
            width: 20px;
            height: 20px;
            color: #d32f2f;
          }

          &:hover {
            background-color: rgba(0, 0, 0, 0.1);
          }
        }
      }

      .meta {
        display: flex;
        align-items: center;
        justify-content: flex-end;
        gap: 4px;
        margin-top: 4px;
      }

      .timestamp {
        font-size: 11px;
        opacity: 0.8;
      }

      .own .timestamp {
        color: rgba(255, 255, 255, 0.8);
      }

      .other .timestamp {
        color: #888;
      }

      .status-icon {
        font-size: 14px;
        width: 14px;
        height: 14px;

        &.sending {
          color: rgba(255, 255, 255, 0.6);
          animation: pulse 1.5s ease-in-out infinite;
        }

        &.sent {
          color: rgba(255, 255, 255, 0.8);
        }

        &.delivered {
          color: rgba(255, 255, 255, 0.9);
        }

        &.read {
          color: #81d4fa;
        }
      }

      @keyframes fadeIn {
        from {
          opacity: 0;
          transform: translateY(4px);
        }
        to {
          opacity: 1;
          transform: translateY(0);
        }
      }

      @keyframes pulse {
        0%,
        100% {
          opacity: 0.4;
        }
        50% {
          opacity: 0.8;
        }
      }

      // Dark theme
      :host-context(.dark-theme) {
        .own .bubble {
          background: linear-gradient(
            135deg,
            var(--brand-primary) 0%,
            var(--color-primary-hover) 100%
          );
        }

        .other .bubble {
          background-color: #2d2d2d;
          color: #e0e0e0;
        }

        .sender-name {
          color: #b0b0b0;
        }

        .other .timestamp {
          color: #888;
        }
      }

      // Responsive
      @media (max-width: 768px) {
        .bubble {
          max-width: 85%;
        }
      }
    `,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MessageBubbleComponent {
  // Inputs
  message = input.required<MessageDTO>();
  isOwn = input.required<boolean>();
  showAvatar = input<boolean>(true);
  isFirstInGroup = input<boolean>(true);
  isLastInGroup = input<boolean>(true);
  senderProfilePicUrl = input<string | null | undefined>(null);

  // Computed properties
  senderName = computed(() => {
    const msg = this.message();
    return msg.senderName || '';
  });

  avatarInitials = computed(() => {
    const name = this.senderName();
    if (!name) return '?';
    const parts = name.split(' ').filter(Boolean);
    if (parts.length >= 2) {
      return parts[0][0] + parts[1][0];
    }
    return name.substring(0, 2);
  });

  avatarColor = computed(() => {
    const colors = [
      '#1abc9c',
      '#2ecc71',
      '#3498db',
      '#9b59b6',
      '#34495e',
      '#16a085',
      '#27ae60',
      '#2980b9',
      '#8e44ad',
      '#2c3e50',
    ];
    const name = this.senderName() || 'X';
    const index = name.charCodeAt(0) % colors.length;
    return colors[index];
  });

  statusIcon = computed(() => {
    const msg = this.message();

    if (msg.readAt || (msg.readBy && msg.readBy.length > 1)) {
      return 'done_all';
    }
    if (msg.deliveredAt) {
      return 'done_all';
    }
    if (msg.sentAt || msg.timestamp) {
      return 'done';
    }
    return 'schedule';
  });

  statusClass = computed(() => {
    const msg = this.message();

    if (msg.readAt || (msg.readBy && msg.readBy.length > 1)) {
      return 'read';
    }
    if (msg.deliveredAt) {
      return 'delivered';
    }
    if (msg.sentAt || msg.timestamp) {
      return 'sent';
    }
    return 'sending';
  });

  statusTooltip = computed(() => {
    const status = this.statusClass();
    const tooltips: Record<string, string> = {
      read: 'Read',
      delivered: 'Delivered',
      sent: 'Sent',
      sending: 'Sending...',
    };
    return tooltips[status] || '';
  });

  // Chat service origin derived once at construction time.
  private readonly chatOrigin = new URL(environment.chatApiUrl).origin;

  // True after the <img> fires an (error) event (e.g. 404, CORS, network).
  imageLoadFailed = signal(false);

  // Fully-qualified URL for the attachment, or null when mediaUrl is absent/unrecognised.
  resolvedMediaUrl = computed(() =>
    resolveAttachmentUrl(this.message().mediaUrl ?? null, this.chatOrigin),
  );

  // Fallback to initials if image fails to load
  onImageError(event: Event): void {
    const img = event.target as HTMLImageElement;
    img.style.display = 'none';
  }

  // Check if attachment is an image based on URL extension
  isImageAttachment(): boolean {
    const url = this.message()?.mediaUrl;
    if (!url) return false;
    const lower = url.toLowerCase();
    return (
      lower.endsWith('.jpg') ||
      lower.endsWith('.jpeg') ||
      lower.endsWith('.png') ||
      lower.endsWith('.gif') ||
      lower.endsWith('.webp')
    );
  }

  // Handle attachment load error — signal the fallback CTA instead of silently hiding.
  onAttachmentError(_event: Event): void {
    this.imageLoadFailed.set(true);
  }

  /**
   * Safely escape message content to prevent XSS attacks.
   */
  safeContent = computed(() => {
    const content = this.message()?.content || '';
    return this.escapeHtml(content);
  });

  /**
   * Escape HTML special characters to prevent XSS attacks.
   * Converts: & < > " ' to HTML entities.
   */
  private escapeHtml(text: string): string {
    const map: Record<string, string> = {
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      '"': '&quot;',
      "'": '&#039;',
    };
    return text.replace(/[&<>"']/g, (char) => map[char]);
  }
}

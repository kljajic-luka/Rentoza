import { Component, input, output, computed, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';

import { ConversationDTO } from '@core/models/chat.model';
import { ChatUiHelper } from '@core/helpers/chat-ui.helper';
import { TimeFormatPipe } from '../../shared/pipes/time-format.pipe';

/**
 * ConversationItemComponent - Presentational Component
 *
 * Displays a single conversation in the list with:
 * - Avatar with initials
 * - Participant name
 * - Car info (year, brand, model)
 * - Last message preview
 * - Timestamp
 * - Unread badge
 * - Trip status indicator
 */
@Component({
  selector: 'app-conversation-item',
  standalone: true,
  imports: [CommonModule, MatIconModule, TimeFormatPipe],
  template: `
    <article
      class="conversation-item"
      [class.selected]="isSelected()"
      [class.has-unread]="conversation().unreadCount > 0"
      (click)="onSelect()"
      (keydown.enter)="onSelect()"
      tabindex="0"
      role="button"
      [attr.aria-label]="'Conversation with ' + participantName()"
    >
      <!-- Avatar -->
      <div class="avatar" [style.background]="profilePicUrl() ? 'transparent' : avatarColor()">
        <img
          *ngIf="profilePicUrl()"
          [src]="profilePicUrl()"
          class="avatar-img"
          alt="Profile"
          (error)="onImageError($event)"
        />
        <span *ngIf="!profilePicUrl()" class="avatar-initials">{{ avatarInitials() }}</span>
      </div>

      <!-- Content -->
      <div class="content">
        <!-- Header row -->
        <div class="header-row">
          <h3 class="participant-name">{{ participantName() }}</h3>
          <time class="timestamp">{{ conversation().lastMessageAt | timeFormat }}</time>
        </div>

        <!-- Car info -->
        <div class="car-info" *ngIf="carInfo()">
          <mat-icon class="car-icon">directions_car</mat-icon>
          <span>{{ carInfo() }}</span>
        </div>

        <!-- Preview row -->
        <div class="preview-row">
          <p class="last-message" *ngIf="lastMessagePreview()">
            {{ lastMessagePreview() }}
          </p>
          <p class="last-message placeholder" *ngIf="!lastMessagePreview()">Nema poruka</p>
        </div>
      </div>

      <!-- Right side indicators -->
      <div class="indicators">
        <!-- Unread badge -->
        <span class="unread-badge" *ngIf="conversation().unreadCount > 0">
          {{ conversation().unreadCount > 99 ? '99+' : conversation().unreadCount }}
        </span>

        <!-- Trip status -->
        <span class="trip-status" [class]="'status-' + tripStatus()">
          {{ tripStatusLabel() }}
        </span>
      </div>
    </article>
  `,
  styles: [
    `
      :host {
        display: block;
      }

      .conversation-item {
        display: flex;
        align-items: flex-start;
        gap: 14px;
        padding: 16px 20px;
        background-color: #ffffff;
        border-bottom: 1px solid #f2f2f2;
        cursor: pointer;
        transition: background-color 0.15s ease;
        min-height: 88px;
        box-sizing: border-box;

        &:hover {
          background-color: #f8f9fa;
        }

        &:focus {
          outline: none;
          background-color: #f8f9fa;
        }

        &.selected {
          background-color: #e3f2fd;
          border-left: 3px solid #2196f3;
          padding-left: 17px;
        }

        &.has-unread {
          background-color: #fafafa;

          .participant-name {
            font-weight: 700;
          }

          .last-message {
            color: #333;
            font-weight: 500;
          }
        }
      }

      .avatar {
        width: 48px;
        height: 48px;
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        flex-shrink: 0;
        box-shadow: 0 2px 4px rgba(0, 0, 0, 0.08);
        overflow: hidden;

        .avatar-img {
          width: 100%;
          height: 100%;
          object-fit: cover;
          border-radius: 50%;
        }

        .avatar-initials {
          font-size: 16px;
          font-weight: 600;
          color: #ffffff;
          text-transform: uppercase;
        }
      }

      .content {
        flex: 1;
        min-width: 0;
        display: flex;
        flex-direction: column;
        gap: 4px;
      }

      .header-row {
        display: flex;
        justify-content: space-between;
        align-items: baseline;
        gap: 12px;
      }

      .participant-name {
        margin: 0;
        font-size: 15px;
        font-weight: 600;
        color: #1a1a1a;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }

      .timestamp {
        font-size: 12px;
        color: #888;
        white-space: nowrap;
        flex-shrink: 0;
      }

      .car-info {
        display: flex;
        align-items: center;
        gap: 4px;
        font-size: 13px;
        color: #666;

        .car-icon {
          font-size: 14px;
          width: 14px;
          height: 14px;
          color: #888;
        }
      }

      .preview-row {
        display: flex;
        align-items: center;
      }

      .last-message {
        margin: 0;
        font-size: 14px;
        color: #666;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        line-height: 1.4;

        &.placeholder {
          font-style: italic;
          color: #999;
        }
      }

      .indicators {
        display: flex;
        flex-direction: column;
        align-items: flex-end;
        gap: 6px;
        flex-shrink: 0;
      }

      .unread-badge {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        min-width: 22px;
        height: 22px;
        padding: 0 6px;
        border-radius: 11px;
        background: #ff6b6b;
        color: white;
        font-size: 11px;
        font-weight: 700;
      }

      .trip-status {
        font-size: 10px;
        padding: 3px 8px;
        border-radius: 4px;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.3px;

        &.status-current {
          background-color: #e8f5e9;
          color: #2e7d32;
        }

        &.status-future {
          background-color: #e3f2fd;
          color: #1565c0;
        }

        &.status-past {
          background-color: #f5f5f5;
          color: #757575;
        }

        &.status-unknown {
          background-color: #fff3e0;
          color: #ef6c00;
        }

        &.status-unavailable {
          background-color: #ffebee;
          color: #c62828;
        }
      }

      // Dark theme support via parent class
      :host-context(.dark-theme) {
        .conversation-item {
          background-color: #1e1e1e;
          border-bottom-color: #333;

          &:hover {
            background-color: #2c2c2c;
          }

          &.selected {
            background-color: #1a365d;
            border-left-color: #64b5f6;
          }

          &.has-unread {
            background-color: #252525;
          }
        }

        .participant-name {
          color: #e0e0e0;
        }

        .timestamp {
          color: #888;
        }

        .car-info {
          color: #b0b0b0;

          .car-icon {
            color: #888;
          }
        }

        .last-message {
          color: #b0b0b0;

          &.placeholder {
            color: #666;
          }
        }

        .trip-status {
          &.status-current {
            background-color: #1b5e20;
            color: #a5d6a7;
          }

          &.status-future {
            background-color: #0d47a1;
            color: #90caf9;
          }

          &.status-past {
            background-color: #333;
            color: #9e9e9e;
          }
        }
      }
    `,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConversationItemComponent {
  // Inputs
  conversation = input.required<ConversationDTO>();
  isSelected = input<boolean>(false);
  currentUserId = input.required<string>();

  // Outputs
  selectConversation = output<ConversationDTO>();

  // Computed properties
  participantName = computed(() => {
    const conv = this.conversation();
    const userId = this.currentUserId();
    // Compare IDs as strings to avoid number/string mismatch from API payloads.
    const isOwner =
      conv.ownerId != null && userId != null ? conv.ownerId.toString() === userId.toString() : false;

    if (isOwner && conv.renterName && conv.renterName !== 'Renter') {
      return conv.renterName;
    } else if (!isOwner && conv.ownerName && conv.ownerName !== 'Owner') {
      return conv.ownerName;
    }

    return isOwner ? 'Driver' : 'Owner';
  });

  avatarInitials = computed(() => {
    const name = this.participantName();
    const parts = name.split(' ').filter(Boolean);
    if (parts.length >= 2) {
      return parts[0][0] + parts[1][0];
    }
    return name.substring(0, 2);
  });

  // Computed: profile picture URL (other participant)
  profilePicUrl = computed(() => {
    const conv = this.conversation();
    const userId = this.currentUserId();
    const isOwner =
      conv.ownerId != null && userId != null ? conv.ownerId.toString() === userId.toString() : false;
    // If current user is owner, show renter's pic; otherwise show owner's pic
    return isOwner ? conv.renterProfilePicUrl : conv.ownerProfilePicUrl;
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
      '#f1c40f',
      '#e67e22',
      '#e74c3c',
      '#00bcd4',
      '#607d8b',
    ];
    const name = this.participantName();
    const index = name.charCodeAt(0) % colors.length;
    return colors[index];
  });

  carInfo = computed(() => {
    const conv = this.conversation();
    if (!conv.carBrand || !conv.carModel || conv.carBrand === 'Unknown') {
      return '';
    }
    const year = conv.carYear && conv.carYear > 0 ? `${conv.carYear} ` : '';
    return `${year}${conv.carBrand} ${conv.carModel}`;
  });

  lastMessagePreview = computed(() => {
    const conv = this.conversation();

    // Primary source: lastMessageContent from backend (for conversation list)
    if (conv.lastMessageContent) {
      const preview =
        conv.lastMessageContent.length > 60
          ? conv.lastMessageContent.substring(0, 60) + '...'
          : conv.lastMessageContent;
      return this.escapeHtml(preview);
    }

    // Fallback: messages array (when conversation is loaded with full messages)
    const messages = conv.messages || [];
    if (messages.length === 0) return '';
    const lastMsg = messages[messages.length - 1];
    const content = lastMsg.content || '';
    const preview = content.length > 60 ? content.substring(0, 60) + '...' : content;
    // Escape HTML to prevent XSS attacks
    return this.escapeHtml(preview);
  });

  tripStatus = computed(() => {
    const conv = this.conversation();
    if (conv.tripStatus) {
      return conv.tripStatus.toLowerCase();
    }

    if (!conv.startTime || !conv.endTime) {
      return 'unknown';
    }

    const now = new Date();
    const start = new Date(conv.startTime);
    const end = new Date(conv.endTime);

    if (now < start) return 'future';
    if (now > end) return 'past';
    return 'current';
  });

  tripStatusLabel = computed(() => {
    const status = this.tripStatus();
    const labels: Record<string, string> = {
      current: 'Aktivna',
      future: 'Predstoji',
      past: 'Završena',
      unknown: 'Na čekanju',
      unavailable: 'Nedostupno',
    };
    return labels[status] || 'Na čekanju';
  });

  // Event handlers
  onSelect(): void {
    this.selectConversation.emit(this.conversation());
  }

  // Fallback to initials if image fails to load
  onImageError(event: Event): void {
    const img = event.target as HTMLImageElement;
    img.style.display = 'none';
  }

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
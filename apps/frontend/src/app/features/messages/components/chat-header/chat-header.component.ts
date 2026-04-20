import { Component, input, output, computed, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';

import { ConversationDTO } from '@core/models/chat.model';

/**
 * ChatHeaderComponent - Presentational Component
 *
 * Displays the header of the chat window with:
 * - Back button (mobile)
 * - Participant info
 * - Car/Booking context card
 * - Trip status badge
 * - Action menu (view booking, report)
 */
@Component({
  selector: 'app-chat-header',
  standalone: true,
  imports: [CommonModule, MatIconModule, MatButtonModule, MatMenuModule, MatTooltipModule],
  template: `
    <header class="chat-header">
      <!-- Mobile back button -->
      <button
        *ngIf="isMobile()"
        mat-icon-button
        class="back-button"
        (click)="goBack.emit()"
        aria-label="Go back to conversations"
      >
        <mat-icon>arrow_back</mat-icon>
      </button>

      <!-- Participant info -->
      <div class="participant-section">
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

        <div class="participant-details">
          <h1 class="participant-name">{{ participantName() }}</h1>
          <div class="trip-context" *ngIf="carInfo()">
            <mat-icon class="context-icon">directions_car</mat-icon>
            <span>{{ carInfo() }}</span>
          </div>
        </div>
      </div>

      <!-- Status and actions -->
      <div class="header-actions">
        <!-- Trip status badge -->
        <span class="trip-status" [class]="'status-' + tripStatus()">
          {{ tripStatusLabel() }}
        </span>

        <!-- Action menu -->
        <button mat-icon-button [matMenuTriggerFor]="actionMenu" aria-label="More options">
          <mat-icon>more_vert</mat-icon>
        </button>

        <mat-menu #actionMenu="matMenu">
          <button mat-menu-item (click)="viewBookingDetails.emit()">
            <mat-icon>receipt_long</mat-icon>
            <span>Pregled detalja rezervacije</span>
          </button>
          <button mat-menu-item (click)="reportUser.emit()">
            <mat-icon>flag</mat-icon>
            <span>Prijavi korisnika</span>
          </button>
        </mat-menu>
      </div>
    </header>

    <!-- Booking context card (optional) -->
    <div class="booking-context-card" *ngIf="showBookingCard() && tripDates()">
      <div class="context-item">
        <mat-icon>event</mat-icon>
        <span>{{ tripDates() }}</span>
      </div>
      <div class="context-item" *ngIf="tripLocation()">
        <mat-icon>location_on</mat-icon>
        <span>{{ tripLocation() }}</span>
      </div>
    </div>
  `,
  styles: [
    `
      :host {
        display: block;
      }

      .chat-header {
        display: flex;
        align-items: center;
        gap: 12px;
        padding: 16px 20px;
        background-color: #ffffff;
        border-bottom: 1px solid #e8e8e8;
        box-shadow: 0 2px 8px rgba(0, 0, 0, 0.02);
      }

      .back-button {
        flex-shrink: 0;
        margin-left: -8px;
      }

      .participant-section {
        display: flex;
        align-items: center;
        gap: 14px;
        flex: 1;
        min-width: 0;
      }

      .avatar {
        width: 44px;
        height: 44px;
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        flex-shrink: 0;
        overflow: hidden;

        .avatar-img {
          width: 100%;
          height: 100%;
          object-fit: cover;
          border-radius: 50%;
        }

        .avatar-initials {
          font-size: 15px;
          font-weight: 600;
          color: #ffffff;
          text-transform: uppercase;
        }
      }

      .participant-details {
        flex: 1;
        min-width: 0;
      }

      .participant-name {
        margin: 0;
        font-size: 17px;
        font-weight: 700;
        color: #1a1a1a;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }

      .trip-context {
        display: flex;
        align-items: center;
        gap: 4px;
        margin-top: 2px;
        font-size: 13px;
        color: #666;

        .context-icon {
          font-size: 14px;
          width: 14px;
          height: 14px;
          color: #888;
        }
      }

      .header-actions {
        display: flex;
        align-items: center;
        gap: 8px;
        flex-shrink: 0;
      }

      .trip-status {
        font-size: 11px;
        padding: 5px 10px;
        border-radius: 12px;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.4px;

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

      .booking-context-card {
        display: flex;
        flex-wrap: wrap;
        gap: 16px;
        padding: 12px 20px;
        background-color: #f8f9fa;
        border-bottom: 1px solid #e8e8e8;
      }

      .context-item {
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: 13px;
        color: #555;

        mat-icon {
          font-size: 16px;
          width: 16px;
          height: 16px;
          color: #666;
        }
      }

      // Dark theme
      :host-context(.dark-theme) {
        .chat-header {
          background-color: #1e1e1e;
          border-bottom-color: #333;
          box-shadow: none;
        }

        .participant-name {
          color: #e0e0e0;
        }

        .trip-context {
          color: #b0b0b0;

          .context-icon {
            color: #888;
          }
        }

        .booking-context-card {
          background-color: #252525;
          border-bottom-color: #333;
        }

        .context-item {
          color: #b0b0b0;

          mat-icon {
            color: #888;
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
export class ChatHeaderComponent {
  // Inputs
  conversation = input.required<ConversationDTO | null>();
  currentUserId = input.required<string>();
  isMobile = input<boolean>(false);

  // Outputs
  goBack = output<void>();
  viewBookingDetails = output<void>();
  reportUser = output<void>();

  // Computed properties
  participantName = computed(() => {
    const conv = this.conversation();
    if (!conv) return '';

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
    if (!conv) return null;
    const userId = this.currentUserId();
    const isOwner =
      conv.ownerId != null && userId != null ? conv.ownerId.toString() === userId.toString() : false;
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
    ];
    const name = this.participantName();
    const index = name.charCodeAt(0) % colors.length;
    return colors[index];
  });

  carInfo = computed(() => {
    const conv = this.conversation();
    if (!conv || !conv.carBrand || conv.carBrand === 'Unknown') {
      return '';
    }
    const year = conv.carYear && conv.carYear > 0 ? `${conv.carYear} ` : '';
    return `${year}${conv.carBrand} ${conv.carModel}`;
  });

  tripStatus = computed(() => {
    const conv = this.conversation();
    if (!conv) return 'unknown';

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
      current: 'Aktivna vožnja',
      future: 'Predstoji',
      past: 'Završena',
      unknown: 'Na čekanju',
      unavailable: 'Nedostupno',
    };
    return labels[status] || 'Na čekanju';
  });

  showBookingCard = computed(() => {
    const conv = this.conversation();
    return conv?.startTime && conv?.endTime;
  });

  tripDates = computed(() => {
    const conv = this.conversation();
    if (!conv?.startTime || !conv?.endTime) return '';

    const start = new Date(conv.startTime);
    const end = new Date(conv.endTime);

    const opts: Intl.DateTimeFormatOptions = { month: 'short', day: 'numeric' };
    return `${start.toLocaleDateString('en-US', opts)} - ${end.toLocaleDateString('en-US', opts)}`;
  });

  tripLocation = computed(() => {
    // TODO: Add location if available in conversation data
    return '';
  });

  // Fallback to initials if image fails to load
  onImageError(event: Event): void {
    const img = event.target as HTMLImageElement;
    img.style.display = 'none';
  }
}
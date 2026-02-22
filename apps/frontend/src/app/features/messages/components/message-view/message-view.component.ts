import {
  Component,
  input,
  signal,
  computed,
  effect,
  ViewChild,
  ElementRef,
  AfterViewInit,
  ChangeDetectionStrategy,
  OnDestroy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ScrollingModule, CdkVirtualScrollViewport } from '@angular/cdk/scrolling';
import { MatIconModule } from '@angular/material/icon';

import { ConversationDTO, MessageDTO } from '@core/models/chat.model';
import { MessageBubbleComponent } from '../message-bubble/message-bubble.component';
import { TypingIndicatorComponent } from '../typing-indicator/typing-indicator.component';
import { EmptyStateComponent } from '@shared/components/empty-state/empty-state.component';



/**
 * MessageViewComponent - Smart Container for Message List
 *
 * Features:
 * - CDK Virtual Scroll for performance
 * - Auto-scroll to bottom on new messages
 * - Message grouping by time
 * - Typing indicator integration
 */
@Component({
  selector: 'app-message-view',
  standalone: true,
  imports: [
    CommonModule,
    ScrollingModule,
    MatIconModule,
    MessageBubbleComponent,
    TypingIndicatorComponent,
    EmptyStateComponent,
  ],
  template: `
    <!-- Empty state -->
    @if (displayMessages().length === 0 && !isTyping()) {
      <div class="empty-state-wrapper">
        <app-empty-state
          variant="messages"
          headline="Nema poruka"
          [subtext]="emptyStateText()"
          [ctaLabel]="null"
        />
      </div>
    }

    <!-- Message list with virtual scroll -->
    <cdk-virtual-scroll-viewport
      #scrollViewport
      [itemSize]="estimatedItemSize"
      class="messages-viewport"
      *ngIf="displayMessages().length > 0 || isTyping()"
    >
      <div class="messages-list">
        <!-- Date separators & messages -->
        <ng-container *cdkVirtualFor="let item of displayItems(); trackBy: trackByItem">
          <!-- Date separator -->
          <div *ngIf="item.type === 'date'" class="date-separator">
            <span>{{ item.date }}</span>
          </div>

          <!-- Message bubble -->
          <app-message-bubble
            *ngIf="item.type === 'message'"
            [message]="item.message!"
            [isOwn]="item.message!.senderId?.toString() === currentUserId()?.toString()"
            [showAvatar]="item.showAvatar ?? true"
            [isFirstInGroup]="item.isFirstInGroup ?? true"
            [isLastInGroup]="item.isLastInGroup ?? true"
            [senderProfilePicUrl]="getSenderProfilePicUrl(item.message!.senderId)"
          ></app-message-bubble>
        </ng-container>

        <!-- Typing indicator -->
        <app-typing-indicator
          *ngIf="isTyping()"
          [userName]="typingUserName()"
        ></app-typing-indicator>
      </div>
    </cdk-virtual-scroll-viewport>
  `,
  styles: [`
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
      overflow: hidden;
    }

    .empty-state-wrapper {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      flex: 1;
      height: 100%;
    }

    .messages-viewport {
      flex: 1;
      overflow-y: auto;

      &::-webkit-scrollbar {
        width: 6px;
      }

      &::-webkit-scrollbar-track {
        background: transparent;
      }

      &::-webkit-scrollbar-thumb {
        background: #e0e0e0;
        border-radius: 3px;

        &:hover {
          background: #ccc;
        }
      }
    }

    .messages-list {
      padding: 16px 20px;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .date-separator {
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 16px 0;

      span {
        background-color: #f0f0f0;
        padding: 6px 14px;
        border-radius: 14px;
        font-size: 12px;
        font-weight: 600;
        color: #666;
        text-transform: uppercase;
        letter-spacing: 0.3px;
      }
    }

    // Dark theme
    :host-context(.dark-theme) {
      .messages-viewport {
        &::-webkit-scrollbar-thumb {
          background: #444;

          &:hover {
            background: #555;
          }
        }
      }

      .date-separator span {
        background-color: #333;
        color: #b0b0b0;
      }
    }
  `],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MessageViewComponent implements AfterViewInit, OnDestroy {
  @ViewChild('scrollViewport') scrollViewport?: CdkVirtualScrollViewport;

  // Inputs
  conversation = input.required<ConversationDTO | null>();
  currentUserId = input.required<string>();
  messages = input<MessageDTO[]>([]); // Explicit messages input - takes precedence
  isTyping = input<boolean>(false);
  typingUserName = input<string>('');

  // Internal state
  private previousMessageCount = 0;
  estimatedItemSize = 72;

  // Computed: use explicit messages input, fallback to conversation.messages
  displayMessages = computed(() => {
    const explicitMessages = this.messages();
    if (explicitMessages && explicitMessages.length > 0) {
      return explicitMessages;
    }
    const conv = this.conversation();
    return conv?.messages || [];
  });

  // Computed: display items (messages + date separators)
  displayItems = computed(() => {
    const msgs = this.displayMessages();
    const items: DisplayItem[] = [];
    let lastDate = '';
    let lastSenderId = '';

    for (let i = 0; i < msgs.length; i++) {
      const msg = msgs[i];
      const msgDate = this.formatDateHeader(msg.timestamp);

      // Add date separator if date changed
      if (msgDate !== lastDate) {
        items.push({ type: 'date', date: msgDate, id: `date-${msgDate}` });
        lastDate = msgDate;
        lastSenderId = ''; // Reset grouping on new day
      }

      // Determine grouping
      const isNewGroup = msg.senderId !== lastSenderId;
      const nextMsg = msgs[i + 1];
      const isLastInGroup = !nextMsg ||
        nextMsg.senderId !== msg.senderId ||
        this.formatDateHeader(nextMsg.timestamp) !== msgDate;

      items.push({
        type: 'message',
        message: msg,
        id: `msg-${msg.id}`,
        showAvatar: isNewGroup,
        isFirstInGroup: isNewGroup,
        isLastInGroup,
      });

      lastSenderId = msg.senderId;
    }

    return items;
  });

  // Computed: empty state text
  emptyStateText = computed(() => {
    const conv = this.conversation();
    if (!conv) return 'Počnite razgovor';

    const tripStatus = conv.tripStatus?.toLowerCase();
    if (tripStatus === 'future') {
      return 'Pozdravi se da koordiniraš nadolazećim putovanjem!';
    } else if (tripStatus === 'current') {
      return 'Trebaš li pomoć tokom putovanja? Pošalji poruku.';
    } else if (tripStatus === 'past') {
      return 'Ovo putovanje je završeno. Ostavi povratnu informaciju ili postavi pitanja.';
    }
    return 'Pošalji poruku da počneš razgovor.';
  });

  /**
   * Get sender's profile picture URL based on senderId.
   * Returns renter's pic if sender is renter, owner's pic if sender is owner.
   */
  getSenderProfilePicUrl(senderId: string): string | null | undefined {
    const conv = this.conversation();
    if (!conv) return null;
    
    // Type-safe comparison - senderId may be number or string
    const senderIdStr = senderId?.toString();
    if (senderIdStr === conv.renterId?.toString()) {
      return conv.renterProfilePicUrl;
    } else if (senderIdStr === conv.ownerId?.toString()) {
      return conv.ownerProfilePicUrl;
    }
    return null;
  }

  constructor() {
    // Effect: scroll to bottom when new messages arrive
    effect(() => {
      const count = this.messages().length;
      if (count > this.previousMessageCount) {
        this.scrollToBottom();
      }
      this.previousMessageCount = count;
    });
  }

  ngAfterViewInit(): void {
    // Initial scroll to bottom
    setTimeout(() => this.scrollToBottom(), 100);
  }

  ngOnDestroy(): void {
    // Cleanup
  }

  private scrollToBottom(): void {
    if (this.scrollViewport) {
      setTimeout(() => {
        this.scrollViewport?.scrollTo({ bottom: 0, behavior: 'smooth' });
      }, 50);
    }
  }

  private formatDateHeader(timestamp: string | undefined): string {
    if (!timestamp) return 'Danas';

    const date = new Date(timestamp);
    const now = new Date();
    const yesterday = new Date(now);
    yesterday.setDate(yesterday.getDate() - 1);

    if (date.toDateString() === now.toDateString()) {
      return 'Danas';
    } else if (date.toDateString() === yesterday.toDateString()) {
      return 'Juče';
    } else if (date.getFullYear() === now.getFullYear()) {
      return date.toLocaleDateString('sr-RS', { weekday: 'long', month: 'long', day: 'numeric' });
    } else {
      return date.toLocaleDateString('sr-RS', { year: 'numeric', month: 'long', day: 'numeric' });
    }
  }

  trackByItem(index: number, item: DisplayItem): string {
    return item.id;
  }
}

interface DisplayItem {
  type: 'date' | 'message';
  id: string;
  date?: string;
  message?: MessageDTO;
  showAvatar?: boolean;
  isFirstInGroup?: boolean;
  isLastInGroup?: boolean;
}